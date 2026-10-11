package com.codexvoice.app.core.navigation
import android.animation.ValueAnimator
import android.app.Activity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import com.codexvoice.app.core.designsystem.CodexVoiceTheme
import com.codexvoice.app.feature.call.*
import com.codexvoice.app.feature.dashboard.*
import com.codexvoice.app.feature.history.HistoryScreen
import com.codexvoice.app.feature.settings.SettingsScreen

private data class Destination(val route: String, val title: String, val icon: ImageVector)
private val destinations = listOf(Destination("home", "Home", Icons.Default.Home),
    Destination("history", "History", Icons.Default.History), Destination("settings", "Settings", Icons.Default.Settings))
@Composable
fun CodexVoiceApp(viewModel: DashboardViewModel) {
    val dashboard by viewModel.dashboard.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val call by viewModel.call.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val reduceMotion = prefs.reduceMotion || !ValueAnimator.areAnimatorsEnabled()
    val navigate: (String) -> Unit = { route -> nav.navigate(route) {
        popUpTo(nav.graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    } }
    CodexVoiceTheme(prefs.theme) {
        val view = LocalView.current
        val lightBars = MaterialTheme.colorScheme.background.luminance() > .5f
        SideEffect {
            (view.context as? Activity)?.let { activity ->
                WindowCompat.getInsetsController(activity.window, view).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }
        when (call.state) {
            CallState.INCOMING -> {
                BackHandler { viewModel.decline() }
                IncomingCallScreen(call.session!!, reduceMotion, viewModel::answer, viewModel::decline)
            }
            CallState.ACTIVE -> {
                BackHandler { viewModel.hangUp() }
                ActiveCallScreen(call.session!!, viewModel::toggleMute, viewModel::hangUp)
            }
            else -> Scaffold(bottomBar = {
                NavigationBar {
                    destinations.forEach { d ->
                        NavigationBarItem((backStack?.destination?.route ?: "home") == d.route, { navigate(d.route) },
                            icon = { Icon(d.icon, null) }, label = { Text(d.title) })
                    }
                }
            }) { padding ->
                NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding),
                    enterTransition = { fadeIn(tween(if (reduceMotion) 0 else 180)) },
                    exitTransition = { fadeOut(tween(if (reduceMotion) 0 else 120)) }) {
                    composable("home") { DashboardScreen(dashboard, reduceMotion, viewModel::previewCall) { navigate("history") } }
                    composable("history") { HistoryScreen(dashboard.history, reduceMotion, viewModel::clearHistory, viewModel::resetDemo) }
                    composable("settings") { SettingsScreen(prefs, viewModel::setTheme, viewModel::setCalling, viewModel::setReduceMotion) }
                }
            }
        }
        if (error != null) AlertDialog(onDismissRequest = viewModel::dismissError, title = { Text("Preference not saved") },
            text = { Text(error!!) }, confirmButton = { TextButton(viewModel::dismissError) { Text("OK") } })
    }
}

