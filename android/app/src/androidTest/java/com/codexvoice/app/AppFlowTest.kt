package com.codexvoice.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.codexvoice.app.core.data.PreferencesRepository
import com.codexvoice.app.core.model.*
import com.codexvoice.app.core.navigation.CodexVoiceApp
import com.codexvoice.app.feature.dashboard.DashboardViewModel
import com.codexvoice.app.feature.demo.DemoTaskRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test

private class TestPreferences : PreferencesRepository {
    private val state = MutableStateFlow(UserPreferences(reduceMotion = true))
    override val preferences = state.asStateFlow()
    override suspend fun setTheme(mode: ThemeMode) { state.value = state.value.copy(theme = mode) }
    override suspend fun setCalling(completion: Boolean, failure: Boolean, approval: Boolean) {
        state.value = state.value.copy(callOnCompletion = completion, callOnFailure = failure, callOnApproval = approval)
    }
    override suspend fun setReduceMotion(value: Boolean) { state.value = state.value.copy(reduceMotion = value) }
}
class AppFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun render() {
        val vm = DashboardViewModel(DemoTaskRepository(), TestPreferences())
        compose.setContent { CodexVoiceApp(vm) }
    }
    private fun preview(label: String = "Preview completed-task call") {
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.onNodeWithText("Codex Voice is calling").assertIsDisplayed()
    }
    @Test fun dashboardAndNavigationRender() {
        compose.onNodeWithText("PC bridge is not connected").assertIsDisplayed()
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Task history").assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Dark").assertIsDisplayed()
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("Codex Voice").assertIsDisplayed()
    }
    @Test fun answerMuteAndHangUp() {
        preview()
        compose.onNodeWithContentDescription("Answer").performScrollTo().performClick()
        compose.onNodeWithText("SAMPLE TRANSCRIPT").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Mute demo").performClick()
        compose.onNodeWithText("Demo muted").assertIsDisplayed()
        compose.onNodeWithText("Hang up").performClick()
        compose.onNodeWithText("PC bridge is not connected").assertIsDisplayed()
    }
    @Test fun declineReturnsHome() {
        preview()
        compose.onNodeWithContentDescription("Decline").performScrollTo().performClick()
        compose.onNodeWithText("PC bridge is not connected").assertIsDisplayed()
    }
    @Test fun failedCallShowsFailureReason() {
        preview("Preview failed-task call")
        compose.onNodeWithText("Task failed").assertIsDisplayed()
        compose.onNodeWithContentDescription("Decline").performScrollTo().performClick()
    }
    @Test fun themeAndCallingSettingsWork() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Light").performClick()
        compose.onNodeWithText("Light").assertIsDisplayed()
        compose.onNodeWithContentDescription("Task failure").performScrollTo().assertIsOn().performClick().assertIsOff()
    }
    @Test fun historyFilterAndEmptyStateWork() {
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Failures").performClick()
        compose.onNodeWithText("Run bridge checks").assertIsDisplayed()
        compose.onNodeWithText("Clear sample history").performScrollTo().performClick()
        compose.onNodeWithText("No tasks here").assertIsDisplayed()
        compose.onNodeWithText("Reset demo").performScrollTo().performClick()
        compose.onNodeWithText("Run bridge checks").assertExists()
    }
}
