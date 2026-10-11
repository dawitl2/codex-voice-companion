package com.codexvoice.app.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codexvoice.app.core.data.*
import com.codexvoice.app.core.model.*
import com.codexvoice.app.feature.call.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.IOException

data class DashboardState(
    val current: Task? = null, val history: List<Task> = emptyList(),
    val connection: ConnectionStatus = ConnectionStatus.DEMO,
)
class DashboardViewModel(
    private val tasks: TaskRepository,
    private val settings: PreferencesRepository,
    private val calls: CallStateMachine = CallStateMachine(),
) : ViewModel() {
    val dashboard = combine(tasks.current, tasks.history, tasks.connection) { task, history, connection ->
        DashboardState(task, sortedHistory(history), connection)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardState())
    val preferences = settings.preferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())
    private val mutableCall = MutableStateFlow(calls.snapshot)
    val call = mutableCall.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    fun previewCall(reason: CallReason) {
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

