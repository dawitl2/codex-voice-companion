package com.codexvoice.app.core.speech

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
/** Session text in memory only. Offline voice metadata survives a call; transcript/summary do not. */
object SpeechRuntime {
    private val mutable=MutableStateFlow(SpeechUiState()); val state=mutable.asStateFlow()
    fun update(action:(SpeechUiState)->SpeechUiState) { mutable.update(action) }
    fun begin(callId:String) { mutable.update { SpeechUiState(callId=callId, voices=it.voices,voice=it.voice,ttsEngine=it.ttsEngine) } }
    fun clear() { mutable.update { SpeechUiState(voices=it.voices,voice=it.voice,ttsEngine=it.ttsEngine,ttsStatus="not_initialized") } }
}
