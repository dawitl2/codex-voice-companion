package com.codexvoice.app.core.calls

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import com.codexvoice.app.core.notifications.TaskNotifications

/** Local incoming alert only while the offered-call Activity is resumed. Never opens a microphone. */
class IncomingCallRinger(private val context: Context, private val expiresAt: Long) : Closeable {
    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var dismissed = false
    private var ringDeadline = 0L
    private val timeout = Runnable { close() }
    val playing: Boolean get() = ringtone?.isPlaying == true
    fun start(soundEnabled: Boolean) {
        val now = System.currentTimeMillis()
        if (dismissed || playing || !soundEnabled || expiresAt <= now || (ringDeadline > 0 && now >= ringDeadline)) return
        val audio = context.getSystemService(AudioManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (!TaskNotifications.permission(context) || audio.ringerMode != AudioManager.RINGER_MODE_NORMAL || notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL ||
            notifications.notificationChannels.any { it.id.startsWith("codex_") && it.importance == NotificationManager.IMPORTANCE_NONE } ||
            notifications.notificationChannelGroups.any { it.id.startsWith("codex_") && it.isBlocked }) return
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE) ?: return
            ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                volume = .35f; isLooping = true; play()
            }
            if (ringDeadline == 0L) ringDeadline = minOf(expiresAt, now + 30000)
            handler.postDelayed(timeout, (ringDeadline - System.currentTimeMillis()).coerceAtLeast(1))
        } catch (_: Exception) { pause() }
    }
    fun pause() { ringtone?.stop(); ringtone = null; handler.removeCallbacks(timeout) }
    override fun close() { dismissed = true; pause() }
}
