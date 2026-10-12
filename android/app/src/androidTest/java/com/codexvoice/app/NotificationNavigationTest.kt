package com.codexvoice.app

import android.app.NotificationManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.codexvoice.app.core.notifications.*
import com.codexvoice.app.core.remote.*
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

/** Real notification PendingIntent and MainActivity; delivery is device-local simulated input. */
class NotificationNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun notificationTapShowsValidatedAlertAndHonestUnpairedDetails() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val credentials = KeystoreCredentialStore(context)
        assumeTrue("Preserve personal pairing", credentials.load() == null)
        assumeTrue("Respect system notification permission", TaskNotifications.permission(context))
        val store = PushStore.get(context); val initial = store.state.value
        val credential = Credential("wss://test.example.ts.net/remote", UUID.randomUUID().toString(), "A".repeat(43), "Local fixture", enabled = false)
        val now = System.currentTimeMillis(); val data = linkedMapOf("version" to "1", "eventId" to "d".repeat(64), "pairingId" to credential.deviceId, "kind" to "completed",
            "occurredAt" to Instant.ofEpochMilli(now).toString(), "expiresAt" to Instant.ofEpochMilli(now + 60000).toString(), "mode" to "mock")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(credential.token.toByteArray()), "HmacSHA256")) }
        val signed = data + ("signature" to Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.values.joinToString("\n").toByteArray())))
        try {
            compose.waitForIdle(); credentials.save(credential); store.update { it.copy(preferences = PushPreferences(enabled = true), seen = emptyMap()) }
            PushReceiver.receive(context, signed)
            val manager = context.getSystemService(NotificationManager::class.java)
            val notification = manager.activeNotifications.single { it.id == TaskNotifications.id(data["eventId"]!!) }
            notification.notification.contentIntent.send()
            compose.waitUntil(10000) { compose.onAllNodesWithText("View results").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Task completed").assertIsDisplayed(); compose.onNodeWithText("View results").performClick()
            compose.onNodeWithText("Pair your PC in Settings to retrieve task details. This alert cannot authorize access.").assertExists()
            compose.onNodeWithText("Dismiss alert").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Your work, within reach.").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(credential.deviceId, credentials.load()?.deviceId)
            assertTrue(store.state.value.receipts.any { it.stage == "opened" }); assertTrue(store.state.value.receipts.any { it.stage == "dismissed" })
        } finally { TaskNotifications.cancel(context, data["eventId"]!!); credentials.clear(); store.update { initial.copy(fid = it.fid ?: initial.fid) } }
    }
}
