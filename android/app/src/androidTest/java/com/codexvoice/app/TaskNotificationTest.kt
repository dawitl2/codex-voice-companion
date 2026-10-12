package com.codexvoice.app

import android.app.NotificationManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.codexvoice.app.core.designsystem.CodexVoiceTheme
import com.codexvoice.app.core.notifications.*
import com.codexvoice.app.core.remote.*
import com.codexvoice.app.feature.call.TaskAlertScreen
import com.google.firebase.FirebaseApp
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Device-local signed fixtures exercise Android behavior; these are NOT Firebase delivery tests. */
class TaskNotificationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(kind: String = "completed"): Pair<Credential, Map<String, String>> {
        val credential = Credential("wss://test.example.ts.net/remote", UUID.randomUUID().toString(), "A".repeat(43), "Local fixture", enabled = false)
        val now = System.currentTimeMillis(); val data = linkedMapOf("version" to "1", "eventId" to "a".repeat(64), "pairingId" to credential.deviceId,
            "kind" to kind, "occurredAt" to Instant.ofEpochMilli(now).toString(), "expiresAt" to Instant.ofEpochMilli(now + 60000).toString(), "mode" to "mock")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(credential.token.toByteArray()), "HmacSHA256")) }
        return credential to (data + ("signature" to Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.values.joinToString("\n").toByteArray()))))
    }
    @Test fun signedLocalReceiverPostsOnceHonorsDisableAndDismissDoesNotAlterPairing() {
        val credentials = KeystoreCredentialStore(context)
        assumeTrue("Preserve personal pairing", credentials.load() == null)
        assumeTrue("Respect system notification block", TaskNotifications.permission(context))
        val store = PushStore.get(context); val original = store.state.value
        val (credential, data) = fixture(); val id = data["eventId"]!!
        try {
            credentials.save(credential); store.update { it.copy(preferences = PushPreferences(enabled = true), seen = emptyMap()) }
            PushReceiver.receive(context, data)
            val manager = context.getSystemService(NotificationManager::class.java)
            val posted = manager.activeNotifications.single { it.id == TaskNotifications.id(id) }
            val channel = manager.getNotificationChannel(posted.notification.channelId)
            assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance); assertNotNull(channel.sound); assertTrue(channel.shouldVibrate())
            assertNull(posted.notification.fullScreenIntent); assertTrue(posted.notification.extras.getString("android.title")!!.contains("Simulated"))
            assertTrue(store.state.value.receipts.any { it.eventId == id && it.stage == "posted" })
            PushReceiver.receive(context, data); assertEquals(1, store.state.value.events.count { it.eventId == id })
            posted.notification.actions.single { it.title.toString() == "Dismiss" }.actionIntent.send()
            repeat(100) { if (manager.activeNotifications.any { it.id == TaskNotifications.id(id) }) Thread.sleep(20) }
            assertFalse(manager.activeNotifications.any { it.id == TaskNotifications.id(id) }); assertEquals(credential.deviceId, credentials.load()?.deviceId)
            store.update { it.copy(preferences = it.preferences.copy(completed = false)) }
            val disabledData = data.toMutableMap().apply { put("eventId", "b".repeat(64)) }
            // A tampered payload and the disabled category both fail closed.
            PushReceiver.receive(context, disabledData); assertFalse(manager.activeNotifications.any { it.id == TaskNotifications.id("b".repeat(64)) })
        } finally {
            TaskNotifications.cancel(context, id); credentials.clear()
            store.update { original.copy(fid = it.fid ?: original.fid) }
        }
    }
    @Test fun taskAlertViewResultsShowsOfflineLimitAndDismisses() {
        val (credential, data) = fixture(); val event = PushParser.parse(data, credential, System.currentTimeMillis())!!
        val state = androidx.compose.runtime.mutableStateOf(NotificationUiState(alert = event))
        var dismissed = false
        compose.setContent { CodexVoiceTheme { TaskAlertScreen(state.value, ClientState(paired = true), { state.value = state.value.copy(viewingDetails = true) }, { dismissed = true }, {}) } }
        compose.onNodeWithText("Task completed").assertIsDisplayed()
        compose.onNodeWithText("Simulated task · Task alert").assertExists()
        compose.onNodeWithText("View results").performClick()
        compose.onNodeWithText("Connect to PC").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Dismiss alert").performScrollTo().performClick(); assertTrue(dismissed)
        compose.activityRule.scenario.recreate()
    }
    @Test fun firebaseClientConfiguredWithoutAnalyticsAndRestrictedCallingPermissions() {
        val app = FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context)
        assumeTrue("Local google-services.json required", app != null)
        assertNotNull(app!!.options.projectId); assertNotNull(app.options.gcmSenderId)
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.USE_FULL_SCREEN_INTENT"))
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.RECORD_AUDIO"))
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.SYSTEM_ALERT_WINDOW"))
    }
}
