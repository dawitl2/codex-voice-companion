package com.codexvoice.app

import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import androidx.test.platform.app.InstrumentationRegistry
import com.codexvoice.app.core.calls.CallRuntime
import com.codexvoice.app.core.calls.IncomingCallRinger
import com.codexvoice.app.core.notifications.TaskNotifications
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit two-second local ringtone diagnostic; no pairing, FCM, capture or PC tone. */
class IncomingRingtoneTest {
    @Test fun selectedSystemRingtonePlaysAndStopsWithoutMicrophone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Respect notification block", TaskNotifications.permission(context))
        assumeTrue("Respect silent mode", context.getSystemService(AudioManager::class.java).ringerMode == AudioManager.RINGER_MODE_NORMAL)
        assumeTrue("Respect Do Not Disturb", context.getSystemService(NotificationManager::class.java).currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL)
        assumeTrue("Respect silent ringtone selection", RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE) != null)
        assertFalse(CallRuntime.state.value.serviceRunning)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val ringtone = IncomingCallRinger(context, System.currentTimeMillis() + 2000)
        try {
            instrumentation.runOnMainSync { ringtone.start(true) }
            Thread.sleep(300)
            assertTrue("System ringtone playback started", ringtone.playing)
            assertFalse(CallRuntime.state.value.capturing)
            Thread.sleep(2100)
            instrumentation.runOnMainSync { }
            assertFalse("Bounded ringtone stopped", ringtone.playing)
        } finally {
            instrumentation.runOnMainSync { ringtone.close(); activity.finish() }
        }
    }
}
