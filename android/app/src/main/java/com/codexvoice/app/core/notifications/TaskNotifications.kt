package com.codexvoice.app.core.notifications

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.codexvoice.app.MainActivity
import com.codexvoice.app.R
import com.codexvoice.app.core.remote.KeystoreCredentialStore

object TaskNotifications {
    const val EXTRA_EVENT = "codex_voice_notification_event"
    private fun family(event: PushEvent) = if (event.kind in setOf("failed", "approval")) "attention" else "tasks"
    fun channelId(event: PushEvent, preferences: PushPreferences) = "codex_${family(event)}_${if (preferences.sound) 1 else 0}${if (preferences.vibration) 1 else 0}"
    fun id(eventId: String) = eventId.take(8).toLong(16).toInt()
    fun permission(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun systemSettings(context: Context) = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    fun channelsSettings(context: Context, attention: Boolean, preferences: PushPreferences): Intent {
        val manager = context.getSystemService(NotificationManager::class.java)
        val family = if (attention) "attention" else "tasks"
        val prefix = "codex_${family}_"
        val blocked = manager.notificationChannels.firstOrNull { it.id.startsWith(prefix) && it.importance == NotificationManager.IMPORTANCE_NONE }
        val current = manager.getNotificationChannel(prefix + "${if (preferences.sound) 1 else 0}${if (preferences.vibration) 1 else 0}")
        val channel = blocked ?: current ?: return systemSettings(context)
        return Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, channel.id)
    }
    fun post(context: Context, event: PushEvent, preferences: PushPreferences): Boolean {
        if (!permission(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        val family = family(event); val group = "codex_$family"
        manager.createNotificationChannelGroup(NotificationChannelGroup(group, if (family == "attention") "Attention required" else "Task outcomes"))
        // Do not circumvent a system block by choosing another sound/vibration variant.
        if (manager.getNotificationChannelGroup(group)?.isBlocked == true || manager.notificationChannels.any { it.id.startsWith("codex_${family}_") && it.importance == NotificationManager.IMPORTANCE_NONE }) return false
        val channelId = channelId(event, preferences)
        if (manager.getNotificationChannel(channelId) == null) {
            val behavior = if (preferences.sound) "Sound" else "Silent"
            val channel = NotificationChannel(channelId, "${if (family == "attention") "Attention" else "Task outcomes"} · $behavior${if (preferences.vibration) ", vibration" else ""}", NotificationManager.IMPORTANCE_HIGH)
            channel.group = group; channel.description = "Codex task alerts. Android controls sound, vibration and Do Not Disturb."
            channel.setSound(if (preferences.sound) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) else null,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            channel.enableVibration(preferences.vibration); if (preferences.vibration) channel.vibrationPattern = longArrayOf(0, 180, 100, 180)
            channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            manager.createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(context, id(event.eventId), Intent(context, MainActivity::class.java).putExtra(EXTRA_EVENT, event.eventId)
            .setData(android.net.Uri.parse("codexvoice-alert://event/${event.eventId}"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismiss = PendingIntent.getBroadcast(context, id(event.eventId), Intent(context, DismissNotificationReceiver::class.java).putExtra(EXTRA_EVENT, event.eventId)
            .setData(android.net.Uri.parse("codexvoice-alert://event/${event.eventId}")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val simulated = if (event.mode == "mock") "Simulated · " else ""
        val body = when (event.kind) {
            "completed" -> "A Codex turn completed. Open to inspect its outcome."
            "failed" -> "A Codex turn failed. Open to inspect its current state."
            "interrupted" -> "A Codex turn was interrupted."
            else -> "Approval needs attention on the PC. It may expire before you open this alert."
        }
        val notification = NotificationCompat.Builder(context, channelId).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("$simulated${event.title}").setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSubText("Codex Voice · Task alert").setContentIntent(open).setDeleteIntent(dismiss).addAction(0, "Dismiss", dismiss)
            .setAutoCancel(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_EVENT).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setWhen(java.time.Instant.parse(event.occurredAt).toEpochMilli()).setTimeoutAfter((event.expires - System.currentTimeMillis()).coerceAtLeast(1))
            .setPriority(NotificationCompat.PRIORITY_HIGH).build()
        try { manager.notify(event.eventId, id(event.eventId), notification); return true } catch (_: SecurityException) { return false }
    }
    fun cancel(context: Context, eventId: String) { context.getSystemService(NotificationManager::class.java).cancel(eventId, id(eventId)) }
}
class DismissNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        Thread {
            try {
                val id = intent.getStringExtra(TaskNotifications.EXTRA_EVENT) ?: return@Thread
                val store = PushStore.get(context); val event = store.state.value.events.find { it.eventId == id } ?: return@Thread
                if (PushParser.parse(event.data(), KeystoreCredentialStore(context).load(), System.currentTimeMillis(), allowExpired = true) == null) return@Thread
                TaskNotifications.cancel(context, id); store.receipt(id, "dismissed")
            } catch (_: Exception) { /* No credential-bearing diagnostics. */ } finally { result.finish() }
        }.start()
    }
}
