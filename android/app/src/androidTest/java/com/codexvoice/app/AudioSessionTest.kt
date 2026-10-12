package com.codexvoice.app

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.codexvoice.app.core.calls.*
import com.codexvoice.app.core.data.DataStorePreferencesRepository
import com.codexvoice.app.core.notifications.*
import com.codexvoice.app.core.remote.KeystoreCredentialStore
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Physical AudioRecord/AudioTrack and private WSS. Task events are simulated; FCM is real. */
class AudioSessionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
    private fun status() = try { JsonParser.parseString(shell("cat /data/local/tmp/codex-voice-push-status.json")).asJsonObject } catch (_: Exception) { null }
    private fun until(label: String, timeout: Long = 60000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) { check(SystemClock.elapsedRealtime() < deadline) { "$label timed out; audioPhase=${CallRuntime.state.value.phase}; diagnostic=${CallRuntime.state.value.problem}; stages=${CallDiagnostics.summary(context)}" }; Thread.sleep(100) }
        println("Verified: $label")
    }
    private fun text(value: String) = try { compose.onAllNodesWithText(value, substring = true).fetchSemanticsNodes().isNotEmpty() } catch (_: IllegalStateException) { false }
    private fun allowButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.packageName?.toString()?.contains("permissioncontroller") == true &&
            (node.viewIdResourceName?.endsWith(":id/permission_allow_foreground_only_button") == true || node.text?.toString() == "While using the app")) return node
        for (index in 0 until node.childCount) allowButton(node.getChild(index))?.let { return it }
        return null
    }
    @Test fun actualNotificationAnswerMicrophonePcToneMuteBackgroundAndCleanup() = runBlocking {
        assumeTrue("Run the explicit private audio fixture", InstrumentationRegistry.getArguments().getString("privateRoute") == "true")
        val credentials = KeystoreCredentialStore(context); assumeTrue("Preserve personal pairing", credentials.load() == null)
        assumeTrue("Respect Android notification block", TaskNotifications.permission(context))
        val runId = InstrumentationRegistry.getArguments().getString("audioRunId") ?: error("audioRunId required")
        val fixture = JsonParser.parseString(shell("cat /data/local/tmp/codex-voice-fixture.json")).asJsonObject
        val settings = DataStorePreferencesRepository(context.preferencesStore); val initial = settings.preferences.first()
        val store = PushStore.get(context); val initialPush = store.state.value
        val directory = File(context.noBackupFilesDir, "device-verification").apply { mkdirs() }
        val continueFile = File(directory, "audio-continue"); continueFile.delete()
        val manager = context.getSystemService(NotificationManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java); val initialMode = audio.mode
        var ownedId: String? = null; var microphoneInitiallyDenied = false; var wifiFrames = 0L; var mobileFrames = 0L
        fun control(command: String) { File(directory, "control.txt").writeText(command) }
        fun wake() { shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard") }
        fun launch() = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun newAlert(): PushEvent {
            val before = store.state.value.events.map { it.eventId }.toSet(); control("complete")
            until("Actual FCM task notification", if (InstrumentationRegistry.getArguments().getString("quickAudio") == "true") 90000 else 180000) { store.state.value.events.any { it.eventId !in before } }
            val event = store.state.value.events.last { it.eventId !in before }; assertEquals("mock", event.mode)
            until("Actual system notification") { manager.activeNotifications.any { it.tag == event.eventId } }
            manager.activeNotifications.single { it.tag == event.eventId }.notification.contentIntent.send()
            until("Task notification opens offered real audio session") { CallRuntime.state.value.phase == LivePhase.OFFERED && text("Incoming audio session") }
            assertFalse(CallRuntime.state.value.capturing); assertFalse(CallRuntime.state.value.serviceRunning)
            return event
        }
        fun answer() {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            compose.onNodeWithContentDescription("Answer real audio call").performClick()
            if (!granted) {
                until("Microphone permission requested only after Answer") { allowButton(instrumentation.uiAutomation.rootInActiveWindow) != null }
                assertFalse(CallRuntime.state.value.capturing)
                assertTrue(allowButton(instrumentation.uiAutomation.rootInActiveWindow)!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
            until("Authenticated native microphone session active") { CallRuntime.state.value.phase == LivePhase.ACTIVE && CallRuntime.state.value.capturing }
            assertEquals(16000, CallRuntime.state.value.captureSampleRate); assertEquals(16000, CallRuntime.state.value.playbackSampleRate)
            until("Actual AudioRecord frames received on Windows") { CallRuntime.state.value.pcReceivedFrames >= 50 }
        }
        fun endFromNotification() {
            val notification = manager.activeNotifications.single { it.notification.channelId == "codex_audio_session" }
            notification.notification.actions.single { it.title.toString() == "End call" }.actionIntent.send()
            until("Ongoing End call releases microphone playback and service") { !CallRuntime.state.value.serviceRunning && !CallRuntime.state.value.capturing && !CallRuntime.state.value.playing }
            until("PC media socket released") { status()?.get("mediaConnected")?.asInt == 0 }
            assertEquals(initialMode, audio.mode)
        }
        try {
            wake(); until("Phone unlocked for call UI", 120000) { !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked }
            settings.setNotifications(initial.copy(notificationsEnabled = false, callOnCompletion = true)); settings.setCallInvitations(false)
            val activity = launch(); instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            until("Normal MainActivity") { text("Settings") }; compose.onNode(hasText("Settings") and hasClickAction()).performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("PC monitor")); compose.onNodeWithText("PC monitor").performClick()
            compose.onNodeWithText("Private server address").performScrollTo().performTextInput(fixture["endpoint"].asString)
            compose.onNodeWithText("One-time pairing code").performScrollTo().performTextInput(fixture["code"].asString)
            compose.onNodeWithText("Pair this PC").performScrollTo().performClick(); until("Private paired monitor") { text("Connected") }
            ownedId = credentials.load()!!.deviceId
            compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Offer audio calls")); compose.onNodeWithContentDescription("Offer audio calls").performClick()
            until("Independent call invitation setting synchronized") { text("Call invitations synchronized") }
            compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Enable notifications")); compose.onNodeWithContentDescription("Enable notifications").performClick()
            until("Firebase FID registered") { text("Registered with paired PC") }
            shell("input keyevent KEYCODE_HOME"); until("Monitoring socket closes in background") { status()?.get("connectedDevices")?.asInt == 0 }
            newAlert(); microphoneInitiallyDenied = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            answer()
            File(directory, "audio-ready.json").writeText(Gson().toJson(mapOf("runId" to runId, "stage" to "audio-active", "sampleRate" to CallRuntime.state.value.captureSampleRate)))
            if (InstrumentationRegistry.getArguments().getString("quickAudio") != "true") until("Human ready for short audio diagnostic", 600000) { continueFile.exists() }
            continueFile.delete()
            compose.onNodeWithContentDescription("Use speaker").performScrollTo().performClick(); until("Actual speaker route selected") { CallRuntime.state.value.speaker }
            compose.onNodeWithContentDescription("Play quiet PC test tone").performScrollTo().performClick()
            until("PC-generated PCM reaches AudioTrack and advances playback head") { CallRuntime.state.value.downlinkFrames >= 20 && CallRuntime.state.value.renderedSamples >= 3200 }
            File(directory, "audio-ready.json").writeText(Gson().toJson(mapOf("runId" to runId, "stage" to "tone-played", "downlinkFrames" to CallRuntime.state.value.downlinkFrames)))
            compose.onNodeWithContentDescription("Mute").performScrollTo().performClick(); until("Mute actually stops AudioRecord") { CallRuntime.state.value.muted && !CallRuntime.state.value.capturing }
            Thread.sleep(500); val mutedCount = status()!!.getAsJsonArray("media").last().asJsonObject.getAsJsonObject("metrics")["uplinkFrames"].asLong
            Thread.sleep(1500); assertEquals(mutedCount, status()!!.getAsJsonArray("media").last().asJsonObject.getAsJsonObject("metrics")["uplinkFrames"].asLong)
            compose.onNodeWithContentDescription("Unmute").performScrollTo().performClick(); until("Unmute resumes actual uplink") { CallRuntime.state.value.capturing && CallRuntime.state.value.pcReceivedFrames > mutedCount + 20 }
            if (InstrumentationRegistry.getArguments().getString("quickAudio") == "true") {
                wifiFrames = CallRuntime.state.value.pcReceivedFrames
                shell("input keyevent KEYCODE_HOME")
                until("Control monitor closes while microphone service continues") { status()?.get("connectedDevices")?.asInt == 0 }
                val before = status()!!.getAsJsonArray("media").last().asJsonObject.getAsJsonObject("metrics")["uplinkFrames"].asLong
                until("Short background audio check", 10000) { status()?.getAsJsonArray("media")?.lastOrNull()?.asJsonObject?.getAsJsonObject("metrics")?.get("uplinkFrames")?.asLong?.let { it >= before + 50 } == true }
                endFromNotification()
                File(directory, "audio-result.json").writeText(Gson().toJson(mapOf("runId" to runId, "scope" to "quick", "sampleRate" to 16000,
                    "wifiPcFrames" to wifiFrames, "mobilePcFrames" to 0, "permissionRequested" to microphoneInitiallyDenied,
                    "muteVerified" to true, "backgroundVerified" to true, "lockedVerified" to false, "cleanupVerified" to true)))
                return@runBlocking
            }
            val originalCall = CallRuntime.state.value.offer!!.callId; val started = CallRuntime.state.value.activeAt
            instrumentation.runOnMainSync { activity.recreate() }; until("Activity recreation preserves the running service call") { CallRuntime.state.value.phase == LivePhase.ACTIVE && CallRuntime.state.value.serviceRunning }
            assertEquals(originalCall, CallRuntime.state.value.offer!!.callId); assertEquals(started, CallRuntime.state.value.activeAt)
            shell("input keyevent KEYCODE_HOME"); until("Control monitor closes but microphone FGS continues") { status()?.get("connectedDevices")?.asInt == 0 }
            val backgroundCount = status()!!.getAsJsonArray("media").last().asJsonObject.getAsJsonObject("metrics")["uplinkFrames"].asLong
            until("Actual background microphone uplink") { status()?.getAsJsonArray("media")?.lastOrNull()?.asJsonObject?.getAsJsonObject("metrics")?.get("uplinkFrames")?.asLong?.let { it >= backgroundCount + 50 } == true }
            wifiFrames = CallRuntime.state.value.pcReceivedFrames; endFromNotification()

            if (InstrumentationRegistry.getArguments().getString("mobileData") == "true") {
                shell("svc data enable"); shell("svc wifi disable"); Thread.sleep(10000)
                wake(); launch(); until("Ended call screen restored") { text("Return to dashboard") }; compose.onNodeWithText("Return to dashboard").performClick()
                until("Private control reconnects on mobile") { status()?.get("connectedDevices")?.asInt == 1 }
                newAlert(); answer(); compose.onNodeWithContentDescription("Use speaker").performScrollTo().performClick(); until("Speaker route on mobile call") { CallRuntime.state.value.speaker }
                compose.onNodeWithContentDescription("Play quiet PC test tone").performScrollTo().performClick(); until("Real PC downlink over mobile data") { CallRuntime.state.value.downlinkFrames >= 20 }
                mobileFrames = CallRuntime.state.value.pcReceivedFrames
            } else { wake(); launch(); until("Ended call screen restored") { text("Return to dashboard") }; compose.onNodeWithText("Return to dashboard").performClick(); newAlert(); answer() }
            shell("input keyevent KEYCODE_HOME"); shell("input keyevent KEYCODE_SLEEP")
            until("Phone locked with user-started audio service") { context.getSystemService(KeyguardManager::class.java).isKeyguardLocked }
            val lockedCount = status()!!.getAsJsonArray("media").last().asJsonObject.getAsJsonObject("metrics")["uplinkFrames"].asLong
            until("Microphone continues while locked after explicit Answer") { status()?.getAsJsonArray("media")?.lastOrNull()?.asJsonObject?.getAsJsonObject("metrics")?.get("uplinkFrames")?.asLong?.let { it >= lockedCount + 50 } == true }
            endFromNotification()
            assertNotNull(credentials.load())
            File(directory, "audio-result.json").writeText(Gson().toJson(mapOf("runId" to runId, "sampleRate" to 16000, "wifiPcFrames" to wifiFrames, "mobilePcFrames" to mobileFrames,
                "permissionRequested" to microphoneInitiallyDenied, "muteVerified" to true, "backgroundVerified" to true, "lockedVerified" to true, "cleanupVerified" to true)))
        } finally {
            if (CallRuntime.state.value.serviceRunning) { CallRuntime.command(context, CallAudioService.END); until("Finally releases owned audio", 15000) { !CallRuntime.state.value.serviceRunning } }
            settings.setNotifications(initial); settings.setCallInvitations(initial.callInvitations)
            for (event in store.state.value.events.filter { it.pairingId == ownedId }) TaskNotifications.cancel(context, event.eventId)
            if (credentials.load()?.deviceId == ownedId) credentials.clear()
            store.update { initialPush.copy(fid = it.fid ?: initialPush.fid) }; CallRuntime.dismiss(); continueFile.delete(); wake()
            instrumentation.runOnMainSync { val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance(); for (stage in listOf(androidx.test.runner.lifecycle.Stage.RESUMED, androidx.test.runner.lifecycle.Stage.PAUSED, androidx.test.runner.lifecycle.Stage.STOPPED, androidx.test.runner.lifecycle.Stage.STARTED)) monitor.getActivitiesInStage(stage).filterIsInstance<MainActivity>().toList().forEach { it.finish() } }
        }
    }
}
