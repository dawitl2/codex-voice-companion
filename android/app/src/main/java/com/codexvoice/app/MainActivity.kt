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

private val android.content.Context.preferencesStore by preferencesDataStore(name = "user_preferences")

class MainActivity : ComponentActivity() {
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
                return DashboardViewModel(repository, DataStorePreferencesRepository(applicationContext.preferencesStore)) as T
            }
        }
        setContent {
            val dashboard: DashboardViewModel = viewModel(factory = factory)
            CodexVoiceApp(dashboard)
        }
    }
}
