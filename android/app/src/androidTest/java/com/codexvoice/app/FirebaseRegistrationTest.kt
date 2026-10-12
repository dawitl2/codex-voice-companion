package com.codexvoice.app

import androidx.test.platform.app.InstrumentationRegistry
import com.codexvoice.app.core.notifications.PushStore
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual Android FCM/FID registration. This does not send a push or prove delivery. */
class FirebaseRegistrationTest {
    @Test fun currentSdkRegistersPhysicalInstallationThroughOnRegistered() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Explicit configured Firebase check required", InstrumentationRegistry.getArguments().getString("firebaseRegistration") == "true")
        val app = FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context)
        assumeTrue("Matching local client configuration required", app != null)
        val messaging = FirebaseMessaging.getInstance(); val originalAutoInit = messaging.isAutoInitEnabled
        val done = CountDownLatch(1); var success = false
        try {
            messaging.isAutoInitEnabled = true
            messaging.register().addOnCompleteListener { success = it.isSuccessful; done.countDown() }
            assertTrue("Firebase registration callback deadline", done.await(45, TimeUnit.SECONDS)); assertTrue("Firebase registration must succeed", success)
            val store = PushStore.get(context); val deadline = System.currentTimeMillis() + 15000
            while (store.state.value.fid == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue("onRegistered must persist a supported FID", store.state.value.fid?.matches(Regex("[cdef][A-Za-z0-9_-]{21}")) == true)
            assertFalse("Notifications remain explicitly controlled", store.state.value.preferences.enabled)
        } finally { messaging.isAutoInitEnabled = originalAutoInit }
    }
}
