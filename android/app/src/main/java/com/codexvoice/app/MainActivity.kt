package com.codexvoice.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codexvoice.app.core.data.DataStorePreferencesRepository
import com.codexvoice.app.core.navigation.CodexVoiceApp
import com.codexvoice.app.feature.dashboard.DashboardViewModel
import com.codexvoice.app.core.data.CompanionTaskRepository
import com.codexvoice.app.core.remote.*
import kotlinx.coroutines.*
import com.codexvoice.app.core.notifications.NotificationController
import com.codexvoice.app.core.notifications.TaskNotifications
import com.codexvoice.app.core.calls.LiveCallController
import android.content.Intent

internal val android.content.Context.preferencesStore by preferencesDataStore(name = "user_preferences")

class MainActivity : ComponentActivity() {
    private var dashboardModel: DashboardViewModel? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == DashboardViewModel::class.java)
                val remoteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val connectionPrefs = applicationContext.getSharedPreferences("connection-mode", MODE_PRIVATE)
                val client = RemoteClient(remoteScope, OkHttpSocketFactory(), KeystoreCredentialStore(applicationContext))
                val repository = CompanionTaskRepository(client, remoteScope, connectionPrefs.getBoolean("demo", false),
                    { value -> if (!connectionPrefs.edit().putBoolean("demo", value).commit()) throw java.io.IOException("Could not save mode") })
                val preferences = DataStorePreferencesRepository(applicationContext.preferencesStore)
                return DashboardViewModel(repository, preferences, notifications = NotificationController(applicationContext, client, preferences, remoteScope),
                    liveCalls = LiveCallController(applicationContext, client, preferences, remoteScope)) as T
            }
        }
        setContent {
            val dashboard: DashboardViewModel = viewModel(factory = factory)
            dashboardModel = dashboard
            androidx.compose.runtime.LaunchedEffect(dashboard) { intent.getStringExtra(TaskNotifications.EXTRA_EVENT)?.let(dashboard::openNotification) }
            CodexVoiceApp(dashboard)
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); intent.getStringExtra(TaskNotifications.EXTRA_EVENT)?.let { dashboardModel?.openNotification(it) } }
}
