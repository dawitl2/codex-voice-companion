package com.codexvoice.app

import com.codexvoice.app.core.speech.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SpeechStateTest {
    @Test fun microphoneStoppedOrMutedNeverDisplaysListening() {
        val ready=SpeechUiState(engine="ready",phase="listening")
        assertEquals("Microphone paused",speechLabel(false,false,ready))
        assertEquals("Muted",speechLabel(true,true,ready))
        assertEquals("Speaking",speechLabel(false,false,ready.copy(speaking=true)))
        assertEquals("Processing speech",speechLabel(true,false,ready.copy(phase="processing")))
    }
    @Test fun finalUtterancesAreStrictBoundedAndNotGuessedConfidence() {
        val id=UUID.randomUUID().toString()
        val p=JsonParser.parseString("""{"callId":"$id","state":"transcript_ready","engine":"ready","language":"en","error":"","utterances":[{"utteranceId":"$id","sequence":1,"startedAt":"2026-10-12T00:00:00Z","endedAt":"2026-10-12T00:00:01Z","text":"The green button is ready.","language":"en","final":true,"processingMs":700}]}""").asJsonObject
        SpeechWire.validate("speech.status.response",p); assertEquals("The green button is ready.",SpeechWire.utterances(p).single().text)
        p["utterances"].asJsonArray.first().asJsonObject.addProperty("confidence",.9)
        try { SpeechWire.validate("speech.status.response",p); fail("Unexpected confidence field accepted") } catch (_:IllegalArgumentException) { }
    }
    @Test fun stateClearDiscardsSpeechTextButKeepsOfflineVoiceMetadata() {
        SpeechRuntime.update { SpeechUiState(callId="test",summary="ephemeral",transcript=listOf(RecognizedUtterance("u",1,"temporary words",1)),voices=listOf(OfflineVoice("en","English")),voice="en") }
        SpeechRuntime.clear(); assertTrue(SpeechRuntime.state.value.summary.isEmpty()); assertTrue(SpeechRuntime.state.value.transcript.isEmpty()); assertEquals("en",SpeechRuntime.state.value.voice)
    }
}
