package com.codexvoice.app.core.speech

/** A transport acknowledgement is not evidence that native TTS started. */
class SpeechStartGate {
    var pending=false;private set
    private var baseline=0L
    private var requestedAt=0L
    fun request(previousNativeStart:Long,now:Long):Boolean {
        if(pending)return false
        baseline=previousNativeStart;requestedAt=now;pending=true;return true
    }
    fun observeNativeStart(nativeStart:Long):Boolean {
        if(!pending || nativeStart<=baseline)return false
        pending=false;return true
    }
    fun timedOut(now:Long)=pending && now-requestedAt>=10000
    fun cancel(){pending=false}
}
