package com.codexvoice.app.core.calls

import android.app.*
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.codexvoice.app.MainActivity
import com.codexvoice.app.R
import com.codexvoice.app.core.conversation.ConversationRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One silent, required foreground indicator shared by Telecom and microphone services. */
object CallNotifications {
    const val ID = 707
    const val QUIET_CHANNEL = "codex_audio_session"
    private val visibleActivities = mutableSetOf<Activity>()
    private val mutableVisible = MutableStateFlow(false)
    val visible = mutableVisible.asStateFlow()
    @Synchronized fun activityStarted(activity: Activity) { visibleActivities.add(activity); mutableVisible.value = true }
    @Synchronized fun activityStopped(activity: Activity) { visibleActivities.remove(activity); mutableVisible.value = visibleActivities.isNotEmpty() }
    fun quietChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(QUIET_CHANNEL, "Active call", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null); enableVibration(false); setShowBadge(false)
            })
    }
    fun ongoing(context: Context, end: PendingIntent, identity: String? = null): Notification {
        quietChannel(context)
        val name = identity ?: ConversationRuntime.state.value.selection?.label ?: "Codex"
        val open = PendingIntent.getActivity(context, ID, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(context, QUIET_CHANNEL).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(name).setContentText("Call in progress").setContentIntent(open)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(Person.Builder().setName(name).build(), end))
            .setCategory(NotificationCompat.CATEGORY_CALL).setOngoing(true).setOnlyAlertOnce(true)
            .setSilent(true).setPriority(NotificationCompat.PRIORITY_LOW).setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
    }
}
