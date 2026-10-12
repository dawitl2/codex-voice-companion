package com.codexvoice.app

import android.content.Context
import com.codexvoice.app.core.calls.*
import com.codexvoice.app.core.control.TaskControlController
import com.codexvoice.app.core.data.*
import com.codexvoice.app.core.notifications.NotificationController
import com.codexvoice.app.core.remote.*
import com.codexvoice.app.core.speech.SpeechSessionController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Shared across Activity recreation; retained in background only by an answered service. */
internal class CompanionRuntime private constructor(context: Context) {
    companion object {
        private var instance: CompanionRuntime? = null
        @Synchronized fun acquire(context: Context): CompanionRuntime =
            (instance ?: CompanionRuntime(context.applicationContext).also { instance = it }).also { it.owners++ }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var owners = 0
    val client = RemoteClient(scope, OkHttpSocketFactory(), KeystoreCredentialStore(context))
    private val mode = context.getSharedPreferences("connection-mode", Context.MODE_PRIVATE)
    val repository = CompanionTaskRepository(client, scope, mode.getBoolean("demo", false),
        { value -> if (!mode.edit().putBoolean("demo", value).commit()) throw java.io.IOException("Could not save mode") })
    val preferences = DataStorePreferencesRepository(context.preferencesStore)
    val notifications = NotificationController(context, client, preferences, scope)
    val calls = LiveCallController(context, client, preferences, scope)
    val speech = SpeechSessionController(context, client, preferences, scope)
    val controls = TaskControlController(client, scope)
    init {
        scope.launch { CallRuntime.state.collect {
            client.activeCall(it.serviceRunning)
            if (!it.serviceRunning) closeIfUnowned()
        } }
    }
    fun release() {
        synchronized(CompanionRuntime::class.java) { owners = (owners - 1).coerceAtLeast(0) }
        // Rotation attaches the replacement before retiring the old process runtime.
        scope.launch { delay(500); closeIfUnowned() }
    }
    private fun closeIfUnowned() = synchronized(CompanionRuntime::class.java) {
        if (owners == 0 && !CallRuntime.state.value.serviceRunning && instance === this) {
            instance = null
            controls.close(); speech.close(); repository.close()
        }
    }
}
