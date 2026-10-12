package com.codexvoice.app

import android.app.KeyguardManager
import android.content.Intent
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.codexvoice.app.core.calls.*
import com.codexvoice.app.core.data.DataStorePreferencesRepository
import com.codexvoice.app.core.remote.KeystoreCredentialStore
import com.codexvoice.app.core.speech.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real phone PCM, real local Whisper, installed offline TTS, and an existing real Codex turn. */
class SpeechCallTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun shell(command:String)=instrumentation.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
    private fun status()=try { JsonParser.parseString(shell("cat /data/local/tmp/codex-voice-push-status.json")).asJsonObject } catch(_:Exception) { null }
    private fun until(label:String,timeout:Long=30000,predicate:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+timeout
        while(!predicate()) { check(SystemClock.elapsedRealtime()<deadline) { "$label timed out; audio=${CallRuntime.state.value.phase}; speech=${SpeechRuntime.state.value.phase}; error=${SpeechRuntime.state.value.error}" }; Thread.sleep(75) }
        println("Verified: $label")
    }
    private fun text(value:String)=try { compose.onAllNodesWithText(value,substring=true).fetchSemanticsNodes().isNotEmpty() } catch(_:IllegalStateException) { false }
    @Test fun microphoneTranscriptionRealSummaryRepeatStopAndCleanup()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("privateRoute")=="true")
        val credentials=KeystoreCredentialStore(context); assumeTrue("Preserve personal pairing",credentials.load()==null)
        val runId=InstrumentationRegistry.getArguments().getString("audioRunId") ?: error("Run ID required")
        val fixture=JsonParser.parseString(shell("cat /data/local/tmp/codex-voice-fixture.json")).asJsonObject
        val settings=DataStorePreferencesRepository(context.preferencesStore); val initial=settings.preferences.first()
        val directory=File(context.noBackupFilesDir,"device-verification").apply{mkdirs()}
        val ready=File(directory,"speech-ready.json"); ready.delete()
        var ownedId:String?=null
        fun stage(value:String) { ready.writeText(Gson().toJson(mapOf("runId" to runId,"stage" to value))) }
        fun frames()=status()?.getAsJsonArray("media")?.lastOrNull()?.asJsonObject?.getAsJsonObject("metrics")?.get("uplinkFrames")?.asLong
        try {
            until("Phone unlocked") { !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked }
            settings.setNotifications(initial.copy(notificationsEnabled=false)); settings.setCallInvitations(false)
            settings.setSpeech(initial.copy(spokenSummaries=true,autoSpeakSummary=false,speechRate=1f,speechPitch=1f,speechVoice=""))
            val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            until("Normal MainActivity") { text("Settings") }; compose.onNode(hasText("Settings") and hasClickAction()).performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("PC monitor")); compose.onNodeWithText("PC monitor").performClick()
            compose.onNodeWithText("Private server address").performScrollTo().performTextInput(fixture["endpoint"].asString)
            compose.onNodeWithText("One-time pairing code").performScrollTo().performTextInput(fixture["code"].asString)
            compose.onNodeWithText("Pair this PC").performScrollTo().performClick(); until("Private paired monitor") { text("Connected") }
            ownedId=credentials.load()!!.deviceId
            compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Offer audio calls")); compose.onNodeWithContentDescription("Offer audio calls").performClick()
            until("Call opt-in synchronized") { text("Call invitations synchronized") }
            File(directory,"control.txt").writeText("offer")
            until("Existing stored turn review offered") { status()?.getAsJsonArray("calls")?.lastOrNull()?.asJsonObject?.get("kind")?.asString=="review" }
            compose.onNode(hasText("Home") and hasClickAction()).performClick()
            until("Open current audio session") { text("Open audio session") }; compose.onNodeWithText("Open audio session").performScrollTo().performClick()
            until("Explicit review invitation") { CallRuntime.state.value.phase==LivePhase.OFFERED }
            assertEquals("real",CallRuntime.state.value.offer!!.mode); assertEquals("review",CallRuntime.state.value.offer!!.kind)
            assertFalse(CallRuntime.state.value.capturing); assertFalse(CallRuntime.state.value.serviceRunning)
            compose.onNodeWithContentDescription("Answer real audio call").performScrollTo().performClick()
            until("Real microphone active") { CallRuntime.state.value.phase==LivePhase.ACTIVE && CallRuntime.state.value.capturing }
            compose.onNodeWithContentDescription("Use speaker").performScrollTo().performClick()
            until("Speaker route") { CallRuntime.state.value.speaker }
            until("Offline English voice and actual stored summary") { SpeechRuntime.state.value.ttsStatus=="ready" && SpeechRuntime.state.value.summary.contains("CODEX VOICE BRIDGE CONNECTED") }
            settings.setSpeech(initial.copy(spokenSummaries=true,autoSpeakSummary=true,speechRate=1f,speechPitch=1f,speechVoice=""))
            until("Automatic real summary starts") { SpeechRuntime.state.value.speaking }
            assertFalse("TTS gates native recorder",CallRuntime.state.value.capturing)
            Thread.sleep(600); val pausedFrames=frames(); assertNotNull(pausedFrames); Thread.sleep(1000); assertEquals("No TTS echo uplink",pausedFrames,frames())
            until("Summary finishes and microphone resumes") { !SpeechRuntime.state.value.speaking && SpeechRuntime.state.value.playback=="completed" && CallRuntime.state.value.capturing }
            val spoken=SpeechRuntime.state.value
            assertEquals("No synthesized summary transcript",0,spoken.transcript.size)
            stage("first-phrase")
            until("First real microphone utterance",90000) { SpeechRuntime.state.value.transcript.isNotEmpty() }
            val first=SpeechRuntime.state.value.transcript.last(); assertTrue("Actual transcript visible",text(first.text))
            compose.onNodeWithText("Repeat summary").performScrollTo().performClick()
            until("Repeat starts real synthesis") { SpeechRuntime.state.value.speaking }; assertFalse(CallRuntime.state.value.capturing)
            compose.onNodeWithText("Stop speaking").performScrollTo().performClick()
            until("Stop interrupts synthesis and resumes capture") { !SpeechRuntime.state.value.speaking && SpeechRuntime.state.value.playback=="interrupted" && CallRuntime.state.value.capturing }
            stage("second-phrase")
            until("Second real microphone utterance",90000) { SpeechRuntime.state.value.transcript.lastOrNull()?.sequence?.let { it>first.sequence }==true }
            val second=SpeechRuntime.state.value.transcript.last(); assertTrue("Second transcript visible",text(second.text))
            compose.onNodeWithContentDescription("Mute").performScrollTo().performClick()
            until("Mute stops recorder") { CallRuntime.state.value.muted && !CallRuntime.state.value.capturing }
            Thread.sleep(500); val mutedFrames=frames(); Thread.sleep(1000); assertEquals(mutedFrames,frames())
            compose.onNodeWithContentDescription("Unmute").performScrollTo().performClick(); until("Unmute resumes recorder") { CallRuntime.state.value.capturing }
            compose.onNodeWithContentDescription("Hang up").performScrollTo().performClick()
            until("Hangup releases audio and speech") { !CallRuntime.state.value.serviceRunning && !CallRuntime.state.value.capturing && !CallRuntime.state.value.playing && !SpeechRuntime.state.value.speaking }
            until("PC media closed") { status()?.get("mediaConnected")?.asInt==0 }
            assertTrue(SpeechRuntime.state.value.transcript.isEmpty()); assertTrue(SpeechRuntime.state.value.summary.isEmpty())
            File(directory,"speech-result.json").writeText(Gson().toJson(mapOf("runId" to runId,"mode" to "real","taskSource" to "existing_stored_turn",
                "firstText" to first.text,"secondText" to second.text,"firstProcessingMs" to first.processingMs,"secondProcessingMs" to second.processingMs,
                "ttsEngine" to spoken.ttsEngine,"voice" to spoken.voice,"ttsInitializationMs" to spoken.initializationMs,"ttsSpeechStartMs" to spoken.speechStartMs,
                "halfDuplexVerified" to true,"repeatVerified" to true,"stopVerified" to true,"muteVerified" to true,"cleanupVerified" to true)))
            stage("complete")
        } finally {
            if(CallRuntime.state.value.serviceRunning) { CallRuntime.command(context,CallAudioService.END); until("Finally releases audio",15000) { !CallRuntime.state.value.serviceRunning } }
            settings.setNotifications(initial); settings.setCallInvitations(initial.callInvitations); settings.setSpeech(initial)
            if(credentials.load()?.deviceId==ownedId) credentials.clear()
            CallRuntime.dismiss(); SpeechRuntime.clear()
            instrumentation.runOnMainSync { val monitor=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance(); for(stage in listOf(androidx.test.runner.lifecycle.Stage.RESUMED,androidx.test.runner.lifecycle.Stage.PAUSED,androidx.test.runner.lifecycle.Stage.STOPPED,androidx.test.runner.lifecycle.Stage.STARTED)) monitor.getActivitiesInStage(stage).filterIsInstance<MainActivity>().toList().forEach{it.finish()} }
        }
    }
}
