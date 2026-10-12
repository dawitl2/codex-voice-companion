package com.codexvoice.app.core.remote

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlin.random.Random
import java.util.UUID

enum class RemoteStatus { UNPAIRED, DISCONNECTED, CONNECTING, PAIRING, AUTHENTICATING, CONNECTED, RECONNECTING, AUTH_FAILED, PAIR_FAILED, BRIDGE_UNAVAILABLE, PROTOCOL_INCOMPATIBLE, TLS_ERROR, STORAGE_ERROR }
data class ClientState(val status: RemoteStatus = RemoteStatus.UNPAIRED, val endpoint: String = "", val bridgeName: String = "",
    val paired: Boolean = false, val lastConnected: Long = 0, val snapshot: RemoteSnapshot? = null, val problem: String = "", val enabled: Boolean = false)
fun reconnectDelay(attempt: Int, jitter: Double): Long? = if (attempt !in 0..5) null else ((1000L shl attempt) * (.75 + jitter.coerceIn(0.0, 1.0) * .5)).toLong()

class RemoteClient(private val scope: CoroutineScope, private val sockets: SocketFactory, private val store: CredentialStore,
    private val now: () -> Long = System::currentTimeMillis, private val jitter: () -> Double = { Random.nextDouble() }) {
    private val mutableState = MutableStateFlow(ClientState()); val state = mutableState.asStateFlow()
    private val mutableNotifications = MutableSharedFlow<ServerMessage>(extraBufferCapacity = 32); val notifications = mutableNotifications.asSharedFlow()
    private val mutableCalls = MutableSharedFlow<ServerMessage>(extraBufferCapacity = 32); val calls = mutableCalls.asSharedFlow()
    private val mutableSpeech = MutableSharedFlow<ServerMessage>(extraBufferCapacity = 32); val speech = mutableSpeech.asSharedFlow()
    private val notificationPending = mutableMapOf<String, String>()
    private val queue = Channel<() -> Unit>(64)
    @Volatile private var closed = false
    private var credential: Credential? = null; private var socket: RemoteSocket? = null; private var generation = 0
    private var timeout: Job? = null; private var retry: Job? = null; private var heartbeat: Job? = null
    private var attempts = 0; private var foreground = false; private var desired = false; private var authenticated = false
    private var invitation: String? = null; private var helloId: String? = null; private var authId: String? = null; private var snapshotId: String? = null
    private var sessionEpoch: String? = null
    init {
        scope.launch { for (action in queue) if (!closed) action() }
        post {
            try { credential = store.load(); credential?.let { mutableState.value = ClientState(RemoteStatus.DISCONNECTED, it.endpoint, it.bridgeName, true, it.lastConnected, enabled = it.enabled) } }
            catch (_: Exception) { mutableState.value = ClientState(status = RemoteStatus.STORAGE_ERROR, problem = "Protected pairing data is unavailable. Forget this PC and pair again.") }
        }
    }
    private fun post(action: () -> Unit) {
        if (closed) return
        if (queue.trySend(action).isFailure) {
            closed = true; socket?.close(); timeout?.cancel(); retry?.cancel(); heartbeat?.cancel(); queue.close()
            mutableState.value = state.value.copy(status = RemoteStatus.PROTOCOL_INCOMPATIBLE, problem = "Too many queued connection messages. Restart the app to reconnect.")
        }
    }
    fun foreground(active: Boolean) = post {
        val changed = foreground != active
        foreground = active
        if (active && !changed) return@post
        if (!active) { stopSocket(); if (credential != null) update(RemoteStatus.DISCONNECTED, "App paused; displayed history may be stale.") }
        else if (credential?.enabled == true && !desired) { desired = true; attempts = 0; open() }
        else if (desired && credential != null) { attempts = 0; open() }
    }
    fun pair(address: String, code: String) = post {
        try {
            val endpoint = RemoteProtocol.endpoint(address); val normalized = code.trim().replace("-", "").replace(" ", "").uppercase()
            require(normalized.matches(Regex("[A-F0-9]{16}")))
            if (credential != null) { update(RemoteStatus.PAIR_FAILED, "Forget or revoke the existing pairing first."); return@post }
            stopSocket(); invitation = normalized; desired = true; attempts = 0
            mutableState.value = ClientState(status = RemoteStatus.CONNECTING, endpoint = endpoint); open()
        } catch (_: Exception) { update(RemoteStatus.PAIR_FAILED, "Use the private WSS address and the 16-character invitation from the PC.") }
    }
    fun connect() = post {
        if (credential == null) { update(RemoteStatus.UNPAIRED, "Pair this PC first."); return@post }
        try { credential = credential!!.copy(enabled = true); store.save(credential!!); desired = true; attempts = 0; open() }
        catch (_: Exception) { stopSocket(); update(RemoteStatus.STORAGE_ERROR, "Could not save connection preferences.") }
    }
    fun disconnect() = post {
        desired = false; stopSocket(); invitation = null
        try { credential = credential?.copy(enabled = false); credential?.let(store::save); update(if (credential == null) RemoteStatus.UNPAIRED else RemoteStatus.DISCONNECTED, "Disconnected by you.") }
        catch (_: Exception) { update(RemoteStatus.STORAGE_ERROR, "Disconnect preference could not be saved. Forget this PC to disable automatic connection.") }
    }
    fun forget() = post {
        desired = false; stopSocket(); invitation = null
        try { store.clear(); credential = null; mutableState.value = ClientState() }
        catch (_: Exception) { update(RemoteStatus.STORAGE_ERROR, "Could not remove protected pairing data.") }
    }
    fun refresh() = post { if (authenticated) requestSnapshot() }
    fun notification(type: String, payload: Map<String, Any> = emptyMap()): String {
        val requestId = UUID.randomUUID().toString()
        post {
        if (!authenticated || notificationPending.size >= 32) return@post
        val expected = when (type) {
            "notification.register", "notification.preferences.update", "notification.status.request" -> "notification.status.response"
            "notification.event.request" -> "notification.event.response"
            "notification.receipt" -> "notification.receipt.response"
            "call.preferences.request", "call.preferences.update" -> "call.preferences.response"
            "call.accept", "call.resume" -> "call.media.response"
            "call.current.request", "call.offer.request", "call.decline", "call.end", "call.mute", "call.status.request", "call.test-tone" -> "call.status.response"
            "speech.summary.request" -> "speech.summary.response"
            "speech.status.request", "speech.listening", "speech.finish", "speech.playback" -> "speech.status.response"
            else -> return@post
        }
        try { notificationPending[send(type, payload, requestId)] = expected } catch (_: Exception) { fail("unreachable") }
        }
        return requestId
    }
    fun call(type: String, payload: Map<String, Any> = emptyMap()): String = notification(type, payload)
    fun speech(type: String, payload: Map<String, Any> = emptyMap()): String = notification(type, payload)
    fun close() { closed = true; desired = false; generation++; socket?.close(); timeout?.cancel(); retry?.cancel(); heartbeat?.cancel(); queue.close() }
    private fun update(status: RemoteStatus, problem: String = "") {
        mutableState.value = mutableState.value.copy(status = status, problem = problem, paired = credential != null, enabled = credential?.enabled == true)
    }
    private fun stopSocket() {
        generation++; socket?.close(); socket = null; authenticated = false; timeout?.cancel(); retry?.cancel(); heartbeat?.cancel()
        helloId = null; authId = null; snapshotId = null; sessionEpoch = null
        notificationPending.clear()
    }
    private fun open() {
        stopSocket(); if (!desired || !foreground) return
        val attemptGeneration = generation
        update(if (attempts == 0) RemoteStatus.CONNECTING else RemoteStatus.RECONNECTING)
        try {
            socket = sockets.open(mutableState.value.endpoint, object : SocketObserver {
                override fun opened() = post {
                    if (attemptGeneration == generation) {
                        try { helloId = send("client.hello", mapOf("name" to "Codex Voice Android")); deadline(attemptGeneration) }
                        catch (_: Exception) { fail("unreachable") }
                    }
                }
                override fun message(text: String) {
                    if (text.length > 262144) { failed("invalid_message"); return }
                    post { if (attemptGeneration == generation) receive(text) }
                }
                override fun failed(reason: String) = post { if (attemptGeneration == generation) fail(reason) }
            })
            deadline(attemptGeneration)
        } catch (_: Exception) { fail("unreachable") }
    }
    private fun deadline(id: Int) {
        timeout?.cancel(); timeout = scope.launch { delay(15000); post { if (id == generation) fail("timeout") } }
    }
    private fun send(type: String, payload: Map<String, Any> = emptyMap(), id: String = UUID.randomUUID().toString()): String {
        check(socket?.send(RemoteProtocol.message(type, payload, id)) == true)
        return id
    }
    private fun requestSnapshot() {
        try { snapshotId = send("state.snapshot.request"); deadline(generation) } catch (_: Exception) { fail("unreachable") }
    }
    private fun receive(raw: String) {
        try {
            val m = RemoteProtocol.parse(raw)
            if (m.type.startsWith("notification.") || m.type.startsWith("call.") || m.type.startsWith("speech.")) {
                check(authenticated)
                val expected = notificationPending.remove(m.correlationId) ?: error("Uncorrelated notification response")
                check(m.type == expected || (m.type == "notification.error" && expected.startsWith("notification.")) || (m.type == "call.error" && expected.startsWith("call.")) || (m.type == "speech.error" && expected.startsWith("speech.")))
                if (m.type.startsWith("speech.")) mutableSpeech.tryEmit(m) else if (m.type.startsWith("call.")) mutableCalls.tryEmit(m) else mutableNotifications.tryEmit(m); return
            }
            when (m.type) {
                "server.hello" -> {
                    check(!authenticated && helloId != null && m.correlationId == helloId && authId == null)
                    update(if (invitation == null) RemoteStatus.AUTHENTICATING else RemoteStatus.PAIRING)
                    authId = if (invitation != null) send("pair.request", mapOf("code" to invitation!!)) else {
                        val c = checkNotNull(credential); send("auth.request", mapOf("deviceId" to c.deviceId, "token" to c.token))
                    }; invitation = null; deadline(generation)
                }
                "pair.success" -> {
                    check(!authenticated && state.value.status == RemoteStatus.PAIRING && m.correlationId == authId)
                    val c = Credential(state.value.endpoint, RemoteProtocol.string(m.payload, "deviceId", 36), RemoteProtocol.string(m.payload, "token", 43), RemoteProtocol.string(m.payload, "bridgeName", 80))
                    try { store.save(c); credential = c } catch (_: Exception) { desired = false; stopSocket(); update(RemoteStatus.STORAGE_ERROR, "Pairing could not be stored. Revoke this device on the PC, then pair again."); return }
                    authenticated = true; mutableState.value = state.value.copy(bridgeName = c.bridgeName, paired = true); requestSnapshot()
                }
                "auth.success" -> {
                    check(!authenticated && state.value.status == RemoteStatus.AUTHENTICATING && m.correlationId == authId && RemoteProtocol.string(m.payload, "deviceId", 36) == credential?.deviceId)
                    authenticated = true; requestSnapshot()
                }
                "auth.failed", "pair.failed" -> {
                    check((authenticated && m.type == "auth.failed") || (authId != null && m.correlationId == authId))
                    desired = false; stopSocket()
                    val code = RemoteProtocol.string(m.payload, "code", 64)
                    if (m.type == "auth.failed" && code != "rate_limited") { try { store.clear(); credential = null } catch (_: Exception) { update(RemoteStatus.STORAGE_ERROR, "Could not remove invalid pairing data."); return } }
                    update(if (m.type == "auth.failed") RemoteStatus.AUTH_FAILED else RemoteStatus.PAIR_FAILED, errorMessage(code))
                }
                "protocol.error", "request.error" -> fail(RemoteProtocol.string(m.payload, "code", 64))
                "heartbeat" -> check(authenticated)
                else -> {
                    check(authenticated); val incoming = checkNotNull(m.snapshot)
                    check(sessionEpoch == null || sessionEpoch == incoming.epoch)
                    sessionEpoch = incoming.epoch
                    val previous = state.value.snapshot
                    // Epoch changes are accepted only after a new authenticated session.
                    if (previous != null && previous.epoch == incoming.epoch && incoming.sequence < previous.sequence) return
                    timeout?.cancel(); attempts = 0
                    val c = checkNotNull(credential); val connectedAt = now()
                    if (c.lastConnected == 0L || connectedAt - c.lastConnected > 60000) {
                        try { credential = c.copy(lastConnected = connectedAt); store.save(credential!!) }
                        catch (_: Exception) { desired = false; stopSocket(); update(RemoteStatus.STORAGE_ERROR, "Could not update protected pairing data."); return }
                    }
                    mutableState.value = state.value.copy(snapshot = incoming, lastConnected = connectedAt,
                        status = if (incoming.available) RemoteStatus.CONNECTED else RemoteStatus.BRIDGE_UNAVAILABLE, problem = if (incoming.available) "" else "The PC monitor is reachable; Codex is unavailable.", paired = true, enabled = true)
                    if (!closed && heartbeat?.isActive != true) heartbeat = scope.launch { while (isActive) { delay(20000); post { if (authenticated) requestSnapshot() } } }
                }
            }
        } catch (e: RemoteProtocolException) { fail(e.reason) } catch (_: Exception) { fail("invalid_message") }
    }
    private fun fail(reason: String) {
        val pairing = credential == null; stopSocket()
        if (!desired) return
        if (reason in setOf("incompatible_version", "unknown_message", "invalid_message", "tls_failure", "binary_not_supported", "rate_limited")) {
            desired = false; update(if (reason == "tls_failure") RemoteStatus.TLS_ERROR else RemoteStatus.PROTOCOL_INCOMPATIBLE, errorMessage(reason)); return
        }
        if (pairing) { desired = false; invitation = null; update(RemoteStatus.PAIR_FAILED, "Pairing connection failed. Check Tailscale and request a fresh PC invitation."); return }
        val wait = reconnectDelay(attempts++, jitter())
        if (!foreground || wait == null) { desired = false; update(RemoteStatus.DISCONNECTED, "PC unreachable. Check Tailscale, then tap Connect."); return }
        update(RemoteStatus.RECONNECTING, "Connection lost; cached history may be stale.")
        retry = scope.launch { delay(wait); post { if (desired && foreground) open() } }
    }
    private fun errorMessage(code: String) = when (code) {
        "revoked", "invalid_credentials" -> "This device needs pairing again. Request a new invitation on the PC."
        "pairing_expired" -> "The invitation expired or was already used. Request a new code on the PC."
        "incorrect_code" -> "Pairing code did not match. Check the invitation on the PC."
        "rate_limited" -> "Too many attempts. Wait one minute before reconnecting."
        "device_limit" -> "Revoke the existing paired device on the PC before pairing another."
        "storage_unavailable" -> "The PC could not save the device securely. Check the PC bridge before pairing again."
        "tls_failure" -> "Secure certificate verification failed. Check the private server address."
        "incompatible_version" -> "Phone and bridge protocol versions differ. Update both."
        else -> "The bridge returned an incompatible or invalid message. Check versions on the PC."
    }
}
