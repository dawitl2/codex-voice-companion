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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.*
import com.codexvoice.app.core.designsystem.CodexVoiceTheme
import com.codexvoice.app.feature.call.*
import com.codexvoice.app.feature.dashboard.*
import com.codexvoice.app.feature.history.HistoryScreen
import com.codexvoice.app.feature.settings.SettingsScreen
import com.codexvoice.app.core.notifications.*
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.os.Build

private data class Destination(val route: String, val title: String, val icon: ImageVector)
private val destinations = listOf(Destination("home", "Home", Icons.Default.Home),
    Destination("history", "History", Icons.Default.History), Destination("settings", "Settings", Icons.Default.Settings))
@Composable
fun CodexVoiceApp(viewModel: DashboardViewModel) {
    val dashboard by viewModel.dashboard.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val call by viewModel.call.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val notification by viewModel.notificationState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingEnable by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (pendingEnable) viewModel.setNotifications(prefs.copy(notificationsEnabled = true))
        pendingEnable = false; viewModel.notificationSystemChanged()
    }
    val changeNotifications: (com.codexvoice.app.core.model.UserPreferences) -> Unit = { value ->
        val asked = context.getSharedPreferences("notification-permission", android.content.Context.MODE_PRIVATE)
        if (value.notificationsEnabled && !prefs.notificationsEnabled && Build.VERSION.SDK_INT >= 33 && !TaskNotifications.permission(context) && !asked.getBoolean("asked", false)) {
            if (asked.edit().putBoolean("asked", true).commit()) { pendingEnable = true; permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        } else viewModel.setNotifications(value)
    }
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val reduceMotion = prefs.reduceMotion || !ValueAnimator.areAnimatorsEnabled()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel, dashboard.demoMode) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) { viewModel.foreground(true); viewModel.notificationSystemChanged() }
            if (event == Lifecycle.Event.ON_STOP) viewModel.foreground(false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.foreground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); viewModel.foreground(false) }
    }
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
        if (notification.alert != null) {
            BackHandler { viewModel.dismissNotification() }
            TaskAlertScreen(notification, dashboard.remote, viewModel::viewNotificationResults, viewModel::dismissNotification, viewModel::connectPc)
        } else when (call.state) {
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
                    composable("home") { DashboardScreen(dashboard, reduceMotion, viewModel::previewCall, { navigate("history") }, { navigate("settings") }) }
                    composable("history") { HistoryScreen(dashboard.history, reduceMotion, viewModel::clearHistory, viewModel::resetDemo, dashboard.demoMode, dashboard.connection, dashboard.remote?.snapshot?.mode == "mock") }
                    composable("settings") { SettingsScreen(prefs, viewModel::setTheme, viewModel::setCalling, viewModel::setReduceMotion,
                        dashboard.remote, dashboard.demoMode, viewModel::selectDemo, viewModel::pair, viewModel::connectPc, viewModel::disconnectPc, viewModel::forgetPc,
                        notification, changeNotifications, { context.startActivity(TaskNotifications.systemSettings(context)) },
                        { attention -> context.startActivity(TaskNotifications.channelsSettings(context, attention, prefs.pushPreferences())) }) }
                }
            }
        }
        if (error != null) AlertDialog(onDismissRequest = viewModel::dismissError, title = { Text("Preference not saved") },
            text = { Text(error!!) }, confirmButton = { TextButton(viewModel::dismissError) { Text("OK") } })
    }
}

