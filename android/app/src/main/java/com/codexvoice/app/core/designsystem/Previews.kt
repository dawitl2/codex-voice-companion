package com.codexvoice.app.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.codexvoice.app.core.model.ThemeMode
import com.codexvoice.app.feature.dashboard.DashboardScreen
import com.codexvoice.app.feature.dashboard.DashboardState
import com.codexvoice.app.feature.demo.DemoTaskRepository

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun DarkDashboardPreview() = DashboardPreview(ThemeMode.DARK)

@Preview(showBackground = true, widthDp = 360, heightDp = 800, fontScale = 1.5f)
@Composable
private fun LargeTextDashboardPreview() = DashboardPreview(ThemeMode.LIGHT)

@Composable
private fun DashboardPreview(mode: ThemeMode) {
    val repository = DemoTaskRepository()
    CodexVoiceTheme(mode) {
        DashboardScreen(DashboardState(repository.current.value, repository.history.value), true, {}, {})
    }
}
