package com.codexvoice.app

import com.codexvoice.app.core.speech.SpeechStartGate
import org.junit.Assert.*
import org.junit.Test

class SpeechStartGateTest {
    @Test fun anotherReplyCannotStartDuringTheTransportToNativeCallbackGap(){
        val gate=SpeechStartGate();assertTrue(gate.request(100,1000))
        // The reply request was acknowledged, but native TTS still has the old start time.
        assertFalse(gate.observeNativeStart(100));assertTrue(gate.pending);assertFalse(gate.request(100,1200))
        assertTrue(gate.observeNativeStart(1500));assertFalse(gate.pending);assertTrue(gate.request(1500,2000))
    }
    @Test fun failedStartupTimesOutAndStopReleasesThePendingRequest(){
        val gate=SpeechStartGate();gate.request(0,1000);assertFalse(gate.timedOut(10999));assertTrue(gate.timedOut(11000));gate.cancel();assertFalse(gate.pending)
    }
}
