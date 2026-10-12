package com.codexvoice.app

import android.app.KeyguardManager
import android.content.Intent
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.codexvoice.app.core.calls.*
import com.codexvoice.app.core.conversation.ConversationRuntime
import com.codexvoice.app.core.data.DataStorePreferencesRepository
import com.codexvoice.app.core.remote.*
import com.codexvoice.app.core.speech.SpeechRuntime
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** One native background/lock/network/ASR fixture. Codex task activity is simulated. */
class ReliabilityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
    }
    private fun metrics() = try { JsonParser.parseString(shell("cat /data/local/tmp/codex-voice-push-status.json")).asJsonObject } catch (_: Exception) { null }
    private fun frames() = metrics()?.getAsJsonArray("media")?.lastOrNull()?.asJsonObject?.getAsJsonObject("metrics")?.get("uplinkFrames")?.asLong ?: 0
    private fun until(label: String, timeout: Long = 30000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) { check(SystemClock.elapsedRealtime() < end) { "$label timed out; call=${CallRuntime.state.value.phase}; speech=${SpeechRuntime.state.value.phase}; error=${SpeechRuntime.state.value.error}" }; Thread.sleep(100) }
        println("Verified: $label")
    }
    private fun text(value: String) = try { compose.onAllNodesWithText(value, substring = true).fetchSemanticsNodes().isNotEmpty() } catch (_: IllegalStateException) { false }
    private fun click(value: String) = compose.onNodeWithText(value).performScrollTo().performClick()
    @Test fun consentedCallSurvivesBackgroundLockAndFreshSpeechThenNetworkEndsOrRecoversSafely() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("privateRoute") == "true")
        val runId = checkNotNull(InstrumentationRegistry.getArguments().getString("audioRunId"))
        val testPhase = InstrumentationRegistry.getArguments().getString("reliabilityPhase") ?: "all"
        val full = testPhase == "all"
        val voice = testPhase != "network"
        val credentials = KeystoreCredentialStore(context)
        assumeTrue("Preserve personal pairing", credentials.load() == null)
        val fixture = JsonParser.parseString(shell("cat /data/local/tmp/codex-voice-fixture.json")).asJsonObject
        val settings = DataStorePreferencesRepository(context.preferencesStore); val initial = settings.preferences.first()
        val directory = File(context.noBackupFilesDir, "device-verification").apply { mkdirs() }
        File(directory, "reliability-result.json").delete()
        var ownedId: String? = null
        fun stage(value: String) { File(directory, "reliability-ready.json").writeText(Gson().toJson(mapOf("runId" to runId, "stage" to value))) }
        fun command(value: String) { File(directory, "control.txt").writeText(value) }
        fun liveDiagnostics() { File(directory, "reliability-live.json").writeText(Gson().toJson(mapOf("runId" to runId, "phase" to CallRuntime.state.value.phase.name,
            "capturing" to CallRuntime.state.value.capturing, "speaking" to SpeechRuntime.state.value.speaking, "speechState" to SpeechRuntime.state.value.phase,
            "speechError" to SpeechRuntime.state.value.error, "transcriptCount" to SpeechRuntime.state.value.transcript.size, "pcFrames" to frames()))) }
        try {
            until("Unlocked phone") { !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked }
            settings.setNotifications(initial.copy(notificationsEnabled = false)); settings.setCallInvitations(false)
            settings.setSpeech(initial.copy(spokenSummaries = true, autoSpeakSummary = false, speechVoice = "", speechRate = 1f, speechPitch = 1f))
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            until("Normal app") { text("Settings") }; compose.onNode(hasText("Settings") and hasClickAction()).performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("PC monitor")); click("PC monitor")
            compose.onNodeWithText("Private server address").performScrollTo().performTextInput(fixture["endpoint"].asString)
            compose.onNodeWithText("One-time pairing code").performScrollTo().performTextInput(fixture["code"].asString)
            click("Pair this PC"); until("Private authenticated monitor") { text("Connected") }; ownedId = credentials.load()!!.deviceId
            compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Offer audio calls")); compose.onNodeWithContentDescription("Offer audio calls").performClick()
            until("Call invitation opt-in synchronized") { text("Call invitations synchronized") }
            compose.onNode(hasText("Home") and hasClickAction()).performClick()
            command("waiting")
            if (voice) {
            click("Task controls"); until("Mock task running") { text("Interrupt task") }
            val model = androidx.lifecycle.ViewModelProvider(activity as MainActivity)[com.codexvoice.app.feature.dashboard.DashboardViewModel::class.java]
            until("Same authoritative task on phone and PC") { compose.waitForIdle(); model.controlState.value.task?.status == "running" && model.controlState.value.task?.id == metrics()?.getAsJsonArray("controls")?.firstOrNull()?.asJsonObject?.getAsJsonObject("task")?.get("id")?.asString }
            click("Interrupt task"); until("Explicit interrupt preview") { text("Confirm interruption") }; click("Confirm interruption")
            until("Authoritative mock interrupt") { text("Interruption · interrupted") }; click("Done")
            } else {
                until("Mock task prepared") { metrics()?.getAsJsonArray("controls")?.firstOrNull()?.asJsonObject?.getAsJsonObject("task")?.get("status")?.asString == "running" }
                command("interrupt"); until("Mock preparation ended") { metrics()?.getAsJsonArray("controls")?.firstOrNull()?.asJsonObject?.getAsJsonObject("task")?.get("status")?.asString == "interrupted" }
            }
            command("offer"); until("Mock review invitation available") { metrics()?.getAsJsonArray("calls")?.lastOrNull()?.asJsonObject?.get("state")?.asString == "offered" }
            click("Open audio session"); until("Explicit offered call") { CallRuntime.state.value.phase == LivePhase.OFFERED }
            assertFalse(CallRuntime.state.value.capturing)
            compose.onNodeWithContentDescription("Answer real audio call").performScrollTo().performClick()
            until("Native audio active") { CallRuntime.state.value.phase == LivePhase.ACTIVE && CallRuntime.state.value.capturing && ConversationRuntime.state.value.enabled }
            val callId = CallRuntime.state.value.offer!!.callId
            compose.onNodeWithContentDescription("Use speaker").performScrollTo().performClick(); until("Built-in speaker") { CallRuntime.state.value.speaker }
            until("PC receives microphone") { frames() >= 20 }
            if (full) {
            shell("input keyevent KEYCODE_HOME"); val background = frames(); Thread.sleep(3500)
            assertTrue("Background native capture advances", frames() > background + 50); assertEquals(callId, CallRuntime.state.value.offer?.callId)
            shell("input keyevent KEYCODE_SLEEP"); val locked = frames(); Thread.sleep(3500)
            assertTrue("Screen locked", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
            assertTrue("Locked capture advances", frames() > locked + 50)
            stage("unlock-phone"); shell("input keyevent KEYCODE_WAKEUP")
            until("User unlocks without supplied credential", 180000) { !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked }
            shell("am start -n com.codexvoice.app/.MainActivity")
            until("Return to original call") { text("Hang up") || CallRuntime.state.value.capturing }
            instrumentation.runOnMainSync { activity.recreate() }
            until("Activity recreated without new call") { compose.waitForIdle(); text("Repeat response") }
            instrumentation.runOnMainSync {
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<MainActivity>().forEach { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            assertEquals(callId, CallRuntime.state.value.offer?.callId); assertEquals(1, metrics()!!["mediaConnected"].asInt)
            }
            until("Offline voice ready and summary available") { SpeechRuntime.state.value.ttsStatus == "ready" && SpeechRuntime.state.value.summary.isNotBlank() }
            click("Repeat response"); until("Native TTS starts") { SpeechRuntime.state.value.speaking }; assertFalse(CallRuntime.state.value.capturing)
            until("TTS finishes and native listening resumes") { !SpeechRuntime.state.value.speaking && CallRuntime.state.value.capturing && SpeechRuntime.state.value.phase == "listening" }
            var processing: Long? = null
            if (voice) {
            val priorSequence = SpeechRuntime.state.value.transcript.lastOrNull()?.sequence ?: 0
            stage("speak-request")
            until("Fresh real ASR after task controls and recreation", 90000) {
                compose.waitForIdle(); liveDiagnostics()
                SpeechRuntime.state.value.transcript.lastOrNull()?.sequence?.let { it > priorSequence } == true && ConversationRuntime.state.value.proposal != null
            }
            processing = SpeechRuntime.state.value.transcript.last().processingMs
            val selected = ConversationRuntime.state.value.selection?.threadId
            assertNotNull(selected); assertEquals(selected, ConversationRuntime.state.value.proposal!!.threadId)
            until("Spoken proposal completes") { compose.waitForIdle(); !SpeechRuntime.state.value.speaking && CallRuntime.state.value.capturing }
            // Isolated mock submission: the actual recognized proposal and explicit UI confirmation.
            click("Send instruction")
            until("One mock submission and final offline reply") { compose.waitForIdle(); metrics()?.get("taskTurnStarts")?.asInt == 2 && ConversationRuntime.state.value.entries.any { it.kind == "codex" } && !SpeechRuntime.state.value.speaking && ConversationRuntime.state.value.lastSpokenSequence >= (ConversationRuntime.state.value.entries.lastOrNull()?.sequence ?: Long.MAX_VALUE) }
            }
            stage("network-mobile"); command("network-mobile"); Thread.sleep(10000); liveDiagnostics()
            command("network-wifi"); Thread.sleep(10000)
            until("Network recovery or bounded clean failure", 20000) { CallRuntime.state.value.phase == LivePhase.ACTIVE || (!CallRuntime.state.value.serviceRunning && !CallRuntime.state.value.capturing) }
            val recovered = CallRuntime.state.value.phase == LivePhase.ACTIVE
            if (recovered) { assertEquals(callId, CallRuntime.state.value.offer?.callId); val resumed = frames(); Thread.sleep(2000); assertTrue(frames() > resumed + 20); CallRuntime.command(context, CallAudioService.END) }
            until("Call notification End releases native resources") { !CallRuntime.state.value.serviceRunning && !CallRuntime.state.value.capturing && !CallRuntime.state.value.playing && !SpeechRuntime.state.value.speaking && metrics()?.get("mediaConnected")?.asInt == 0 }
            assertTrue(SpeechRuntime.state.value.transcript.isEmpty()); assertTrue(ConversationRuntime.state.value.entries.isEmpty())
            assertEquals("No duplicate inference", if (voice) 2 else 1, metrics()!!["taskTurnStarts"].asInt)
            File(directory, "reliability-result.json").writeText(Gson().toJson(mapOf("runId" to runId, "taskMode" to "mock", "background" to full, "locked" to full,
                "recreation" to full, "postControlAsr" to voice, "mockConfirmedVoice" to voice, "tts" to true, "asrProcessingMs" to processing,
                "networkRecovered" to recovered, "networkCleanFailure" to !recovered, "cleanup" to true, "realInferenceTurns" to 0)))
            stage("complete")
        } finally {
            command("network-restore")
            if (CallRuntime.state.value.serviceRunning) { CallRuntime.command(context, CallAudioService.END); until("Finally releases audio", 15000) { !CallRuntime.state.value.serviceRunning } }
            settings.setNotifications(initial); settings.setCallInvitations(initial.callInvitations); settings.setSpeech(initial)
            if (credentials.load()?.deviceId == ownedId) credentials.clear()
            CallRuntime.dismiss(); SpeechRuntime.clear(); ConversationRuntime.clear()
            instrumentation.runOnMainSync { val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance();
                for (s in listOf(androidx.test.runner.lifecycle.Stage.RESUMED, androidx.test.runner.lifecycle.Stage.PAUSED, androidx.test.runner.lifecycle.Stage.STOPPED, androidx.test.runner.lifecycle.Stage.STARTED)) monitor.getActivitiesInStage(s).filterIsInstance<MainActivity>().toList().forEach { it.finish() } }
        }
    }
}
