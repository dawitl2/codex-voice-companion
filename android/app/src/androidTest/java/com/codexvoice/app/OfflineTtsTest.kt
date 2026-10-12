package com.codexvoice.app

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.codexvoice.app.core.speech.*
import com.codexvoice.app.core.calls.CallRuntime
import com.codexvoice.app.core.model.UserPreferences
import com.google.gson.Gson
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Short physical offline-voice lifecycle probe, independent of ASR/call transport. */
class OfflineTtsTest {
    @Test fun installedOfflineEnglishVoiceSpeaksAndCanBeStopped() {
        val instrumentation=InstrumentationRegistry.getInstrumentation(); val context=instrumentation.targetContext
        assertFalse(CallRuntime.state.value.serviceRunning)
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var engine:AndroidTtsEngine?=null
        fun until(label:String,predicate:()->Boolean) { val deadline=System.currentTimeMillis()+15000; while(!predicate()) { check(System.currentTimeMillis()<deadline) { "$label: ${SpeechRuntime.state.value.ttsStatus}" }; Thread.sleep(50) } }
        try {
            instrumentation.runOnMainSync { engine=AndroidTtsEngine(context) { }; engine!!.configure(UserPreferences(spokenSummaries=true)) }
            until("Installed offline English voice ready") { SpeechRuntime.state.value.ttsStatus=="ready" || SpeechRuntime.state.value.error.isNotEmpty() }
            assertEquals("Offline English voice required", "ready",SpeechRuntime.state.value.ttsStatus)
            instrumentation.runOnMainSync { assertTrue(engine!!.speak("Offline speech is ready.")) }
            until("Actual TTS started") { SpeechRuntime.state.value.speaking }
            until("Actual TTS completed") { !SpeechRuntime.state.value.speaking && SpeechRuntime.state.value.playback=="completed" }
            val state=SpeechRuntime.state.value
            instrumentation.runOnMainSync { assertTrue(engine!!.speak("This is a short interruption check. The user can stop spoken output and return to listening.")) }
            until("Second utterance started") { SpeechRuntime.state.value.speaking }
            instrumentation.runOnMainSync { engine!!.stop() }
            until("Stop cancels speech") { !SpeechRuntime.state.value.speaking && SpeechRuntime.state.value.playback=="interrupted" }
            assertFalse(CallRuntime.state.value.capturing)
            File(context.noBackupFilesDir,"device-verification").apply{mkdirs()}.resolve("tts-probe.json").writeText(Gson().toJson(mapOf("engine" to state.ttsEngine,"voice" to state.voice,"initializationMs" to state.initializationMs,"speechStartMs" to state.speechStartMs,"offlineVoiceChecked" to true,"stopVerified" to true)))
        } finally { instrumentation.runOnMainSync { engine?.close(); activity.finish() }; SpeechRuntime.clear() }
    }
}
