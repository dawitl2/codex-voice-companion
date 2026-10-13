package com.codexvoice.app.core.notifications

import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.codexvoice.app.core.remote.KeystoreCredentialStore
import android.content.Context
import android.annotation.SuppressLint
import com.codexvoice.app.preferencesStore
import kotlinx.coroutines.flow.first

// FID registration replaces the deprecated token callback in Firebase Messaging 26.
@SuppressLint("MissingFirebaseInstanceTokenRefresh")
class CodexMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        try { PushStore.get(this).register(installationId); PushDiagnostics.record(this, "registered") } catch (_: Exception) { /* Pending registration is retried on foreground startup. */ }
    }
    override fun onMessageReceived(message: RemoteMessage) {
        // Data-only pushes are handled immediately on Firebase's callback worker.
        try {
            PushDiagnostics.record(this, "service_received")
            val expectedSender = FirebaseApp.getInstance().options.gcmSenderId ?: return
            if (message.from != expectedSender || message.notification != null) { PushDiagnostics.record(this, "sender_rejected"); return }
            PushReceiver.receive(this, message.data, message.priority==RemoteMessage.PRIORITY_HIGH)
        } catch (_: Exception) { PushDiagnostics.record(this, "receiver_error") }
    }
}
object PushReceiver {
    fun receive(context: Context, data: Map<String, String>, allowVoip:Boolean=false) {
        val store = PushStore.get(context); val now = System.currentTimeMillis()
        val event = PushParser.parse(data, KeystoreCredentialStore(context).load(), now) ?: run { PushDiagnostics.record(context, "envelope_rejected"); return }
        if (!store.accept(event, now)) { PushDiagnostics.record(context, "local_filtered"); return }
        val callPrefs=kotlinx.coroutines.runBlocking{com.codexvoice.app.core.data.DataStorePreferencesRepository(context.applicationContext.preferencesStore).preferences.first()}
        if(allowVoip&&event.mode=="real"&&callPrefs.notificationsEnabled&&callPrefs.callInvitations&&TaskNotifications.permission(context)&&!com.codexvoice.app.core.calls.CallRuntime.state.value.serviceRunning){
            com.codexvoice.app.core.calls.VoipCallService.incoming(context,event);store.receipt(event.eventId,"posted");PushDiagnostics.record(context,"call_service_requested");return
        }
        if (TaskNotifications.post(context, event, store.state.value.preferences)) { store.receipt(event.eventId, "posted"); PushDiagnostics.record(context, "posted") }
        else PushDiagnostics.record(context, "system_blocked")
        // Only the supported VoIP foreground service may connect private call control. No microphone or task execution here.
    }
}
