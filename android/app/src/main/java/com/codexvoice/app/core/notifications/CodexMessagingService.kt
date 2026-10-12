package com.codexvoice.app.core.notifications

import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.codexvoice.app.core.remote.KeystoreCredentialStore
import android.content.Context
import android.annotation.SuppressLint

// FID registration replaces the deprecated token callback in Firebase Messaging 26.
@SuppressLint("MissingFirebaseInstanceTokenRefresh")
class CodexMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        try { PushStore.get(this).register(installationId) } catch (_: Exception) { /* Pending registration is retried on foreground startup. */ }
    }
    override fun onMessageReceived(message: RemoteMessage) {
        // Data-only pushes are handled immediately on Firebase's callback worker.
        try {
            val expectedSender = FirebaseApp.getInstance().options.gcmSenderId ?: return
            if (message.from != expectedSender || message.notification != null) return
            PushReceiver.receive(this, message.data)
        } catch (_: Exception) { /* Reject malformed/configuration failures without logging private data. */ }
    }
}
object PushReceiver {
    fun receive(context: Context, data: Map<String, String>) {
        val store = PushStore.get(context); val now = System.currentTimeMillis()
        val event = PushParser.parse(data, KeystoreCredentialStore(context).load(), now) ?: return
        if (!store.accept(event, now)) return
        if (TaskNotifications.post(context, event, store.state.value.preferences)) store.receipt(event.eventId, "posted")
        // No socket, task execution, analytics or automatic Activity launch here.
    }
}
