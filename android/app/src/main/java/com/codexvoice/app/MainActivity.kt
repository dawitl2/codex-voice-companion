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
import com.codexvoice.app.feature.demo.DemoTaskRepository

private val android.content.Context.preferencesStore by preferencesDataStore(name = "user_preferences")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == DashboardViewModel::class.java)
                return DashboardViewModel(DemoTaskRepository(), DataStorePreferencesRepository(applicationContext.preferencesStore)) as T
            }
        }
        setContent {
            val dashboard: DashboardViewModel = viewModel(factory = factory)
            CodexVoiceApp(dashboard)
        }
    }
}
