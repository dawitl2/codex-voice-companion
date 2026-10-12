package com.codexvoice.app

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.codexvoice.app.core.data.DataStorePreferencesRepository
import com.codexvoice.app.core.notifications.*
import com.codexvoice.app.core.remote.KeystoreCredentialStore
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real FCM transport and private WSS; all PC task events are explicitly simulated. */
class FirebaseDeliveryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
    }
    private fun status() = try { JsonParser.parseString(shell("cat /data/local/tmp/codex-voice-push-status.json")).asJsonObject } catch (_: Exception) { null }
    private fun until(label: String, timeout: Long = 60000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!predicate()) { if (System.currentTimeMillis() > deadline) error("$label timed out; PC diagnostic=${status()?.get("diagnostic")?.asString ?: "unavailable"}; receiver stages=${PushDiagnostics.summary(context)}"); Thread.sleep(100) }
        println("Verified: $label")
    }
    private fun text(value: String) = try { compose.onAllNodesWithText(value, substring = true).fetchSemanticsNodes().isNotEmpty() } catch (_: IllegalStateException) { false }
    @Test fun actualFirebaseBackgroundLockedMobileAndPrivateTaskNavigation() = runBlocking {
        assumeTrue("Run scripts/device-verification.ps1 -Notifications", InstrumentationRegistry.getArguments().getString("privateRoute") == "true")
        val credentials = KeystoreCredentialStore(context); assumeTrue("Preserve personal pairing", credentials.load() == null)
        assumeTrue("Respect Android notification block", TaskNotifications.permission(context))
        val fixture = JsonParser.parseString(shell("cat /data/local/tmp/codex-voice-fixture.json")).asJsonObject
        val prefs = DataStorePreferencesRepository(context.preferencesStore); val initial = prefs.preferences.first()
        val store = PushStore.get(context); val initialPush = store.state.value
        val directory = File(context.noBackupFilesDir, "device-verification").apply { mkdirs() }
        val manager = context.getSystemService(NotificationManager::class.java)
        val screenTimeout = shell("settings get system screen_off_timeout").trim()
        require(screenTimeout.matches(Regex("[0-9]+")))
        fun control(command: String) { File(directory, "control.txt").writeText(command) }
        fun background() { shell("input keyevent KEYCODE_HOME"); until("Foreground WSS closes in background") { status()?.get("connectedDevices")?.asInt == 0 } }
        fun receive(kind: String, command: String): PushEvent {
            val previous = store.state.value.events.map { it.eventId }.toSet(); control(command)
            until("Real FCM $kind callback", 180000) { store.state.value.events.any { it.eventId !in previous && it.kind == kind } }
            val event = store.state.value.events.last { it.eventId !in previous && it.kind == kind }
            until("FCM acceptance recorded separately") { status()?.getAsJsonArray("deliveries")?.any { it.asJsonObject["eventId"].asString == event.eventId && it.asJsonObject["status"].asString == "accepted" } == true }
            until("Android system notification posted") { manager.activeNotifications.any { it.tag == event.eventId } }
            assertEquals("mock", event.mode); assertNull(manager.activeNotifications.single { it.tag == event.eventId }.notification.fullScreenIntent)
            assertTrue(store.state.value.receipts.any { it.eventId == event.eventId && it.stage == "received" })
            assertTrue(store.state.value.receipts.any { it.eventId == event.eventId && it.stage == "posted" })
            return event
        }
        fun wake() { shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard") }
        try {
            wake()
            until("Unlock phone for initial UI checks", 120000) { !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked }
            // Keep background navigation checks unlocked; the explicit locked/Doze check runs last.
            // This changes only the timeout during this fixture and is restored even on failure.
            shell("settings put system screen_off_timeout 300000")
            prefs.setNotifications(initial.copy(notificationsEnabled = false, callOnCompletion = true, callOnFailure = true, callOnApproval = true, alertOnInterruption = true, notificationSound = true, notificationVibration = true))
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            until("MainActivity available") { text("Settings") }
            compose.onNode(hasText("Settings") and hasClickAction()).performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("PC monitor"))
            compose.onNodeWithText("PC monitor").performClick()
            compose.onNodeWithText("Private server address").performScrollTo().performTextInput(fixture["endpoint"].asString)
            compose.onNodeWithText("One-time pairing code").performScrollTo().performTextInput(fixture["code"].asString)
            compose.onNodeWithText("Pair this PC").performScrollTo().performClick()
            until("Private WSS pairing") { text("Connected") }
            compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Enable notifications"))
            compose.onNodeWithContentDescription("Enable notifications").performClick()
            until("Actual Firebase FID registered with authenticated PC") { text("Registered with paired PC") }
            assertTrue(status()!!["configured"].asBoolean)
            val installation = com.google.android.gms.tasks.Tasks.await(com.google.firebase.installations.FirebaseInstallations.getInstance().id, 30, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue("Stored FID must match current SDK installation", installation == store.state.value.fid)
            val fidHash = java.security.MessageDigest.getInstance("SHA-256").digest(installation.toByteArray()).joinToString("") { "%02x".format(it) }
            assertTrue("PC destination must match current SDK installation", status()!!.getAsJsonArray("registration").first().asJsonObject["fidHash"].asString == fidHash)
            receive("completed", "complete"); println("Verified: Real FCM while app active")

            background(); val first = receive("completed", "complete")
            val channel = manager.getNotificationChannel(manager.activeNotifications.single { it.tag == first.eventId }.notification.channelId)
            assertNotNull(channel.sound); assertTrue(channel.shouldVibrate()); assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
            assertEquals(1, store.state.value.events.count { it.eventId == first.eventId })
            assertEquals(1, status()!!.getAsJsonArray("deliveries").count { it.asJsonObject["eventId"].asString == first.eventId })
            println("Verified: Duplicate app-server completion produces one FCM alert")

            wake()
            val alert = manager.activeNotifications.single { it.tag == first.eventId }
            alert.notification.contentIntent.send()
            until("Notification opens task alert") { text("View results") }
            compose.onNodeWithText("View results").performClick()
            until("Authenticated actual simulated result retrieved through WSS") { text("MOCK response:") }
            assertTrue(text("Simulated task"))
            until("Device opened/posted observations synchronized") { status()?.getAsJsonArray("deliveries")?.any { it.asJsonObject["eventId"].asString == first.eventId && it.asJsonObject.getAsJsonObject("receipts").has("opened") } == true }
            compose.onNodeWithText("Dismiss alert").performScrollTo().performClick()
            until("Dashboard navigation restored") { text("Settings") }

            // A disabled category prevents PC sends after an authenticated preference response.
            prefs.setNotifications(prefs.preferences.first().copy(callOnCompletion = false))
            compose.onNode(hasText("Settings") and hasClickAction()).performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Enable notifications"))
            until("Disabled completion preference synchronized") { status()?.getAsJsonArray("registration")?.firstOrNull()?.asJsonObject?.getAsJsonObject("preferences")?.get("completed")?.asBoolean == false }
            val beforeDisabled = store.state.value.events.size; background(); control("complete"); Thread.sleep(5000)
            assertEquals(beforeDisabled, store.state.value.events.size); println("Verified: Disabled completion produces no alert")
            prefs.setNotifications(prefs.preferences.first().copy(callOnCompletion = true))
            wake(); context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            until("Preferences resynchronized") { status()?.getAsJsonArray("registration")?.firstOrNull()?.asJsonObject?.getAsJsonObject("preferences")?.get("completed")?.asBoolean == true }
            background(); val failed = receive("failed", "failure")
            val notification = manager.activeNotifications.single { it.tag == failed.eventId }
            notification.notification.actions.single { it.title.toString() == "Dismiss" }.actionIntent.send()
            until("System dismiss action clears only alert") { manager.activeNotifications.none { it.tag == failed.eventId } }
            assertNotNull(credentials.load())

            if (InstrumentationRegistry.getArguments().getString("mobileData") == "true") {
                shell("svc data enable"); shell("svc wifi disable"); Thread.sleep(10000)
                receive("completed", "complete"); println("Verified: Real FCM over mobile data")
                shell("svc wifi enable"); Thread.sleep(5000)
            }
            // Offline transport queues a fresh event without any background socket.
            val offlineBaseline = store.state.value.events.map { it.eventId }.toSet()
            shell("svc data disable"); shell("svc wifi disable"); Thread.sleep(3000)
            control("complete"); Thread.sleep(5000); assertEquals(offlineBaseline, store.state.value.events.map { it.eventId }.toSet())
            shell("svc wifi enable")
            until("FCM delivery recovers after temporary network loss") { store.state.value.events.any { it.eventId !in offlineBaseline } }
            val offlineEvent = store.state.value.events.last { it.eventId !in offlineBaseline }
            control("offline"); Thread.sleep(1000); wake()
            assertFalse("Secure lock screen blocks notification navigation; unlock is required", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
            manager.activeNotifications.single { it.tag == offlineEvent.eventId }.notification.contentIntent.send()
            until("Offline notification opens generic alert") { text("View results") }
            compose.onNodeWithText("View results").performClick()
            until("Offline PC details are honestly unavailable") { text("Live details unavailable.") }
            compose.onNodeWithText("Dismiss alert").performScrollTo().performClick()
            background(); control("online"); Thread.sleep(1000)
            val beforeRestart = store.state.value.events.size; control("restart"); Thread.sleep(5000)
            assertEquals(beforeRestart, store.state.value.events.size)
            println("Verified: PC restart does not replay old audible alerts")

            shell("input keyevent KEYCODE_SLEEP")
            until("Phone screen locked") { context.getSystemService(KeyguardManager::class.java).isKeyguardLocked }
            shell("dumpsys deviceidle force-idle")
            val idle = shell("dumpsys deviceidle get deep").trim()
            assertEquals("IDLE", idle)
            receive("completed", "complete")
            assertTrue(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap -> File(directory, "locked-notification.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
            println("Verified: Real FCM notification posted while locked in forced IDLE")
        } finally {
            shell("settings put system screen_off_timeout $screenTimeout")
            shell("dumpsys deviceidle unforce"); wake(); prefs.setNotifications(initial)
            for (event in store.state.value.events.filter { it.pairingId == credentials.load()?.deviceId }) TaskNotifications.cancel(context, event.eventId)
            credentials.clear(); store.update { initialPush.copy(fid = it.fid ?: initialPush.fid) }
            instrumentation.runOnMainSync {
                val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                for (stage in listOf(androidx.test.runner.lifecycle.Stage.RESUMED, androidx.test.runner.lifecycle.Stage.PAUSED, androidx.test.runner.lifecycle.Stage.STOPPED, androidx.test.runner.lifecycle.Stage.STARTED)) monitor.getActivitiesInStage(stage).filterIsInstance<MainActivity>().toList().forEach { it.finish() }
            }
        }
    }
}
