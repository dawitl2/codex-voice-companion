package com.codexvoice.app.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codexvoice.app.core.data.*
import com.codexvoice.app.core.model.*
import com.codexvoice.app.feature.call.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.IOException
import com.codexvoice.app.core.remote.ClientState
import com.codexvoice.app.core.notifications.*
import com.codexvoice.app.core.calls.*

data class DashboardState(
    val current: Task? = null, val history: List<Task> = emptyList(),
    val connection: ConnectionStatus = ConnectionStatus.DEMO,
    val remote: ClientState? = null, val demoMode: Boolean = true,
)
class DashboardViewModel(
    private val tasks: TaskRepository,
    private val settings: PreferencesRepository,
    private val calls: CallStateMachine = CallStateMachine(),
    private val notifications: NotificationController? = null,
    private val liveCalls: LiveCallController? = null,
) : ViewModel() {
    private val basicDashboard = combine(tasks.current, tasks.history, tasks.connection) { task, history, connection ->
        DashboardState(task, sortedHistory(history), connection)
    }
    val dashboard = (if (tasks is CompanionTaskRepository) tasks.data.map { data ->
        DashboardState(data.current, sortedHistory(data.history), data.connection, data.remote, data.demo)
    } else basicDashboard).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardState(demoMode = tasks !is CompanionTaskRepository))
    val preferences = settings.preferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())
    private val mutableCall = MutableStateFlow(calls.snapshot)
    val call = mutableCall.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    val notificationState = notifications?.state ?: MutableStateFlow(NotificationUiState()).asStateFlow()
    val liveCallState = liveCalls?.state ?: MutableStateFlow(LiveCallState()).asStateFlow()
    val callSettingsStatus = liveCalls?.status ?: MutableStateFlow("Call invitations disabled").asStateFlow()
    fun setCallInvitations(value: Boolean) = save { settings.setCallInvitations(value) }
    fun answerLiveCall(permitted: Boolean) { liveCalls?.answer(permitted) }
    fun declineLiveCall() { liveCalls?.decline() }
    fun endLiveCall() { liveCalls?.end() }
    fun muteLiveCall() { liveCalls?.mute() }
    fun speakerLiveCall() { liveCalls?.speaker() }
    fun testLiveAudio() { liveCalls?.tone() }
    fun dismissLiveCall() { liveCalls?.dismiss(); notifications?.dismiss() }
    fun liveCallResults() { liveCalls?.results(); notifications?.viewResults() }
    fun setNotifications(value: UserPreferences) = save { settings.setNotifications(value) }
    fun openNotification(id: String) { notifications?.open(id); liveCalls?.open(id) }
    fun viewNotificationResults() { notifications?.viewResults() }
    fun dismissNotification() { notifications?.dismiss() }
    fun notificationSystemChanged() { notifications?.systemStateChanged() }
    fun previewCall(reason: CallReason) {
        if (tasks is CompanionTaskRepository && !tasks.isDemo.value) return
        if (calls.snapshot.state != CallState.IDLE) return
        val task = tasks.completeDemoTask(reason)
        calls.incoming(task, reason)
        mutableCall.value = calls.snapshot
    }
    fun answer() { calls.answer(); mutableCall.value = calls.snapshot }
    fun decline() { calls.decline(); calls.dismiss(); mutableCall.value = calls.snapshot }
    fun hangUp() { calls.hangUp(); calls.dismiss(); mutableCall.value = calls.snapshot }
    fun toggleMute() { calls.toggleMute(); mutableCall.value = calls.snapshot }
    fun clearHistory() = tasks.clearHistory()
    fun resetDemo() = tasks.resetDemo()
    fun selectDemo(value: Boolean) = save { (tasks as? CompanionTaskRepository)?.selectDemo(value) }
    fun foreground(active: Boolean) { (tasks as? CompanionTaskRepository)?.foreground(active); liveCalls?.foreground(active) }
    fun pair(address: String, code: String) { (tasks as? CompanionTaskRepository)?.pair(address, code) }
    fun connectPc() { (tasks as? CompanionTaskRepository)?.connect() }
    fun disconnectPc() { (tasks as? CompanionTaskRepository)?.disconnect() }
    fun forgetPc() { (tasks as? CompanionTaskRepository)?.forget() }
    fun refreshPc() { (tasks as? CompanionTaskRepository)?.refresh() }
    override fun onCleared() { (tasks as? CompanionTaskRepository)?.close(); super.onCleared() }
    fun setTheme(mode: ThemeMode) = save { settings.setTheme(mode) }
    fun setCalling(completion: Boolean, failure: Boolean, approval: Boolean) =
        save { settings.setCalling(completion, failure, approval) }
    fun setReduceMotion(value: Boolean) = save { settings.setReduceMotion(value) }
    fun dismissError() { mutableError.value = null }
    private fun save(action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action() } catch (_: IOException) { mutableError.value = "Could not save preferences. Please try again." }
        }
    }
}

