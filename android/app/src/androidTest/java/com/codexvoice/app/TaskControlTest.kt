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
import com.codexvoice.app.core.control.SecureApproval
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real private route/native authentication and speech; all Codex task events are simulated. */
class TaskControlTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun shell(command:String)=instrumentation.uiAutomation.executeShellCommand(command).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()}
    private fun metrics()=try{JsonParser.parseString(shell("cat /data/local/tmp/codex-voice-push-status.json")).asJsonObject}catch(_:Exception){null}
    private fun control():JsonObject?=try{metrics()?.getAsJsonArray("controls")?.firstOrNull()?.asJsonObject}catch(_:Exception){null}
    private fun approvalStatus():String?=control()?.getAsJsonArray("approvals")?.lastOrNull()?.asJsonObject?.get("status")?.asString
    private fun taskStatus():String?=control()?.getAsJsonObject("task")?.get("status")?.asString
    private fun until(label:String,timeout:Long=35000,predicate:()->Boolean){val end=SystemClock.elapsedRealtime()+timeout;while(!predicate()){check(SystemClock.elapsedRealtime()<end){"$label timed out"};Thread.sleep(100)};println("Verified: $label")}
    private fun text(value:String)=try{compose.onAllNodesWithText(value,substring=true).fetchSemanticsNodes().isNotEmpty()}catch(_:IllegalStateException){false}
    private fun click(value:String){compose.onNodeWithText(value).performScrollTo().performClick()}
    @Test fun confirmedControlsSecureApprovalQueueAndVoiceRemainSeparate()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("privateRoute")=="true")
        val runId=checkNotNull(InstrumentationRegistry.getArguments().getString("audioRunId"))
        val credentials=KeystoreCredentialStore(context);assumeTrue("Preserve personal pairing",credentials.load()==null)
        val fixture=JsonParser.parseString(shell("cat /data/local/tmp/codex-voice-fixture.json")).asJsonObject
        val settings=DataStorePreferencesRepository(context.preferencesStore);val initial=settings.preferences.first()
        val directory=File(context.noBackupFilesDir,"device-verification").apply{mkdirs()};val ready=File(directory,"controls-ready.json");ready.delete()
        File(directory,"controls-result.json").delete()
        var ownedId:String?=null;var approved=false
        val phase=InstrumentationRegistry.getArguments().getString("controlPhase")?:"all"
        fun stage(value:String){ready.writeText(Gson().toJson(mapOf("runId" to runId,"stage" to value)))}
        fun command(value:String){File(directory,"control.txt").writeText(value)}
        fun open(){click("Task controls");until("Task control panel"){text("One follow-up")}}
        try{
            until("Unlocked phone"){!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked}
            settings.setNotifications(initial.copy(notificationsEnabled=false));settings.setCallInvitations(false)
            settings.setSpeech(initial.copy(spokenSummaries=true,autoSpeakSummary=false,speechVoice="",speechRate=1f,speechPitch=1f))
            val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.runOnMainSync{activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            until("Normal app"){text("Settings")};compose.onNode(hasText("Settings") and hasClickAction()).performClick()
            val model=androidx.lifecycle.ViewModelProvider(activity as MainActivity)[com.codexvoice.app.feature.dashboard.DashboardViewModel::class.java]
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("PC monitor"));compose.onNodeWithText("PC monitor").performClick()
            compose.onNodeWithText("Private server address").performScrollTo().performTextInput(fixture["endpoint"].asString)
            compose.onNodeWithText("One-time pairing code").performScrollTo().performTextInput(fixture["code"].asString)
            click("Pair this PC");until("Private authenticated monitor"){text("Connected")};ownedId=credentials.load()!!.deviceId
            compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Offer audio calls"));compose.onNodeWithContentDescription("Offer audio calls").performClick()
            until("Call opt-in synchronized"){text("Call invitations synchronized")}
            compose.onNode(hasText("Home") and hasClickAction()).performClick()
            command("waiting");open();until("Simulated running task"){taskStatus()=="running"&&text("running")}
            if(phase=="all"){
            click("Interrupt task");until("Explicit interruption preview"){text("Confirm interruption")};assertEquals("running",taskStatus())
            click("Confirm interruption");until("Authoritative simulated interrupted outcome"){taskStatus()=="interrupted"&&text("Interruption · interrupted")}
            }
            click("Enable secure review");until("Reviewer enrollment or secure fallback"){control()?.get("reviewer")?.isJsonNull==false||text("Secure authentication unavailable")}
            val enrolled=control()?.get("reviewer")?.isJsonNull==false
            if(phase!="all"){command("interrupt");until("Fixture preparation completed"){taskStatus()=="interrupted"}}
            if(phase=="all"){
            click("Done");command("approval");open();until("Approval exact command on Android"){approvalStatus()=="pending"&&text("echo MOCK")}
            click("Review denial");until("Explicit denial confirmation"){text("Confirm denial")};click("Confirm denial")
            until("Same bridge approval denied and server-resolved"){approvalStatus()=="declined"&&control()?.getAsJsonArray("approvals")?.lastOrNull()?.asJsonObject?.get("serverConfirmed")?.asBoolean==true}
            click("Done")
            }else click("Done")
            if(enrolled&&InstrumentationRegistry.getArguments().getString("skipSecureApproval")!="true"){
                stage("authenticate-soon");command("approval-reviewed");open()
                until("Exact mock approval nominated with trusted reviewer"){approvalStatus()=="pending"&&control()?.getAsJsonObject("reviewer")?.get("trusted")?.asBoolean==true&&text("Reviewer key trusted on PC")}
                click("Approve once");stage("authenticate-now")
                until("Signed decision or explicit secure fallback",50000){
                    // The test clock must render the challenge-triggered system prompt after the network reply.
                    compose.waitForIdle()
                    File(directory,"secure-review-live.json").writeText(Gson().toJson(mapOf("runId" to runId,"diagnostic" to SecureApproval.diagnostic.value,"error" to model.controlState.value.error,"hasChallenge" to (model.controlState.value.challenge!=null))))
                    (approvalStatus()=="accepted"&&control()?.getAsJsonArray("approvals")?.lastOrNull()?.asJsonObject?.get("serverConfirmed")?.asBoolean==true)||model.controlState.value.error.isNotBlank()||SecureApproval.diagnostic.value.startsWith("crypto_unavailable")||SecureApproval.diagnostic.value.startsWith("authentication_unavailable")||SecureApproval.diagnostic.value.startsWith("authentication_error")||SecureApproval.diagnostic.value=="signature_unavailable"
                }
                approved=approvalStatus()=="accepted"
                File(directory,"secure-review-result.json").writeText(Gson().toJson(mapOf("runId" to runId,"approved" to approved,"diagnostic" to SecureApproval.diagnostic.value,"error" to model.controlState.value.error)))
                if(approved)until("Simulated approved task completes"){taskStatus()=="completed"}else{stage("secure-fallback");Thread.sleep(12000);command("decline");until("Fallback denied on fixture PC"){taskStatus()=="interrupted"}}
                click("Done")
            }
            command("waiting");open();until("Next simulated running turn on PC and Android"){
                compose.waitForIdle();taskStatus()=="running"&&model.controlState.value.task?.status=="running"&&model.controlState.value.task?.id==control()?.getAsJsonObject("task")?.get("id")?.asString
            }
            compose.onNodeWithText("Follow-up instruction").performScrollTo().performTextInput("Reply with the controlled follow-up result")
            click("Review follow-up");until("One follow-up confirmation"){text("Queue once")};click("Queue once");until("Confirmed queue visible"){text("Cancel queued instruction")}
            click("Cancel queued instruction");until("Queue cancelled without dispatch"){control()?.getAsJsonArray("operations")?.lastOrNull()?.asJsonObject?.get("state")?.asString=="cancelled"}
            click("Review follow-up");until("Fresh follow-up confirmation"){text("Queue once")};click("Queue once");until("Fresh confirmed queue"){text("Cancel queued instruction")}
            val before=metrics()!!["taskTurnStarts"].asInt;command("finish-waiting")
            until("One actual mock follow-up dispatch"){metrics()?.get("taskTurnStarts")?.asInt==before+1&&taskStatus()=="completed"}
            assertEquals(before+1,metrics()!!["taskTurnStarts"].asInt);click("Done")
            command("offer");until("Audio invitation offered"){metrics()?.getAsJsonArray("calls")?.lastOrNull()?.asJsonObject?.get("state")?.asString=="offered"}
            click("Open audio session");until("Explicit audio invitation"){CallRuntime.state.value.phase==LivePhase.OFFERED}
            compose.onNodeWithContentDescription("Answer real audio call").performScrollTo().performClick()
            until("Native audio and conversation after controls"){CallRuntime.state.value.phase==LivePhase.ACTIVE&&CallRuntime.state.value.capturing&&ConversationRuntime.state.value.enabled&&SpeechRuntime.state.value.ttsStatus=="ready"}
            compose.onNodeWithContentDescription("Use speaker").performScrollTo().performClick()
            val humanVoice=InstrumentationRegistry.getArguments().getString("humanVoice")=="true"
            if(humanVoice){stage("voice-status")
            until("Real utterance recognized after task controls",90000){
                compose.waitForIdle()
                File(directory,"voice-control-live.json").writeText(Gson().toJson(mapOf("runId" to runId,"transcriptCount" to SpeechRuntime.state.value.transcript.size,"speechError" to SpeechRuntime.state.value.error,"capturing" to CallRuntime.state.value.capturing,"speaking" to SpeechRuntime.state.value.speaking)))
                SpeechRuntime.state.value.transcript.isNotEmpty()
            }
            // Verify ordinary conversation response/proposal and real playback, not an assumed ASR wording.
            until("Local conversational reply played"){compose.waitForIdle();ConversationRuntime.state.value.entries.isNotEmpty()&&ConversationRuntime.state.value.lastSpokenSequence>0&&!SpeechRuntime.state.value.speaking&&CallRuntime.state.value.capturing}
            }else{
                until("Actual native microphone frames after controls"){metrics()?.getAsJsonArray("media")?.lastOrNull()?.asJsonObject?.getAsJsonObject("metrics")?.get("uplinkFrames")?.asInt?.let{it>=20}==true}
                until("Actual stored summary available"){SpeechRuntime.state.value.summary.isNotBlank()}
                click("Repeat response");until("Native summary TTS starts"){SpeechRuntime.state.value.speaking};assertFalse("Half-duplex capture gate",CallRuntime.state.value.capturing)
                until("Native TTS completes and microphone resumes"){!SpeechRuntime.state.value.speaking&&CallRuntime.state.value.capturing}
            }
            assertEquals(before+1,metrics()!!["taskTurnStarts"].asInt)
            compose.onNodeWithContentDescription("Hang up").performScrollTo().performClick()
            until("Microphone playback TTS and media released"){!CallRuntime.state.value.serviceRunning&&!CallRuntime.state.value.capturing&&!SpeechRuntime.state.value.speaking&&metrics()?.get("mediaConnected")?.asInt==0}
            assertTrue(ConversationRuntime.state.value.entries.isEmpty());assertTrue(SpeechRuntime.state.value.transcript.isEmpty())
            File(directory,"controls-result.json").writeText(Gson().toJson(mapOf("runId" to runId,"taskMode" to "mock","privateRoute" to true,"interruptVerified" to (phase=="all"),"denyVerified" to (phase=="all"),"approveOnceAuthenticated" to approved,"secureDiagnostic" to SecureApproval.diagnostic.value,"queueCancelAndDispatchVerified" to true,"nativeAudioTtsAfterControls" to true,"nativeAsrAfterControls" to humanVoice,"cleanupVerified" to true,"realInferenceTurns" to 0)))
            stage("complete")
        }finally{
            if(CallRuntime.state.value.serviceRunning){CallRuntime.command(context,CallAudioService.END);until("Finally releases audio",15000){!CallRuntime.state.value.serviceRunning}}
            settings.setNotifications(initial);settings.setCallInvitations(initial.callInvitations);settings.setSpeech(initial)
            if(credentials.load()?.deviceId==ownedId)credentials.clear();CallRuntime.dismiss();SpeechRuntime.clear();ConversationRuntime.clear()
            instrumentation.runOnMainSync{val monitor=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance();for(s in listOf(androidx.test.runner.lifecycle.Stage.RESUMED,androidx.test.runner.lifecycle.Stage.PAUSED,androidx.test.runner.lifecycle.Stage.STOPPED,androidx.test.runner.lifecycle.Stage.STARTED))monitor.getActivitiesInStage(s).filterIsInstance<MainActivity>().toList().forEach{it.finish()}}
        }
    }
}
