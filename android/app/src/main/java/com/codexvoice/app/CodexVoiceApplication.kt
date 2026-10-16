package com.codexvoice.app

import android.app.Application
import com.codexvoice.app.core.notifications.LocalFirebaseConfiguration

class CodexVoiceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LocalFirebaseConfiguration.initialize(this)
    }
}
