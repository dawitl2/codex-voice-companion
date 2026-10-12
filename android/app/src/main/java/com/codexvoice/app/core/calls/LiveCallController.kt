package com.codexvoice.app.core.calls

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.codexvoice.app.core.data.PreferencesRepository
import com.codexvoice.app.core.notifications.*
import com.codexvoice.app.core.remote.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class LiveCallController(private val context: Context, private val client: RemoteClient, preferences: PreferencesRepository, parent: CoroutineScope) {
    private val scope = CoroutineScope(parent.coroutineContext + Dispatchers.IO.limitedParallelism(1))
    val state = CallRuntime.state
    private val mutableStatus = MutableStateFlow("Call invitations disabled"); val status = mutableStatus.asStateFlow()
    private var enabled = false; private var foreground = false; private var fingerprint: String? = null
    private var wantedEvent: String? = null; private var requestedEvent: String? = null
    private val pending = mutableMapOf<String, String>()
    private var resumes = 0; private var resumePending = false; private var pollAt = 0L
    private var connectDeadline = 0L
    init {
        scope.launch { preferences.preferences.collect { enabled = it.callInvitations; sync() } }
        scope.launch { client.state.collect { connection ->
            if (connection.status !in setOf(RemoteStatus.CONNECTED, RemoteStatus.BRIDGE_UNAVAILABLE)) { fingerprint = null; requestedEvent = null; pending.clear(); resumePending = false }
            if (connection.status in setOf(RemoteStatus.AUTH_FAILED, RemoteStatus.STORAGE_ERROR) ||
                (connection.status == RemoteStatus.UNPAIRED && KeystoreCredentialStore(context).load() == null)) {
                if (state.value.serviceRunning) CallRuntime.command(context, CallAudioService.END)
                else if (state.value.phase == LivePhase.OFFERED) CallRuntime.update { it.copy(phase = LivePhase.FAILED, problem = "Pairing unavailable") }
            }
            sync()
        } }
        scope.launch { client.calls.collect { response ->
            val operation = pending.remove(response.correlationId) ?: return@collect
            when (response.type) {
                "call.preferences.response" -> mutableStatus.value = if (response.payload["enabled"].asBoolean == enabled) { if (enabled) "Call invitations synchronized" else "Call invitations disabled" } else "Call invitation settings pending"
                "call.status.response" -> {
                    if (!response.payload["call"].isJsonNull) {
                        val offer = CallWire.offer(response.payload.getAsJsonObject("call"))
                        if (state.value.serviceRunning) {
                            if (offer.callId == state.value.offer?.callId && offer.state in setOf("ended","failed","expired","declined")) CallRuntime.command(context, CallAudioService.END)
                        } else if (operation == "current" || (operation == "offer" && offer.eventId == wantedEvent)) {
                            resumes = 0; wantedEvent = null
                            CallDiagnostics.record(context, "offer_received")
                            CallRuntime.update { LiveCallState(phase = phase(offer.state), offer = offer, muted = offer.muted) }
                        } else if (offer.callId == state.value.offer?.callId &&
                            acceptsCallSnapshot(state.value.phase, offer.state)) {
                            // A status requested before Answer must not regress a connecting call.
                            CallRuntime.update { it.copy(offer = offer, phase = phase(offer.state), problem = if (offer.state == "expired") "Call invitation expired; task results remain available" else it.problem) }
                        }
                    } else if (operation == "offer") wantedEvent = null
                }
                "call.media.response" -> {
                    resumePending = false
                    val grant = CallWire.grant(response.payload)
                    if (state.value.phase !in setOf(LivePhase.CONNECTING, LivePhase.RECONNECTING)) {
                        CallDiagnostics.record(context, "grant_stale")
                        request("call.end", mapOf("callId" to grant.call.callId), "end"); return@collect
                    }
                    val credential = KeystoreCredentialStore(context).load()
                    val expected = credential?.let { java.net.URI(it.endpoint) }; val media = java.net.URI(grant.endpoint)
                    val allowed = foreground && credential != null && expected?.host == media.host && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (!allowed || grant.call.callId != state.value.offer?.callId || grant.expiresAt <= System.currentTimeMillis()) {
                        CallDiagnostics.record(context, if (!foreground) "grant_not_visible" else "grant_rejected")
                        request("call.end", mapOf("callId" to grant.call.callId), "end"); CallRuntime.update { it.copy(phase = LivePhase.FAILED, problem = "Return to the app to start an authorized microphone session") }; return@collect
                    }
                    CallRuntime.prepare(grant)
                    try { CallDiagnostics.record(context, "service_requested"); ContextCompat.startForegroundService(context, Intent(context, CallAudioService::class.java).setAction(CallAudioService.CONNECT)) }
                    catch (_: Exception) { request("call.end", mapOf("callId" to grant.call.callId), "end"); CallRuntime.update { it.copy(phase = LivePhase.FAILED, problem = "Android could not start the microphone service") } }
                }
                "call.error" -> {
                    resumePending = false
                    val code = response.payload["code"].asString
                    if (operation == "preferences") mutableStatus.value = if (code == "calls_disabled") "PC call support is disabled; start it with --calls" else "Call invitation settings pending"
                    else { if (state.value.serviceRunning) CallRuntime.command(context, CallAudioService.END)
                        CallRuntime.update { it.copy(phase = if (code == "call_not_available") LivePhase.EXPIRED else LivePhase.FAILED, problem = if (code == "call_not_available") "Call invitation unavailable or expired; review task results" else "Call setup failed. Reconnect to the PC and try a new invitation.") } }
                }
            }
        } }
        scope.launch { state.collect { sync() } }
        scope.launch { while (isActive) { delay(1000); sync() } }
    }
    private fun phase(state: String) = when (state) { "offered" -> LivePhase.OFFERED; "connecting" -> LivePhase.CONNECTING; "active" -> LivePhase.ACTIVE; "reconnecting" -> LivePhase.RECONNECTING; "ended" -> LivePhase.ENDED; "declined" -> LivePhase.DECLINED; "expired" -> LivePhase.EXPIRED; else -> LivePhase.FAILED }
    private fun request(type: String, payload: Map<String, Any>, operation: String) { if (pending.size < 16) pending[client.call(type, payload)] = operation }
    private fun sync() {
        val connected = client.state.value.status in setOf(RemoteStatus.CONNECTED, RemoteStatus.BRIDGE_UNAVAILABLE)
        val before = state.value
        if (before.phase == LivePhase.CONNECTING && !before.serviceRunning && connectDeadline > 0 && System.currentTimeMillis() >= connectDeadline) {
            connectDeadline = 0; if (connected) before.offer?.let { request("call.end", mapOf("callId" to it.callId), "end") }
            CallRuntime.update { it.copy(phase = LivePhase.FAILED, problem = "Call setup timed out; no microphone session started") }
        }
        if (before.phase == LivePhase.OFFERED && before.offer != null && before.offer.expiresAt <= System.currentTimeMillis()) CallRuntime.update { it.copy(phase = LivePhase.EXPIRED, problem = "Call invitation expired; task results remain available") }
        if (!connected) { mutableStatus.value = if (enabled) "Call invitation settings pending; connect to PC" else "Call invitations disabled"; return }
        val key = "${client.state.value.snapshot?.epoch}|$enabled"
        if (fingerprint != key) { fingerprint = key; request("call.preferences.update", mapOf("enabled" to enabled), "preferences") }
        if (enabled && wantedEvent != null && requestedEvent != wantedEvent && !state.value.serviceRunning) {
            requestedEvent = wantedEvent; request("call.offer.request", mapOf("eventId" to wantedEvent!!), "offer")
        }
        val current = state.value
        if (mayRecover(current.phase, foreground, connected, resumes) && !resumePending && current.offer != null) {
            resumes++; resumePending = true; request("call.resume", mapOf("callId" to current.offer.callId), "resume")
        }
        if (current.offer != null && current.phase in setOf(LivePhase.OFFERED, LivePhase.ACTIVE) && System.currentTimeMillis() - pollAt > 5000) {
            pollAt = System.currentTimeMillis(); request("call.status.request", mapOf("callId" to current.offer.callId), "status")
        }
        if (current.phase == LivePhase.OFFERED && current.offer != null && current.offer.expiresAt <= System.currentTimeMillis()) CallRuntime.update { it.copy(phase = LivePhase.EXPIRED, problem = "Call invitation expired; task results remain available") }
    }
    fun foreground(active: Boolean) = scope.launch { foreground = active; sync() }
    fun openCurrent() = scope.launch { if (state.value.serviceRunning) return@launch; wantedEvent=null; requestedEvent=null; request("call.current.request", emptyMap(), "current") }
    fun open(eventId: String) = scope.launch {
        val event = PushStore.get(context).state.value.events.find { it.eventId == eventId } ?: return@launch
        if (PushParser.parse(event.data(), KeystoreCredentialStore(context).load(), System.currentTimeMillis(), allowExpired = true) == null) return@launch
        wantedEvent = eventId; requestedEvent = null; sync()
    }
    fun answer(permitted: Boolean) = scope.launch {
        if (state.value.phase != LivePhase.OFFERED) return@launch
        val offer = state.value.offer ?: return@launch
        if (!permitted) { CallRuntime.update { it.copy(permissionDenied = true, problem = "Microphone permission is needed for a real audio session. No capture started.") }; return@launch }
        if (!TaskNotifications.permission(context)) { CallRuntime.update { it.copy(problem = "Enable Android notifications so the ongoing audio session stays visible") }; return@launch }
        if (!canAnswer(offer, System.currentTimeMillis(), foreground, permitted) || client.state.value.status != RemoteStatus.CONNECTED) { CallRuntime.update { it.copy(problem = "Call is unavailable; connect to the PC or review results") }; return@launch }
        CallRuntime.update { it.copy(phase = LivePhase.CONNECTING, permissionDenied = false, problem = "") }
        CallDiagnostics.record(context, "answer_requested")
        connectDeadline = System.currentTimeMillis() + 20000
        request("call.accept", mapOf("callId" to offer.callId), "accept")
    }
    fun decline() = scope.launch {
        state.value.offer?.let { request("call.decline", mapOf("callId" to it.callId), "decline") }
        CallRuntime.update { it.copy(phase = LivePhase.DECLINED, capturing = false, playing = false, problem = "Declined by you") }
    }
    fun mute() { CallRuntime.command(context, CallAudioService.MUTE) }
    fun speaker() { CallRuntime.command(context, CallAudioService.SPEAKER) }
    fun tone() { CallRuntime.command(context, CallAudioService.TONE) }
    fun results() = scope.launch {
        if (state.value.serviceRunning) return@launch
        state.value.offer?.takeIf { it.state == "offered" }?.let { request("call.decline", mapOf("callId" to it.callId), "decline") }
        wantedEvent = null; requestedEvent = null; CallRuntime.dismiss()
    }
    fun end() = scope.launch {
        CallDiagnostics.record(context, "user_ended")
        if (state.value.serviceRunning) CallRuntime.command(context, CallAudioService.END)
        else { state.value.offer?.let { request("call.end", mapOf("callId" to it.callId), "end") }; CallRuntime.update { it.copy(phase = LivePhase.ENDED, problem = "Cancelled by you") } }
    }
    fun dismiss() { if (!state.value.serviceRunning) { CallDiagnostics.record(context, "ui_dismissed"); wantedEvent = null; requestedEvent = null; CallRuntime.dismiss() } }
}
