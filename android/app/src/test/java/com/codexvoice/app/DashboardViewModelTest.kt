package com.codexvoice.app
import com.codexvoice.app.core.data.*
import com.codexvoice.app.core.model.*
import com.codexvoice.app.feature.dashboard.DashboardViewModel
import com.codexvoice.app.feature.demo.DemoTaskRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test

class MemoryPreferences : PreferencesRepository {
    private val state = MutableStateFlow(UserPreferences())
    override val preferences = state.asStateFlow()
    override suspend fun setTheme(mode: ThemeMode) { state.value = state.value.copy(theme = mode) }
    override suspend fun setCalling(completion: Boolean, failure: Boolean, approval: Boolean) {
        state.value = state.value.copy(callOnCompletion = completion, callOnFailure = failure, callOnApproval = approval)
    }
    override suspend fun setReduceMotion(value: Boolean) { state.value = state.value.copy(reduceMotion = value) }
}
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    @Test fun reflectsRepositoryAndClearHistory() = runTest {
        val vm = DashboardViewModel(DemoTaskRepository(), MemoryPreferences())
        backgroundScope.launch(dispatcher) { vm.dashboard.collect() }
        assertEquals(ConnectionStatus.DEMO, vm.dashboard.value.connection)
        assertEquals(3, vm.dashboard.value.history.size)
        vm.clearHistory(); assertTrue(vm.dashboard.value.history.isEmpty())
        vm.resetDemo(); assertEquals(3, vm.dashboard.value.history.size)
    }
    @Test fun previewAnswerMuteAndHangUpWork() = runTest {
        val vm = DashboardViewModel(DemoTaskRepository(), MemoryPreferences())
        vm.previewCall(CallReason.COMPLETION); assertEquals(com.codexvoice.app.feature.call.CallState.INCOMING, vm.call.value.state)
        vm.answer(); vm.toggleMute(); assertTrue(vm.call.value.session!!.demoMuted)
        vm.hangUp(); assertEquals(com.codexvoice.app.feature.call.CallState.IDLE, vm.call.value.state)
    }
    @Test fun duplicatePreviewDoesNotCreateTask() = runTest {
        val repo = DemoTaskRepository(); val vm = DashboardViewModel(repo, MemoryPreferences())
        vm.previewCall(CallReason.FAILURE); val count = repo.history.value.size
        vm.previewCall(CallReason.COMPLETION); assertEquals(count, repo.history.value.size)
        assertEquals(CallReason.FAILURE, vm.call.value.session!!.reason)
    }
    @Test fun declineReturnsIdleWithoutAnswer() = runTest {
        val vm = DashboardViewModel(DemoTaskRepository(), MemoryPreferences())
        vm.previewCall(CallReason.FAILURE); vm.decline()
        assertNull(vm.call.value.session)
    }
    @Test fun settingsActionsUpdateObservablePreferences() = runTest {
        val vm = DashboardViewModel(DemoTaskRepository(), MemoryPreferences())
        backgroundScope.launch(dispatcher) { vm.preferences.collect() }
        vm.setTheme(ThemeMode.LIGHT); vm.setCalling(false, true, false); vm.setReduceMotion(true)
        assertEquals(UserPreferences(ThemeMode.LIGHT, false, true, false, true), vm.preferences.value)
    }
    @Test fun invalidCallActionsRemainIdle() = runTest {
        val vm = DashboardViewModel(DemoTaskRepository(), MemoryPreferences())
        vm.answer(); vm.toggleMute(); vm.hangUp(); vm.decline()
        assertEquals(com.codexvoice.app.feature.call.CallSnapshot(), vm.call.value)
    }
}

