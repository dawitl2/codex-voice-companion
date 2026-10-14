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
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
import com.codexvoice.app.core.calls.LivePhase
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

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
    val liveCall by viewModel.liveCallState.collectAsStateWithLifecycle()
    val callSettingsStatus by viewModel.callSettingsStatus.collectAsStateWithLifecycle()
    val speechState by viewModel.speechState.collectAsStateWithLifecycle()
    val conversationState by viewModel.conversationState.collectAsStateWithLifecycle()
    val controlState by viewModel.controlState.collectAsStateWithLifecycle()
    val chatState by viewModel.chatState.collectAsStateWithLifecycle()
    val pendingAnswer by viewModel.pendingIncomingAnswer.collectAsStateWithLifecycle()
    var chatOpen by remember{mutableStateOf(false)}
    var outgoingThread by remember{mutableStateOf<String?>(null)}
    val openChat={val id=conversationState.selection?.threadId?:liveCall.offer?.threadId?:dashboard.remote?.snapshot?.selectedThreadId;if(id!=null)viewModel.openChat(id);chatOpen=true}
    var controlsOpen by remember{mutableStateOf(false)}
    LaunchedEffect(liveCall.phase){if(liveCall.phase==LivePhase.OFFERED){chatOpen=false;controlsOpen=false;viewModel.hideChat()}}
    val openControls={val id=conversationState.selection?.threadId?:dashboard.remote?.snapshot?.selectedThreadId?:dashboard.remote?.snapshot?.threads?.firstOrNull()?.id;if(id!=null){viewModel.openControls(id);controlsOpen=true}}
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
    val microphoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> val target=outgoingThread;outgoingThread=null;if(target!=null)viewModel.callCodex(target,granted)else viewModel.answerLiveCall(granted) }
    val answerAudio = {
        val permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val asked = context.getSharedPreferences("microphone-permission", android.content.Context.MODE_PRIVATE)
        if (!permitted && !asked.getBoolean("asked", false)) {
            if (asked.edit().putBoolean("asked", true).commit()) microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else viewModel.answerLiveCall(permitted)
    }
    val callCodex={
        val id=dashboard.remote?.snapshot?.selectedThreadId
        if(id==null){chatOpen=true}else{
            val permitted=ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
            val asked=context.getSharedPreferences("microphone-permission",android.content.Context.MODE_PRIVATE)
            if(!permitted&&!asked.getBoolean("asked",false)){if(asked.edit().putBoolean("asked",true).commit()){outgoingThread=id;microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)}}else viewModel.callCodex(id,permitted)
        }
    }
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val reduceMotion = prefs.reduceMotion || !ValueAnimator.areAnimatorsEnabled()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(pendingAnswer,liveCall.phase){if(pendingAnswer!=null&&liveCall.phase==LivePhase.OFFERED&&liveCall.offer?.eventId==pendingAnswer&&lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)){viewModel.consumeIncomingAnswer();answerAudio()}}
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
        if (liveCall.phase != LivePhase.IDLE) {
            BackHandler { if (liveCall.phase == LivePhase.OFFERED) viewModel.declineLiveCall() else if (liveCall.serviceRunning || liveCall.phase == LivePhase.CONNECTING) viewModel.endLiveCall() else viewModel.dismissLiveCall() }
            LiveCallScreen(liveCall, dashboard.remote, answerAudio, viewModel::declineLiveCall, viewModel::endLiveCall,
                viewModel::muteLiveCall, viewModel::speakerLiveCall, viewModel::testLiveAudio, viewModel::dismissLiveCall, viewModel::liveCallResults,
                { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))) },
                ringEnabled = prefs.notificationsEnabled && prefs.notificationSound, speech = speechState,
                onRepeat = viewModel::repeatSummary, onStopSpeech = viewModel::stopSpeaking, onFinishSpeech = viewModel::finishSpeaking,
                conversation=conversationState,onConfirm=viewModel::confirmVoice,onCancel=viewModel::cancelVoice,onCorrect=viewModel::correctVoice,onSelect=viewModel::selectVoiceThread,onControls=openControls,onChat=openChat,onEdit=viewModel::editVoice,onEditDictation=viewModel::editDictation)
        } else if (notification.alert != null) {
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
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column {
                        HorizontalDivider(thickness = .5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth().navigationBarsPadding()) {
                            destinations.forEach { d ->
                                val current = (backStack?.destination?.route ?: "home") == d.route
                                val tint = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                Column(Modifier.weight(1f).semantics { selected = current }.clickable(role = Role.Tab) { navigate(d.route) }.padding(vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(d.icon, null, Modifier.size(24.dp), tint = tint)
                                    Text(d.title, style = MaterialTheme.typography.labelSmall, color = tint)
                                }
                            }
                        }
                    }
                }
            }) { padding ->
                NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding),
                    enterTransition = { fadeIn(tween(if (reduceMotion) 0 else 180)) },
                    exitTransition = { fadeOut(tween(if (reduceMotion) 0 else 120)) }) {
                    composable("home") { DashboardScreen(dashboard, reduceMotion, viewModel::previewCall, { navigate("history") }, { navigate("settings") }, viewModel::openLiveCall,openControls,callCodex,openChat,{id->viewModel.openChat(id);viewModel.selectChat(id);chatOpen=true},viewModel::connectPc,viewModel::disconnectPc) }
                    composable("history") { HistoryScreen(dashboard.history, reduceMotion, viewModel::clearHistory, viewModel::resetDemo, dashboard.demoMode, dashboard.connection, dashboard.remote?.snapshot?.mode == "mock") }
                    composable("settings") { SettingsScreen(prefs, viewModel::setTheme, viewModel::setCalling, viewModel::setReduceMotion,
                        dashboard.remote, dashboard.demoMode, viewModel::selectDemo, viewModel::pair, viewModel::connectPc, viewModel::disconnectPc, viewModel::forgetPc,
                        notification, changeNotifications, { context.startActivity(TaskNotifications.systemSettings(context)) },
                        { attention -> context.startActivity(TaskNotifications.channelsSettings(context, attention, prefs.pushPreferences())) },
                        callSettingsStatus, viewModel::setCallInvitations, speechState, viewModel::setSpeech) }
                }
            }
        }
        if(chatOpen){BackHandler{chatOpen=false;viewModel.hideChat()};com.codexvoice.app.feature.conversation.ChatScreen(chatState,dashboard.remote?.snapshot?.threads?:emptyList(),viewModel::selectChat,viewModel::sendChat,{chatOpen=false;viewModel.hideChat()},{val id=chatState.threadId;if(id.isNotBlank()){viewModel.openControls(id);controlsOpen=true}})}
        if(controlsOpen)com.codexvoice.app.feature.control.TaskControlSheet(controlState,dashboard.remote?.snapshot?.threads?:emptyList(),viewModel::openControls,
            {controlsOpen=false;viewModel.hideControls()},viewModel::interruptTask,viewModel::queueTask,viewModel::confirmControl,viewModel::cancelControl,
            viewModel::denyApproval,viewModel::registerReviewer,viewModel::approveOnce,viewModel::authorizedApproval)
        if (error != null) AlertDialog(onDismissRequest = viewModel::dismissError, title = { Text("Preference not saved") },
            text = { Text(error!!) }, confirmButton = { TextButton(viewModel::dismissError) { Text("OK") } })
    }
}

