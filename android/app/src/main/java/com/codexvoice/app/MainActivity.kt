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
import com.codexvoice.app.core.speech.SpeechSessionController
import android.content.Intent
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal val android.content.Context.preferencesStore by preferencesDataStore(name = "user_preferences")

class MainActivity : ComponentActivity() {
    private var dashboardModel: DashboardViewModel? = null
    private lateinit var runtime: CompanionRuntime
    private var incomingAnswer:String?=null
    private fun receiveCallIntent(value:Intent){
        val proof=com.codexvoice.app.core.calls.TelecomRuntime.consumeProof(value)
        if(proof!=null){setShowWhenLocked(true);setTurnScreenOn(true);if(proof.second){incomingAnswer=proof.first;dashboardModel?.incomingAnswer(proof.first)}}
        value.getStringExtra(TaskNotifications.EXTRA_EVENT)?.let{dashboardModel?.openNotification(it)}
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runtime = CompanionRuntime.acquire(applicationContext)
        receiveCallIntent(intent)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == DashboardViewModel::class.java)
                return DashboardViewModel(runtime.repository, runtime.preferences, notifications = runtime.notifications,
                    liveCalls = runtime.calls, speech = runtime.speech, controls = runtime.controls,
                    ownsRuntime = false,chat = runtime.chat) as T
            }
        }
        setContent {
            val dashboard: DashboardViewModel = viewModel(factory = factory)
            dashboardModel = dashboard
            androidx.compose.runtime.LaunchedEffect(dashboard) { intent.getStringExtra(TaskNotifications.EXTRA_EVENT)?.let(dashboard::openNotification);incomingAnswer?.let(dashboard::incomingAnswer);incomingAnswer=null }
            val live=runtime.calls.state.collectAsStateWithLifecycle().value
            androidx.compose.runtime.LaunchedEffect(live.phase,live.serviceRunning){if(live.serviceRunning||live.phase in setOf(com.codexvoice.app.core.calls.LivePhase.ENDED,com.codexvoice.app.core.calls.LivePhase.DECLINED,com.codexvoice.app.core.calls.LivePhase.EXPIRED,com.codexvoice.app.core.calls.LivePhase.FAILED)){setShowWhenLocked(false);setTurnScreenOn(false)}}
            CodexVoiceApp(dashboard)
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent);receiveCallIntent(intent) }
    override fun onStart() { super.onStart(); com.codexvoice.app.core.calls.CallNotifications.activityStarted(this) }
    override fun onStop() { com.codexvoice.app.core.calls.CallNotifications.activityStopped(this); super.onStop() }
    override fun onDestroy() { runtime.release(); super.onDestroy() }
}
