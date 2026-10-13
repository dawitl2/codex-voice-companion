package com.codexvoice.app
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import com.codexvoice.app.core.calls.*
import com.codexvoice.app.core.conversation.ConversationRuntime
import com.codexvoice.app.core.data.DataStorePreferencesRepository
import com.codexvoice.app.core.remote.*
import com.codexvoice.app.core.speech.SpeechRuntime
import com.codexvoice.app.feature.dashboard.DashboardViewModel
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in single REAL coding acceptance. Uses normal UI, pairing and supported plan-backed service. */
class FinalDeliveryTest{
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun until(label:String,timeout:Long=45000,p:()->Boolean){val end=SystemClock.elapsedRealtime()+timeout;while(!p()){check(SystemClock.elapsedRealtime()<end){"$label timed out; call=${CallRuntime.state.value.phase}; problem=${CallRuntime.state.value.problem}"};Thread.sleep(100)};println("Verified: $label")}
    private fun text(value:String)=try{compose.onAllNodesWithText(value,substring=true).fetchSemanticsNodes().isNotEmpty()}catch(_:IllegalStateException){false}
    private fun click(value:String)=compose.onNodeWithText(value).performScrollTo().performClick()
    @Test fun realDesktopOutgoingTextCodingIncomingAndCleanup()=runBlocking{
        assumeTrue(InstrumentationRegistry.getArguments().getString("finalDelivery")=="true")
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked){"User must unlock phone"}
        val directory=File(context.noBackupFilesDir,"final-delivery").apply{mkdirs()}
        fun stage(value:String){File(directory,"stage.txt").writeText(value)}
        val settings=DataStorePreferencesRepository(context.preferencesStore);val prefs=settings.preferences.first()
        settings.setNotifications(prefs.copy(notificationsEnabled=true));settings.setCallInvitations(true);settings.setSpeech(prefs.copy(spokenSummaries=true,autoSpeakSummary=true))
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.runOnMainSync{activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        val model=ViewModelProvider(activity)[DashboardViewModel::class.java]
        var outgoing=false;var received=false;var incoming=false;var spoken=false
        try{
            until("Normal application UI"){text("Settings")}
            if(KeystoreCredentialStore(context).load()==null){
                val fixture=instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/codex-voice-final-pair.json").use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()}.let{JsonParser.parseString(it).asJsonObject}
                compose.onNode(hasText("Settings") and hasClickAction()).performClick();compose.onNode(hasScrollAction()).performScrollToNode(hasText("PC monitor"));click("PC monitor")
                compose.onNodeWithText("Private server address").performScrollTo().performTextInput(fixture["endpoint"].asString);compose.onNodeWithText("One-time pairing code").performScrollTo().performTextInput(fixture["code"].asString);click("Pair this PC")
            }else model.connectPc()
            until("Real authenticated private PC connection"){model.dashboard.value.remote?.status==RemoteStatus.CONNECTED}
            assertEquals("real",model.dashboard.value.remote?.snapshot?.mode)
            until("Selected managed coding conversation"){model.dashboard.value.remote?.snapshot?.selectedThreadId!=null}
            val thread=model.dashboard.value.remote!!.snapshot!!.selectedThreadId!!
            compose.onNode(hasText("Home") and hasClickAction()).performClick();click("Call Codex")
            until("Phone-initiated private microphone audio"){CallRuntime.state.value.phase==LivePhase.ACTIVE&&CallRuntime.state.value.capturing&&ConversationRuntime.state.value.enabled}
            outgoing=true;until("Core Telecom owns this outgoing call"){TelecomRuntime.control!=null}
            Thread.sleep(1500);stage("outgoing-audio-active")
            compose.onNodeWithContentDescription("Use speaker").performClick();until("System speaker route"){CallRuntime.state.value.speaker}
            click("Hang up");until("Outgoing microphone released"){!CallRuntime.state.value.serviceRunning&&!CallRuntime.state.value.capturing}
            until("Telecom call released"){!TelecomRuntime.claimed}
            click("Return to dashboard");click("Text Codex");until("Real text history loaded"){model.chatState.value.selected&&model.chatState.value.threadId==thread}
            assertEquals("workspace-write",model.chatState.value.policy)
            val instruction="Create a small text file named voice-check.txt in the selected test workspace containing exactly Codex Voice works. Do not change any other file. Reply briefly with the result."
            if(!File(directory,"sent.txt").exists()){
                compose.onNodeWithText("Instruction to this conversation").performTextInput(instruction)
                File(directory,"sent.txt").writeText("Attempt authorized once; inspect actual thread before any retry.")
                click("Send instruction")
            }
            stage("real-task-submitted")
            until("Actual Codex completed response on Android",180000){model.chatState.value.turns.any{it.instruction==instruction&&it.status=="completed"&&it.response.isNotBlank()}}
            val turn=model.chatState.value.turns.last{it.instruction==instruction};received=true
            File(directory,"result.json").writeText(Gson().toJson(mapOf("threadId" to thread,"turnId" to turn.id,"instruction" to instruction,"response" to turn.response,"outgoingAudio" to outgoing,"realResponseOnAndroid" to received)))
            stage("real-response-received")
            until("Automatic real incoming invitation",60000){CallRuntime.state.value.phase==LivePhase.OFFERED&&CallRuntime.state.value.offer?.turnId==turn.id}
            assertFalse("No microphone before Answer",CallRuntime.state.value.capturing)
            until("Real incoming Core Telecom call"){TelecomRuntime.control!=null}
            val notifications=context.getSystemService(NotificationManager::class.java).activeNotifications
            assertTrue("Incoming CallStyle notification",notifications.any{it.id==707&&it.notification.extras.getString("android.template")?.contains("CallStyle")==true})
            incoming=true;stage("incoming-call-offered")
            compose.onNodeWithContentDescription("Answer real audio call").performClick()
            until("Incoming Answer establishes real media"){CallRuntime.state.value.phase==LivePhase.ACTIVE&&CallRuntime.state.value.capturing}
            until("Actual response sent to offline speech output"){SpeechRuntime.state.value.summary.isNotBlank()&&SpeechRuntime.state.value.speechStartedAtMs>0}
            spoken=true;Thread.sleep(1500);click("Hang up");until("Final microphone and playback released"){!CallRuntime.state.value.serviceRunning&&!CallRuntime.state.value.capturing&&!CallRuntime.state.value.playing}
            until("Final Telecom ownership released"){!TelecomRuntime.claimed}
            File(directory,"result.json").writeText(Gson().toJson(mapOf("threadId" to thread,"turnId" to turn.id,"instruction" to instruction,"response" to turn.response,"outgoingAudio" to outgoing,"realResponseOnAndroid" to received,"incomingCallStyle" to incoming,"offlineResponseSpeechStarted" to spoken,"cleanup" to true)))
            stage("complete")
        }finally{if(CallRuntime.state.value.serviceRunning)CallRuntime.command(context,CallAudioService.END);println("Final delivery flags: outgoing=$outgoing response=$received incoming=$incoming speech=$spoken")}
    }
}
