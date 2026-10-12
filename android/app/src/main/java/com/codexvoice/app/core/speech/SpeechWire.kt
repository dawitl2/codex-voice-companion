package com.codexvoice.app.core.speech

import com.google.gson.JsonObject
import com.codexvoice.app.core.remote.RemoteProtocol

data class RecognizedUtterance(val id: String, val sequence: Long, val text: String, val processingMs: Long, val endedAtMs:Long=0,val receivedAtMs:Long=0)
data class OfflineVoice(val name: String, val label: String)
data class SpeechUiState(val callId: String = "", val phase: String = "paused", val engine: String = "loading",
    val error: String = "", val transcript: List<RecognizedUtterance> = emptyList(), val summary: String = "",
    val voices: List<OfflineVoice> = emptyList(), val voice: String = "", val ttsEngine: String = "", val ttsStatus: String = "not_initialized",
    val speaking: Boolean = false, val playback: String = "none", val initializationMs: Long = 0, val speechStartMs: Long = 0,
    val outputText: String = "",val speechStartedAtMs:Long=0)
object SpeechWire {
    private fun keys(p:JsonObject, fields:Set<String>) { require(p.keySet()==fields) }
    private fun string(p:JsonObject,key:String,max:Int)=RemoteProtocol.string(p,key,max)
    private fun number(p:JsonObject,key:String):Long { val v=p[key].asJsonPrimitive; require(v.isNumber); return v.asBigDecimal.longValueExact() }
    private fun boolean(p:JsonObject,key:String):Boolean { val v=p[key].asJsonPrimitive; require(v.isBoolean); return v.asBoolean }
    private fun date(p:JsonObject,key:String)=java.time.Instant.parse(string(p,key,48)).toEpochMilli()
    private val phases = setOf("listening","speech_detected","processing","transcript_ready","speaking","paused","error")
    fun validate(type: String, p: JsonObject) {
        if (type == "speech.error") { keys(p,setOf("code")); require(string(p,"code",64).matches(Regex("[a-z_]+"))); return }
        require(string(p,"callId",36).matches(Regex("[a-f0-9-]{36}")))
        if (type == "speech.summary.response") {
            keys(p,setOf("callId","text","mode","taskState")); string(p,"text",1000)
            require(string(p,"mode",8) in setOf("mock","real")); require(string(p,"taskState",32) in setOf("starting","running","awaitingApproval","completed","failed","interrupted","unknown","unavailable")); return
        }
        require(type == "speech.status.response"); keys(p,setOf("callId","state","engine","language","error","utterances"))
        require(string(p,"state",32) in phases && string(p,"engine",32) in setOf("loading","ready","unavailable") && string(p,"language",8) == "en")
        require(p["error"].isJsonPrimitive && p["error"].asJsonPrimitive.isString && p["error"].asString.matches(Regex("[a-z_]{0,64}")))
        val rows=p["utterances"].asJsonArray; require(rows.size()<=8); var last=0L
        for (row in rows) {
            val u=row.asJsonObject; keys(u,setOf("utteranceId","sequence","startedAt","endedAt","text","language","final","processingMs"))
            require(string(u,"utteranceId",36).matches(Regex("[a-f0-9-]{36}"))); val sequence=number(u,"sequence"); require(sequence>last && sequence<=9007199254740991L); last=sequence
            require(date(u,"endedAt")>=date(u,"startedAt")); string(u,"text",2000); require(string(u,"language",8)=="en" && boolean(u,"final")); require(number(u,"processingMs") in 0..60000)
        }
    }
    fun utterances(p: JsonObject) = p["utterances"].asJsonArray.map { row -> val u=row.asJsonObject; RecognizedUtterance(string(u,"utteranceId",36),number(u,"sequence"),string(u,"text",2000),number(u,"processingMs"),date(u,"endedAt"),System.currentTimeMillis()) }
}

fun speechLabel(capturing:Boolean, muted:Boolean, speech:SpeechUiState):String = when {
    speech.speaking -> "Speaking"
    muted -> "Muted"
    speech.engine=="unavailable" -> "Recognition unavailable"
    speech.engine=="loading" -> "Preparing speech"
    speech.phase=="processing" -> "Processing speech"
    !capturing -> "Microphone paused"
    speech.phase=="speech_detected" -> "Speech detected"
    speech.phase=="transcript_ready" -> "Speech recognized"
    else -> "Listening"
}
fun speechErrorText(code:String) = when(code) {
    "offline_english_voice_missing", "offline_voice_unavailable" -> "Install an offline English voice in Android text-to-speech settings."
    "asr_engine_missing", "asr_model_missing", "asr_model_invalid", "asr_unavailable" -> "Run local speech setup on your PC."
    "speech_disabled" -> "Start the PC bridge with speech enabled."
    "recognition_timeout" -> "Recognition timed out. Try a shorter sentence."
    "empty_transcription" -> "No words recognized. Speak again or use Finish speaking."
    "tts_playback_failed", "tts_initialization_failed" -> "Spoken output is unavailable. The text summary remains available."
    "recognition_queue_full", "asr_busy" -> "Recognition is busy. Wait before your next sentence."
    else -> "Speech is temporarily unavailable. Reconnect or try again."
}
