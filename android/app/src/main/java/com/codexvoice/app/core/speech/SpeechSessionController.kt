package com.codexvoice.app.core.speech

import android.content.Context
import com.codexvoice.app.core.calls.*
import com.codexvoice.app.core.data.PreferencesRepository
import com.codexvoice.app.core.model.UserPreferences
import com.codexvoice.app.core.remote.*
import com.codexvoice.app.core.conversation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Authenticated call polling and one half-duplex output pipeline. Confirmed dispatch stays on PC. */
class SpeechSessionController(private val context:Context, private val client:RemoteClient, preferences:PreferencesRepository, parent:CoroutineScope) {
    private val scope=CoroutineScope(parent.coroutineContext+Dispatchers.IO.limitedParallelism(1))
    private var prefs=UserPreferences(); private var callId:String?=null; private var connected=false; private var configured=false
    private var summaryRequested=false; private var autoPlayed=false; private var playback="none"; private val startGate=SpeechStartGate()
    private val pending=mutableMapOf<String,String>(); private var polledAt=0L
    private var conversationPolledAt=0L; private var conversationDisabled=false; private var outputSequence=0L
    private var appliedAutomatic:Boolean?=null;private var editPaused=false
    init {
        scope.launch { preferences.preferences.collect { prefs=it; if(!it.spokenSummaries) stop(); sync() } }
        scope.launch { CallRuntime.state.collect { sync() } }
        scope.launch { SpeechRuntime.state.collect { sync() } }
        scope.launch { ConversationRuntime.state.collect { sync() } }
        scope.launch { client.state.collect { connected=it.status==RemoteStatus.CONNECTED; if(!connected) { configured=false; appliedAutomatic=null; pending.clear(); startGate.cancel() }; sync() } }
        scope.launch { client.speech.collect { response ->
            val operation=pending.remove(response.correlationId) ?: return@collect
            if (CallRuntime.state.value.phase!=LivePhase.ACTIVE || callId!=CallRuntime.state.value.offer?.callId) return@collect
            if(response.type=="speech.error") {
                startGate.cancel()
                SpeechRuntime.update { it.copy(engine="unavailable",phase="error",error=response.payload["code"].asString) }; return@collect
            }
            if(response.payload["callId"].asString!=callId) return@collect
            if(response.type=="speech.summary.response") SpeechRuntime.update { it.copy(summary=response.payload["text"].asString) }
            else {
                val rows=SpeechWire.utterances(response.payload)
                SpeechRuntime.update { old -> old.copy(phase=response.payload["state"].asString,engine=response.payload["engine"].asString,error=response.payload["error"].asString,
                    transcript=(old.transcript+rows).distinctBy { it.sequence }.takeLast(8),provisional=SpeechWire.provisional(response.payload)) }
                if(operation=="start-playback") {
                    val wanted=startGate.pending
                    if(wanted && prefs.spokenSummaries && SpeechRuntime.state.value.ttsStatus=="ready") CallRuntime.command(context,CallAudioService.SPEAK)
                    else {startGate.cancel();request("speech.playback",mapOf("state" to "error"),"playback")}
                }
            }
            sync()
        } }
        scope.launch { client.conversation.collect { response ->
            pending.remove(response.correlationId) ?: return@collect
            if(CallRuntime.state.value.phase!=LivePhase.ACTIVE || callId!=CallRuntime.state.value.offer?.callId)return@collect
            if(response.type=="conversation.error") {
                val code=response.payload["code"].asString
                if(code=="conversation_disabled")conversationDisabled=true
                ConversationRuntime.update{it.copy(error=code,proposal=if(code=="confirmation_unavailable")null else it.proposal)}
                return@collect
            }
            val next=ConversationWire.parse(response.payload)
            if(editPaused && next.proposal==null && next.phase in setOf("accepted","running","completed","cancelled")){editPaused=false;appliedAutomatic=null}
            if(next.callId==callId)ConversationRuntime.receive(next)
            sync()
        } }
        scope.launch { while(isActive) { delay(250); sync() } }
    }
    private fun request(type:String,extra:Map<String,Any> = emptyMap(),operation:String=type) {
        val id=callId ?: return
        if(connected && pending.size<8) pending[client.speech(type,mapOf("callId" to id)+extra)]=operation
    }
    private fun conversationRequest(type:String,extra:Map<String,Any> = emptyMap(),operation:String=type) {
        val id=callId ?: return
        if(connected && pending.size<8)pending[client.conversation(type,mapOf("callId" to id)+extra)]=operation
    }
    private fun sync() {
        val live=CallRuntime.state.value
        if(live.phase!=LivePhase.ACTIVE || !live.serviceRunning) {
            if (live.phase == LivePhase.RECONNECTING) {
                // PC speech/conversation sessions are recreated with the fresh media grant.
                // Re-enable listening and fetch the summary, without replaying old speech.
                configured=false; appliedAutomatic=null; summaryRequested=false; autoPlayed=true; playback="none"
                pending.clear(); startGate.cancel(); outputSequence=0
            }
            if(live.phase in setOf(LivePhase.IDLE,LivePhase.ENDED,LivePhase.DECLINED,LivePhase.EXPIRED,LivePhase.FAILED)) {
                callId=null; configured=false; summaryRequested=false; autoPlayed=false; playback="none"; pending.clear(); startGate.cancel()
                if(SpeechRuntime.state.value.callId.isNotEmpty()) SpeechRuntime.clear()
                ConversationRuntime.clear()
            }
            return
        }
        val current=live.offer?.callId ?: return
        if(callId!=current) { callId=current; configured=false; appliedAutomatic=null; editPaused=false; summaryRequested=false; autoPlayed=false; playback="none"; startGate.cancel(); pending.clear();conversationDisabled=false;conversationPolledAt=0;outputSequence=0
            if(ConversationRuntime.state.value.callId!=current)ConversationRuntime.clear()
        }
        if(!connected) return
        val automatic=prefs.autoSendInstructions && !editPaused
        if(appliedAutomatic!=automatic && !conversationDisabled){appliedAutomatic=automatic;conversationRequest("conversation.preferences",mapOf("automatic" to automatic))}
        if(!configured) { configured=true; request("speech.listening",mapOf("enabled" to true)); request("speech.playback",mapOf("state" to if(SpeechRuntime.state.value.speaking) "started" else "completed"),"playback") }
        if(!summaryRequested) { summaryRequested=true; request("speech.summary.request",operation="summary") }
        val speech=SpeechRuntime.state.value
        if(startGate.observeNativeStart(speech.speechStartedAtMs) && outputSequence>0)ConversationRuntime.update{if(it.lastSpokenSequence<outputSequence)it.copy(lastSpokenSequence=outputSequence)else it}
        if(startGate.timedOut(System.currentTimeMillis())) {
            stopNow();request("speech.playback",mapOf("state" to "error"),"playback")
            SpeechRuntime.update{it.copy(error="tts_playback_failed",speaking=false)}
        }
        if(speech.playback!=playback && speech.playback!="none") { playback=speech.playback; request("speech.playback",mapOf("state" to playback),"playback") }
        if(!autoPlayed && prefs.autoSpeakSummary && prefs.spokenSummaries && speech.summary.isNotBlank() && speech.ttsStatus=="ready") { autoPlayed=true;play(speech.summary,0) }
        if(!speech.speaking && !startGate.pending && prefs.spokenSummaries && speech.ttsStatus=="ready" && (autoPlayed || !prefs.autoSpeakSummary)) {
            val conversation=ConversationRuntime.state.value
            conversation.entries.firstOrNull{it.sequence>conversation.lastSpokenSequence}?.let{play(it.spoken,it.sequence)}
        }
        if(System.currentTimeMillis()-polledAt>500 && pending.values.none { it=="poll" }) { polledAt=System.currentTimeMillis(); request("speech.status.request",mapOf("afterSequence" to (speech.transcript.lastOrNull()?.sequence ?: 0)),"poll") }
        if(!conversationDisabled && System.currentTimeMillis()-conversationPolledAt>(if(ConversationRuntime.state.value.proposal!=null)200 else 1000) && pending.values.none{it=="conversation-poll"}) {
            conversationPolledAt=System.currentTimeMillis();conversationRequest("conversation.status.request",mapOf("afterSequence" to (ConversationRuntime.state.value.entries.lastOrNull()?.sequence ?: 0)),"conversation-poll")
        }
    }
    private fun play(text:String,sequence:Long) {
        if(!startGate.request(SpeechRuntime.state.value.speechStartedAtMs,System.currentTimeMillis()))return
        outputSequence=sequence;SpeechRuntime.update{it.copy(outputText=text)}
        request("speech.playback",mapOf("state" to "started"),"start-playback")
    }
    fun repeat() = scope.launch {
        val speech=SpeechRuntime.state.value
        if(!connected || startGate.pending || !prefs.spokenSummaries || speech.ttsStatus!="ready") return@launch
        val output=ConversationRuntime.state.value.entries.lastOrNull()?.spoken ?: speech.summary
        if(output.isNotBlank())play(output,0)
    }
    private fun stopNow() {
        startGate.cancel();outputSequence=0
        ConversationRuntime.update{it.copy(lastSpokenSequence=it.entries.lastOrNull()?.sequence ?: it.lastSpokenSequence)}
        CallRuntime.command(context,CallAudioService.STOP_SPEECH)
    }
    fun stop() = scope.launch { stopNow() }
    fun finishSpeaking() = scope.launch { request("speech.finish") }
    fun confirmProposal(id:String)=scope.launch{stopNow();conversationRequest("conversation.confirm",mapOf("confirmationId" to id))}
    fun cancelProposal(id:String)=scope.launch{stopNow();conversationRequest("conversation.cancel",mapOf("confirmationId" to id))}
    fun correctProposal(id:String,text:String)=scope.launch{stopNow();conversationRequest("conversation.correct",mapOf("confirmationId" to id,"text" to text))}
    fun editProposal(id:String)=scope.launch{conversationRequest("conversation.edit",mapOf("confirmationId" to id))}
    fun editDictation()=scope.launch{editPaused=true;appliedAutomatic=false;conversationRequest("conversation.preferences",mapOf("automatic" to false));request("speech.finish")}
    fun selectThread(id:String)=scope.launch{stopNow();conversationRequest("conversation.select",mapOf("threadId" to id))}
    fun close() { pending.clear(); scope.cancel() }
}
