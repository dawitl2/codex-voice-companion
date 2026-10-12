package com.codexvoice.app.core.speech

import android.content.Context
import com.codexvoice.app.core.calls.*
import com.codexvoice.app.core.data.PreferencesRepository
import com.codexvoice.app.core.model.UserPreferences
import com.codexvoice.app.core.remote.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Authenticated active-call polling only. Recognized text never invokes Codex or a shell. */
class SpeechSessionController(private val context:Context, private val client:RemoteClient, preferences:PreferencesRepository, parent:CoroutineScope) {
    private val scope=CoroutineScope(parent.coroutineContext+Dispatchers.IO.limitedParallelism(1))
    private var prefs=UserPreferences(); private var callId:String?=null; private var connected=false; private var configured=false
    private var summaryRequested=false; private var autoPlayed=false; private var playback="none"; private var playingPending=false
    private val pending=mutableMapOf<String,String>(); private var polledAt=0L
    init {
        scope.launch { preferences.preferences.collect { prefs=it; if(!it.spokenSummaries) stop(); sync() } }
        scope.launch { CallRuntime.state.collect { sync() } }
        scope.launch { SpeechRuntime.state.collect { sync() } }
        scope.launch { client.state.collect { connected=it.status==RemoteStatus.CONNECTED; if(!connected) { configured=false; pending.clear(); playingPending=false }; sync() } }
        scope.launch { client.speech.collect { response ->
            val operation=pending.remove(response.correlationId) ?: return@collect
            if (CallRuntime.state.value.phase!=LivePhase.ACTIVE || callId!=CallRuntime.state.value.offer?.callId) return@collect
            if(response.type=="speech.error") {
                playingPending=false
                SpeechRuntime.update { it.copy(engine="unavailable",phase="error",error=response.payload["code"].asString) }; return@collect
            }
            if(response.payload["callId"].asString!=callId) return@collect
            if(response.type=="speech.summary.response") SpeechRuntime.update { it.copy(summary=response.payload["text"].asString) }
            else {
                val rows=SpeechWire.utterances(response.payload)
                SpeechRuntime.update { old -> old.copy(phase=response.payload["state"].asString,engine=response.payload["engine"].asString,error=response.payload["error"].asString,
                    transcript=(old.transcript+rows).distinctBy { it.sequence }.takeLast(8)) }
                if(operation=="start-playback") {
                    val wanted=playingPending; playingPending=false
                    if(wanted && prefs.spokenSummaries && SpeechRuntime.state.value.ttsStatus=="ready") CallRuntime.command(context,CallAudioService.SPEAK)
                    else request("speech.playback",mapOf("state" to "error"),"playback")
                }
            }
            sync()
        } }
        scope.launch { while(isActive) { delay(250); sync() } }
    }
    private fun request(type:String,extra:Map<String,Any> = emptyMap(),operation:String=type) {
        val id=callId ?: return
        if(connected && pending.size<8) pending[client.speech(type,mapOf("callId" to id)+extra)]=operation
    }
    private fun sync() {
        val live=CallRuntime.state.value
        if(live.phase!=LivePhase.ACTIVE || !live.serviceRunning) {
            if(live.phase in setOf(LivePhase.IDLE,LivePhase.ENDED,LivePhase.DECLINED,LivePhase.EXPIRED,LivePhase.FAILED)) {
                callId=null; configured=false; summaryRequested=false; autoPlayed=false; playback="none"; pending.clear(); playingPending=false
                if(SpeechRuntime.state.value.callId.isNotEmpty()) SpeechRuntime.clear()
            }
            return
        }
        val current=live.offer?.callId ?: return
        if(callId!=current) { callId=current; configured=false; summaryRequested=false; autoPlayed=false; playback="none"; playingPending=false; pending.clear() }
        if(!connected) return
        if(!configured) { configured=true; request("speech.listening",mapOf("enabled" to true)); request("speech.playback",mapOf("state" to if(SpeechRuntime.state.value.speaking) "started" else "completed"),"playback") }
        if(!summaryRequested) { summaryRequested=true; request("speech.summary.request",operation="summary") }
        val speech=SpeechRuntime.state.value
        if(speech.playback!=playback && speech.playback!="none") { playback=speech.playback; request("speech.playback",mapOf("state" to playback),"playback") }
        if(!autoPlayed && prefs.autoSpeakSummary && prefs.spokenSummaries && speech.summary.isNotBlank() && speech.ttsStatus=="ready") { autoPlayed=true; repeat() }
        if(System.currentTimeMillis()-polledAt>750 && pending.values.none { it=="poll" }) { polledAt=System.currentTimeMillis(); request("speech.status.request",mapOf("afterSequence" to (speech.transcript.lastOrNull()?.sequence ?: 0)),"poll") }
    }
    fun repeat() = scope.launch {
        val speech=SpeechRuntime.state.value
        if(!connected || playingPending || speech.summary.isBlank() || !prefs.spokenSummaries || speech.ttsStatus!="ready") return@launch
        playingPending=true; request("speech.playback",mapOf("state" to "started"),"start-playback")
    }
    fun stop() = scope.launch { playingPending=false; CallRuntime.command(context,CallAudioService.STOP_SPEECH) }
    fun finishSpeaking() = scope.launch { request("speech.finish") }
    fun close() { pending.clear(); scope.cancel() }
}
