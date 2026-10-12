package com.codexvoice.app

import android.app.KeyguardManager
import android.content.Intent
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.codexvoice.app.core.calls.*
import com.codexvoice.app.core.conversation.*
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

/** One human-confirmed harmless Plus turn, using real ASR/private media and offline TTS. */
class ConversationCallTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun shell(command:String)=instrumentation.uiAutomation.executeShellCommand(command).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()}
    private fun status()=try{JsonParser.parseString(shell("cat /data/local/tmp/codex-voice-push-status.json")).asJsonObject}catch(_:Exception){null}
    private fun until(label:String,timeout:Long=40000,predicate:()->Boolean){val deadline=SystemClock.elapsedRealtime()+timeout;while(!predicate()){check(SystemClock.elapsedRealtime()<deadline){"$label timed out; call=${CallRuntime.state.value.phase}; conversation=${ConversationRuntime.state.value.phase}; code=${ConversationRuntime.state.value.error}; tts=${SpeechRuntime.state.value.ttsStatus}"};Thread.sleep(75)};println("Verified: $label")}
    private fun text(value:String)=try{compose.onAllNodesWithText(value,substring=true).fetchSemanticsNodes().isNotEmpty()}catch(_:IllegalStateException){false}
    @Test fun cleanupInterruptedFixture()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cleanupOwned")=="true")
        val proof=JsonParser.parseString(shell("cat /data/local/tmp/codex-voice-conversation-cleanup.json")).asJsonObject
        val ids=proof["deviceIds"].asJsonArray.map{it.asString}.also{values->require(values.size<=1 && values.all{it.matches(Regex("[a-f0-9-]{36}"))})}
        val credentials=KeystoreCredentialStore(context)
        if(credentials.load()?.deviceId in ids)credentials.clear()
        val snapshot=File(context.noBackupFilesDir,"device-verification/conversation-preferences.json")
        if(snapshot.exists()) {
            val original=Gson().fromJson(snapshot.readText(),com.codexvoice.app.core.model.UserPreferences::class.java)
            val settings=DataStorePreferencesRepository(context.preferencesStore)
            settings.setNotifications(original);settings.setCallInvitations(original.callInvitations);settings.setSpeech(original);snapshot.delete()
        }
        CallRuntime.dismiss();SpeechRuntime.clear();ConversationRuntime.clear()
    }
    @Test fun realSpeechConfirmationCodexReplyAndCleanup()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("privateRoute")=="true")
        val credentials=KeystoreCredentialStore(context);assumeTrue("Preserve personal pairing",credentials.load()==null)
        val runId=InstrumentationRegistry.getArguments().getString("audioRunId")?:error("Run ID required")
        val fixture=JsonParser.parseString(shell("cat /data/local/tmp/codex-voice-fixture.json")).asJsonObject
        val offset=fixture["phoneMinusPcMs"].asLong
        val settings=DataStorePreferencesRepository(context.preferencesStore);val initial=settings.preferences.first()
        val directory=File(context.noBackupFilesDir,"device-verification").apply{mkdirs()};val ready=File(directory,"conversation-ready.json");ready.delete()
        val preferencesSnapshot=File(directory,"conversation-preferences.json");preferencesSnapshot.writeText(Gson().toJson(initial))
        var ownedId:String?=null
        fun stage(value:String){ready.writeText(Gson().toJson(mapOf("runId" to runId,"stage" to value)))}
        try{
            until("Unlocked phone"){!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked}
            settings.setNotifications(initial.copy(notificationsEnabled=false));settings.setCallInvitations(false)
            settings.setSpeech(initial.copy(spokenSummaries=true,autoSpeakSummary=false,speechVoice="",speechRate=1f,speechPitch=1f))
            val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.runOnMainSync{activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            until("Normal application"){text("Settings")};compose.onNode(hasText("Settings") and hasClickAction()).performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("PC monitor"));compose.onNodeWithText("PC monitor").performClick()
            compose.onNodeWithText("Private server address").performScrollTo().performTextInput(fixture["endpoint"].asString)
            compose.onNodeWithText("One-time pairing code").performScrollTo().performTextInput(fixture["code"].asString)
            compose.onNodeWithText("Pair this PC").performScrollTo().performClick();until("Authenticated private monitor"){text("Connected")};ownedId=credentials.load()!!.deviceId
            compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Offer audio calls"));compose.onNodeWithContentDescription("Offer audio calls").performClick()
            until("Call invitations synchronized"){text("Call invitations synchronized")}
            compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Enable notifications"));compose.onNodeWithContentDescription("Enable notifications").performClick()
            until("Existing Firebase installation registered"){text("Registered with paired PC")}
            File(directory,"control.txt").writeText("offer");until("Actual stored-task review offered"){status()?.getAsJsonArray("calls")?.lastOrNull()?.asJsonObject?.let{it["kind"].asString=="review" && it["state"].asString=="offered"}==true}
            compose.onNode(hasText("Home") and hasClickAction()).performClick();compose.onNodeWithText("Open audio session").performScrollTo().performClick()
            until("Explicit real invitation"){CallRuntime.state.value.phase==LivePhase.OFFERED};assertEquals("real",CallRuntime.state.value.offer!!.mode)
            compose.onNodeWithContentDescription("Answer real audio call").performScrollTo().performClick()
            until("Native real microphone and conversation"){CallRuntime.state.value.phase==LivePhase.ACTIVE && CallRuntime.state.value.capturing && ConversationRuntime.state.value.enabled}
            compose.onNodeWithContentDescription("Mute").performScrollTo().performClick();until("Mute during test preparation"){CallRuntime.state.value.muted && !CallRuntime.state.value.capturing}
            compose.onNodeWithContentDescription("Use speaker").performScrollTo().performClick();until("Speaker route"){CallRuntime.state.value.speaker}
            until("Offline voice and real stored summary"){SpeechRuntime.state.value.ttsStatus=="ready" && SpeechRuntime.state.value.summary.isNotEmpty()}
            settings.setSpeech(initial.copy(spokenSummaries=true,autoSpeakSummary=true,speechVoice="",speechRate=1f,speechPitch=1f))
            until("Stored summary starts"){SpeechRuntime.state.value.speaking};assertFalse(CallRuntime.state.value.capturing)
            until("Summary completed with preparatory mute preserved"){!SpeechRuntime.state.value.speaking && CallRuntime.state.value.muted}
            compose.onNodeWithContentDescription("Unmute").performScrollTo().performClick();until("Listening after summary"){CallRuntime.state.value.capturing}
            assertEquals(0,status()!!["voiceTurnStarts"].asInt);stage("request")
            until("Real spoken instruction proposal",120000){
                val candidate=ConversationRuntime.state.value.proposal
                if(candidate!=null && !(candidate.text.contains("reply",true) && candidate.text.contains("hello",true))) {
                    compose.onNodeWithText("Cancel instruction").performScrollTo().performClick();Thread.sleep(250)
                }
                ConversationRuntime.state.value.proposal?.let{it.text.contains("reply",true) && it.text.contains("hello",true)}==true
            }
            val proposal=ConversationRuntime.state.value.proposal!!
            assertTrue("Controlled harmless request",proposal.text.contains("reply",true) && proposal.text.contains("hello",true))
            assertEquals(0,status()!!["voiceTurnStarts"].asInt)
            until("Request transcript appears"){SpeechRuntime.state.value.transcript.any{it.text==proposal.text} && text(proposal.text)}
            val request=SpeechRuntime.state.value.transcript.last{it.text==proposal.text};val screenAt=System.currentTimeMillis()
            val proposalEntry=ConversationRuntime.state.value.entries.last{it.text.startsWith("Proposed for ")}
            until("Spoken confirmation proposal starts"){SpeechRuntime.state.value.speaking}
            until("Listening for verbal confirmation"){!SpeechRuntime.state.value.speaking && CallRuntime.state.value.capturing}
            stage("confirm")
            until("Real confirmed Codex submission",120000){ConversationRuntime.state.value.submission?.turnId!=null}
            val confirmation=SpeechRuntime.state.value.transcript.lastOrNull{it.sequence>request.sequence && it.text.lowercase().contains("send")}
            until("Actual Codex final response",90000){ConversationRuntime.state.value.submission?.state=="completed" && ConversationRuntime.state.value.entries.any{it.kind=="codex" && it.text.contains("hello",true)}}
            val submission=ConversationRuntime.state.value.submission!!;val response=ConversationRuntime.state.value.entries.last{it.kind=="codex"}
            assertEquals(ConversationRuntime.state.value.selection!!.threadId,submission.threadId);assertNotNull(submission.turnId);assertTrue(text(response.text))
            until("Actual response TTS starts"){SpeechRuntime.state.value.speaking && SpeechRuntime.state.value.outputText==response.spoken}
            val responseStartedAt=SpeechRuntime.state.value.speechStartedAtMs
            assertFalse("Half-duplex final reply",CallRuntime.state.value.capturing)
            until("Actual response TTS completes"){!SpeechRuntime.state.value.speaking && CallRuntime.state.value.capturing && ConversationRuntime.state.value.lastSpokenSequence>=response.sequence}
            assertEquals(1,status()!!["voiceTurnStarts"].asInt);assertEquals(1,status()!!["voiceSubmissionCount"].asInt)
            val initialTranscriptCount=SpeechRuntime.state.value.transcript.size;Thread.sleep(750);assertEquals("No synthesized reply became an instruction",initialTranscriptCount,SpeechRuntime.state.value.transcript.size)
            stage("reply-played")
            compose.onNodeWithContentDescription("Hang up").performScrollTo().performClick()
            until("Call microphone playback TTS and service released"){!CallRuntime.state.value.serviceRunning && !CallRuntime.state.value.capturing && !CallRuntime.state.value.playing && !SpeechRuntime.state.value.speaking}
            until("PC media socket closed"){status()?.get("mediaConnected")?.asInt==0}
            assertNull(ConversationRuntime.state.value.proposal);assertTrue(ConversationRuntime.state.value.entries.isEmpty());assertTrue(SpeechRuntime.state.value.transcript.isEmpty())
            File(directory,"conversation-result.json").writeText(Gson().toJson(mapOf("runId" to runId,"request" to request.text,"confirmation" to confirmation?.text,"threadId" to submission.threadId,"turnId" to submission.turnId,"reply" to response.text,
                "requestProcessingMs" to request.processingMs,"detectedSpeechEndToProposalMs" to (proposalEntry.timestamp-request.endedAtMs),"detectedSpeechEndToScreenApproxMs" to (screenAt-offset-request.endedAtMs),"clockUncertaintyMs" to fixture["clockUncertaintyMs"].asLong,
                "submittedAt" to submission.submittedAt,"acceptedAt" to submission.acceptedAt,"firstOutputAt" to submission.firstOutputAt,"finishedAt" to submission.finishedAt,"replyTtsStartedAtApproxPcMs" to (responseStartedAt-offset),
                "ttsEngine" to SpeechRuntime.state.value.ttsEngine,"oneTurnVerified" to true,"halfDuplexVerified" to true,"cleanupVerified" to true)))
            stage("complete")
        }finally{
            if(CallRuntime.state.value.serviceRunning){CallRuntime.command(context,CallAudioService.END);until("Finally releases owned audio",15000){!CallRuntime.state.value.serviceRunning}}
            settings.setNotifications(initial);settings.setCallInvitations(initial.callInvitations);settings.setSpeech(initial)
            preferencesSnapshot.delete()
            if(credentials.load()?.deviceId==ownedId)credentials.clear();CallRuntime.dismiss();SpeechRuntime.clear();ConversationRuntime.clear()
            instrumentation.runOnMainSync{val monitor=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance();for(stage in listOf(androidx.test.runner.lifecycle.Stage.RESUMED,androidx.test.runner.lifecycle.Stage.PAUSED,androidx.test.runner.lifecycle.Stage.STOPPED,androidx.test.runner.lifecycle.Stage.STARTED))monitor.getActivitiesInStage(stage).filterIsInstance<MainActivity>().toList().forEach{it.finish()}}
        }
    }
}
