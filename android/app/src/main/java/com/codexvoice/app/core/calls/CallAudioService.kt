package com.codexvoice.app.core.calls

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.codexvoice.app.MainActivity
import com.codexvoice.app.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import com.codexvoice.app.core.speech.*
import com.codexvoice.app.core.model.UserPreferences
import com.codexvoice.app.core.data.DataStorePreferencesRepository
import com.codexvoice.app.preferencesStore

/** Started only from a visible, permission-granted Answer action; never sticky or FCM-started. */
class CallAudioService : Service() {
    companion object {
        const val CONNECT = "com.codexvoice.app.audio.CONNECT"; const val END = "com.codexvoice.app.audio.END"
        const val MUTE = "com.codexvoice.app.audio.MUTE"; const val SPEAKER = "com.codexvoice.app.audio.SPEAKER"; const val TONE = "com.codexvoice.app.audio.TONE"
        const val SPEAK = "com.codexvoice.app.audio.SPEAK"; const val STOP_SPEECH = "com.codexvoice.app.audio.STOP_SPEECH"
        private const val NOTIFICATION = 606
    }
    private var engine: NativeAudioEngine? = null
    private var synthesizer: AndroidTtsEngine? = null
    private var speechPreferences = UserPreferences()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recovery: Job? = null
    private var wake: PowerManager.WakeLock? = null
    override fun onCreate() {
        super.onCreate()
        scope.launch { DataStorePreferencesRepository(applicationContext.preferencesStore).preferences.collect { speechPreferences=it; synthesizer?.configure(it) } }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            CONNECT -> {
                val grant = CallRuntime.consume(); if (grant == null || engine != null) { if (engine == null) stopSelf(); return START_NOT_STICKY }
                try {
                    recovery?.cancel(); ongoing()
                    wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CodexVoice:ActiveAudio").apply { acquire(1800000) }
                    CallRuntime.update { it.copy(phase = LivePhase.CONNECTING, offer = grant.call, serviceRunning = true, capturing = false, playing = false) }
                    if (SpeechRuntime.state.value.callId != grant.call.callId) SpeechRuntime.begin(grant.call.callId)
                    synthesizer = AndroidTtsEngine(applicationContext) { outcome -> scope.launch {
                        delay(150)
                        engine?.pauseForSpeech(false)
                        SpeechRuntime.update { it.copy(speaking=false,playback=outcome) }
                    } }.also { it.configure(speechPreferences) }
                    engine = NativeAudioEngine(applicationContext, grant) { reason, recoverable -> scope.launch { ended(reason, recoverable) } }
                    engine!!.connect()
                } catch (_: Exception) { ended("Foreground audio session could not start", false) }
            }
            END -> { recovery?.cancel(); synthesizer?.close(); synthesizer=null; SpeechRuntime.clear(); engine?.end() ?: ended("Ended by you", false) }
            MUTE -> engine?.mute(!CallRuntime.state.value.muted)
            SPEAKER -> engine?.speaker(!CallRuntime.state.value.speaker)
            TONE -> { synthesizer?.stop(); engine?.tone() }
            STOP_SPEECH -> synthesizer?.stop()
            SPEAK -> if (CallRuntime.state.value.phase==LivePhase.ACTIVE && SpeechRuntime.state.value.callId==CallRuntime.state.value.offer?.callId && speechPreferences.spokenSummaries) scope.launch {
                if (engine?.pauseForSpeech(true)==true && synthesizer?.speak(SpeechRuntime.state.value.summary)!=true) {
                    engine?.pauseForSpeech(false)
                    SpeechRuntime.update { it.copy(speaking=false,playback="error",error="offline_voice_unavailable") }
                }
            }
            else -> if (engine == null) stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun ongoing() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("codex_audio_session", "Active audio session", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        val open = PendingIntent.getActivity(this, 606, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val end = PendingIntent.getService(this, 607, Intent(this, CallAudioService::class.java).setAction(END), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, "codex_audio_session").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Codex Voice · Audio session").setContentText("Private audio · Local speech only")
            .setContentIntent(open).setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).addAction(0, "End call", end).build()
        startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }
    private fun ended(reason: String, recoverable: Boolean) {
        synthesizer?.close(); synthesizer=null; SpeechRuntime.clear()
        engine = null; wake?.let { if (it.isHeld) it.release() }; wake = null
        CallRuntime.update { it.copy(phase = if (recoverable) LivePhase.RECONNECTING else if (reason.startsWith("Ended") || reason.startsWith("Call ended")) LivePhase.ENDED else LivePhase.FAILED,
            capturing = false, playing = false, inputLevel = 0f, outputLevel = 0f, problem = reason, endedAt = if (recoverable) 0 else SystemClock.elapsedRealtime()) }
        if (recoverable) recovery = scope.launch { delay(15000); if (engine == null) ended("Reconnection unavailable; call ended", false) }
        else { recovery?.cancel(); CallRuntime.update { it.copy(serviceRunning = false) }; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }
    override fun onTaskRemoved(rootIntent: Intent?) { engine?.end(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        synthesizer?.close(); synthesizer=null; SpeechRuntime.clear()
        recovery?.cancel(); engine?.destroy(); engine = null
        wake?.let { if (it.isHeld) it.release() }; wake = null
        CallRuntime.update { it.copy(serviceRunning = false, capturing = false, playing = false,
            phase = if (it.phase in setOf(LivePhase.CONNECTING, LivePhase.ACTIVE, LivePhase.RECONNECTING)) LivePhase.FAILED else it.phase,
            problem = if (it.phase in setOf(LivePhase.CONNECTING, LivePhase.ACTIVE, LivePhase.RECONNECTING)) "Audio service stopped" else it.problem) }
        scope.cancel(); super.onDestroy()
    }
}
