package com.codexvoice.app.core.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.codexvoice.app.core.model.UserPreferences
import java.util.UUID

/** Installed, network-free English voices only. No synthesize-to-file or network voice fallback. */
class AndroidTtsEngine(context:Context, private val finished:(String)->Unit) {
    private var tts:TextToSpeech?=null; @Volatile private var closed=false; @Volatile private var current:String?=null
    private val started=SystemClock.elapsedRealtime(); private var requestedAt=0L
    private var preferences=UserPreferences()
    private val application=context.applicationContext
    private val attempted=mutableSetOf<String>()
    private var provider=""; private var generation=0
    init { open(null) }
    private fun open(name:String?) {
        val attempt=++generation; provider=name ?: ""; if(name!=null) attempted.add(name)
        tts=TextToSpeech(application,{ status ->
            if (!closed && generation==attempt) {
                if(status==TextToSpeech.SUCCESS) initialize() else if(!nextProvider()) fail("tts_initialization_failed")
            }
        },name)
    }
    private fun nextProvider():Boolean {
        val engine=tts ?: return false
        if(provider.isEmpty()) { provider=engine.defaultEngine ?: ""; attempted.add(provider) }
        val next=engine.engines.firstOrNull { it.name !in attempted } ?: return false
        if(attempted.size>=3) return false
        engine.shutdown(); open(next.name); return true
    }
    private fun initialize() {
        val engine=tts ?: return
        try {
            val voices=engine.voices?.filter { it.locale.language=="en" && !it.isNetworkConnectionRequired && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                ?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale.country=="US" }.thenByDescending { it.quality }.thenBy { it.latency }) ?: emptyList()
            if (voices.isEmpty()) { if(!nextProvider()) fail("offline_english_voice_missing"); return }
            val voice=voices.find { it.name==preferences.speechVoice } ?: voices.first()
            check(engine.setVoice(voice)==TextToSpeech.SUCCESS && engine.voice?.isNetworkConnectionRequired==false)
            engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            engine.setOnUtteranceProgressListener(object:UtteranceProgressListener() {
                override fun onStart(id:String?) { if (!closed && id==current) SpeechRuntime.update { it.copy(speaking=true, playback="started", speechStartMs=SystemClock.elapsedRealtime()-requestedAt,speechStartedAtMs=System.currentTimeMillis()) } }
                override fun onDone(id:String?) { if (!closed && id==current) complete("completed") }
                @Deprecated("Use the coded callback") override fun onError(id:String?) { if (!closed && id==current) complete("error") }
                override fun onError(id:String?, code:Int) { if (!closed && id==current) complete("error") }
                override fun onStop(id:String?, interrupted:Boolean) { if (!closed && id==current) complete("interrupted") }
            })
            SpeechRuntime.update { it.copy(voices=voices.mapIndexed { index,v->OfflineVoice(v.name,"${v.locale.displayName} · Voice ${index+1}") },voice=voice.name,ttsEngine=provider.ifEmpty { engine.defaultEngine ?: "" },ttsStatus="ready",initializationMs=SystemClock.elapsedRealtime()-started) }
        } catch (_:Exception) { fail("offline_voice_unavailable") }
    }
    fun configure(value:UserPreferences) { preferences=value; if (SpeechRuntime.state.value.ttsStatus=="ready") initialize() }
    fun speak(text:String):Boolean {
        if (closed || !preferences.spokenSummaries || text.isBlank() || text.length>1000 || SpeechRuntime.state.value.ttsStatus!="ready") return false
        val engine=tts ?: return false
        val id=UUID.randomUUID().toString(); current=id; requestedAt=SystemClock.elapsedRealtime()
        try {
            check(engine.setSpeechRate(preferences.speechRate.coerceIn(.5f,1.5f))==TextToSpeech.SUCCESS)
            check(engine.setPitch(preferences.speechPitch.coerceIn(.5f,1.5f))==TextToSpeech.SUCCESS)
            if (engine.speak(text,TextToSpeech.QUEUE_FLUSH,null,id)!=TextToSpeech.SUCCESS) { complete("error"); return false }
            return true
        } catch (_:Exception) { complete("error"); return false }
    }
    private fun complete(state:String) { current=null; SpeechRuntime.update { it.copy(speaking=false, playback=state,error=if(state=="error") "tts_playback_failed" else it.error) }; finished(state) }
    private fun fail(code:String) { SpeechRuntime.update { it.copy(ttsStatus=code,speaking=false,error=code) }; finished("error") }
    fun stop() { val was=current!=null; current=null; tts?.stop(); if(was) { SpeechRuntime.update { it.copy(speaking=false,playback="interrupted") }; finished("interrupted") } }
    fun close() { closed=true; current=null; tts?.stop(); tts?.shutdown(); tts=null; SpeechRuntime.update { it.copy(speaking=false) } }
}
