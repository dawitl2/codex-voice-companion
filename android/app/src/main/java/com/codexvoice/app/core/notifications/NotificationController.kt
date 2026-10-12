package com.codexvoice.app.core.notifications

import android.content.Context
import com.codexvoice.app.core.data.PreferencesRepository
import com.codexvoice.app.core.remote.*
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class NotificationUiState(val status: String = "Notifications disabled", val permission: Boolean = false, val configured: Boolean = false,
    val registered: Boolean = false, val pending: Boolean = true, val alert: PushEvent? = null, val details: JsonObject? = null, val viewingDetails: Boolean = false)
class NotificationController(private val context: Context, private val client: RemoteClient, settings: PreferencesRepository, parentScope: CoroutineScope) {
    private val scope = CoroutineScope(parentScope.coroutineContext + Dispatchers.IO.limitedParallelism(1))
    private val mutable = MutableStateFlow(NotificationUiState(permission = TaskNotifications.permission(context))); val state = mutable.asStateFlow()
    private val store = PushStore.get(context)
    private var fingerprint: String? = null
    private var detailEpoch: String? = null
    private val receiptPending = mutableSetOf<PushReceipt>()
    private var registrationAttempt = false
    private var receiptWindow = 0L
    private var receiptCount = 0
    init {
        scope.launch { settings.preferences.collect { prefs ->
            val value = prefs.pushPreferences()
            try { if (store.state.value.preferences != value) store.update { it.copy(preferences = value) } }
            catch (_: Exception) { mutable.value = state.value.copy(status = "Notification settings could not be protected", pending = true); return@collect }
            initializeFirebase(value.enabled && TaskNotifications.permission(context)); sync()
        } }
        scope.launch { store.state.collect { sync() } }
        scope.launch { client.state.collect { connection ->
            mutable.value = state.value.copy(permission = TaskNotifications.permission(context))
            if (connection.status !in setOf(RemoteStatus.CONNECTED, RemoteStatus.BRIDGE_UNAVAILABLE)) {
                fingerprint = null; detailEpoch = null; receiptPending.clear()
                mutable.value = state.value.copy(pending = true, registered = false, details = null)
            }; sync()
        } }
        scope.launch { client.notifications.collect { message ->
            when (message.type) {
                "notification.status.response" -> {
                    val configured = message.payload["configured"].asBoolean; val registered = message.payload["registered"].asBoolean
                    val matches = message.payload.getAsJsonObject("preferences").entrySet().all { (key, value) -> serverPreferences()[key] == value.asBoolean }
                    mutable.value = state.value.copy(configured = configured, registered = registered, pending = !matches,
                        status = if (!matches) "Settings pending synchronization" else if (!store.state.value.preferences.enabled) "Notifications disabled" else if (!TaskNotifications.permission(context)) "Notifications blocked by Android; open system settings" else if (!configured) "PC push provider is disabled" else if (registered) "Registered with paired PC" else "Registration pending")
                }
                "notification.receipt.response" -> {
                    val id = message.payload["eventId"].asString; val stage = message.payload["stage"].asString
                    receiptPending.removeAll { it.eventId == id && it.stage == stage }; try { store.acknowledge(id, stage) } catch (_: Exception) { }
                }
                "notification.event.response" -> if (message.payload["eventId"].asString == state.value.alert?.eventId) mutable.value = state.value.copy(details = message.payload)
                "notification.error" -> { mutable.value = state.value.copy(status = when (message.payload["code"].asString) {
                    "firebase_project_mismatch" -> "Phone and PC Firebase projects differ or PC project is unset"
                    "notifications_unavailable" -> "PC notification service is unavailable"
                    "unknown_event" -> "Alert details are unavailable on this PC"
                    else -> "Notification synchronization failed; reconnect to retry"
                }, pending = true) }
            }
        } }
        scope.launch {
            client.state.map { it.status in setOf(RemoteStatus.CONNECTED, RemoteStatus.BRIDGE_UNAVAILABLE) }.distinctUntilChanged().collectLatest { connected ->
                if (connected) while (isActive) { delay(1000); sync() }
            }
        }
    }
    private fun initializeFirebase(enabled: Boolean) {
        try {
            val app = FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context)
            if (app == null) { mutable.value = state.value.copy(status = "Firebase client configuration is missing", configured = false); return }
            val messaging = FirebaseMessaging.getInstance()
            messaging.isAutoInitEnabled = enabled
            if (enabled && !registrationAttempt) {
                registrationAttempt = true
                messaging.register().addOnFailureListener { scope.launch { mutable.value = state.value.copy(status = "Firebase registration failed; reopen to retry", pending = true) } }
            }
        } catch (_: Exception) { mutable.value = state.value.copy(status = "Firebase configuration is unavailable", configured = false) }
    }
    private fun serverPreferences() = store.state.value.preferences.server() + ("enabled" to (store.state.value.preferences.enabled && TaskNotifications.permission(context)))
    private fun sync() {
        val connection = client.state.value; val local = store.state.value
        if (connection.status !in setOf(RemoteStatus.CONNECTED, RemoteStatus.BRIDGE_UNAVAILABLE)) {
            mutable.value = state.value.copy(status = if (!local.preferences.enabled) "Notifications disabled" else if (!connection.paired) "Pair a PC to register notifications" else "Settings pending; connect to the PC", pending = true)
            return
        }
        val projectId = try { FirebaseApp.getInstance().options.projectId } catch (_: Exception) { null }
        val preferences = serverPreferences()
        val key = "${connection.snapshot?.epoch}|${local.fid}|$preferences"
        if (fingerprint != key) {
            fingerprint = key; mutable.value = state.value.copy(pending = true)
            if (local.fid != null && projectId != null) client.notification("notification.register", mapOf("fid" to local.fid, "projectId" to projectId, "preferences" to preferences))
            else if (!local.preferences.enabled) client.notification("notification.status.request")
            else mutable.value = state.value.copy(status = "Firebase installation registration pending")
        }
        val credential = try { KeystoreCredentialStore(context).load() } catch (_: Exception) { null }
        val applicable = local.receipts.filter { receipt -> local.events.any { it.eventId == receipt.eventId && it.pairingId == credential?.deviceId } }
        if (applicable.size != local.receipts.size) { try { store.update { it.copy(receipts = applicable) } } catch (_: Exception) { }; return }
        val now = System.currentTimeMillis()
        if (now - receiptWindow >= 10000) { receiptWindow = now; receiptCount = 0 }
        for (receipt in applicable) if (receiptCount < 8 && receiptPending.add(receipt)) {
            receiptCount++; client.notification("notification.receipt", mapOf("eventId" to receipt.eventId, "stage" to receipt.stage))
        }
        val event = state.value.alert
        if (event != null && detailEpoch != connection.snapshot?.epoch) {
            detailEpoch = connection.snapshot?.epoch
            client.notification("notification.event.request", mapOf("eventId" to event.eventId)); client.refresh()
        }
    }
    fun open(eventId: String) = scope.launch {
        try {
            val event = store.state.value.events.find { it.eventId == eventId } ?: return@launch
            val verified = PushParser.parse(event.data(), KeystoreCredentialStore(context).load(), System.currentTimeMillis(), allowExpired = true) ?: return@launch
            TaskNotifications.cancel(context, eventId); store.receipt(eventId, "opened")
            detailEpoch = null; mutable.value = state.value.copy(alert = verified, details = null, viewingDetails = false); sync()
        } catch (_: Exception) { mutable.value = state.value.copy(status = "Protected alert is unavailable") }
    }
    fun viewResults() = scope.launch { mutable.value = state.value.copy(viewingDetails = true); detailEpoch = null; sync() }
    fun dismiss() = scope.launch { state.value.alert?.let { try { TaskNotifications.cancel(context, it.eventId); store.receipt(it.eventId, "dismissed") } catch (_: Exception) { } }; mutable.value = state.value.copy(alert = null, details = null, viewingDetails = false) }
    fun systemStateChanged() = scope.launch { mutable.value = state.value.copy(permission = TaskNotifications.permission(context)); initializeFirebase(store.state.value.preferences.enabled && TaskNotifications.permission(context)); sync() }
}
