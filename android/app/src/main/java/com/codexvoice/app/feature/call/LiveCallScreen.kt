package com.codexvoice.app.feature.call

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.codexvoice.app.core.calls.*
import com.codexvoice.app.core.remote.ClientState
import com.codexvoice.app.core.speech.*
import com.codexvoice.app.core.conversation.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LiveCallScreen(state: LiveCallState, remote: ClientState?, onAnswer: () -> Unit, onDecline: () -> Unit,
    onEnd: () -> Unit, onMute: () -> Unit, onSpeaker: () -> Unit, onTone: () -> Unit,
    onReturn: () -> Unit, onResults: () -> Unit, onPermissions: () -> Unit, ringEnabled: Boolean = false,
    speech: SpeechUiState = SpeechUiState(), onRepeat: () -> Unit = {}, onStopSpeech: () -> Unit = {}, onFinishSpeech: () -> Unit = {},
    conversation:ConversationState=ConversationState(),onConfirm:(String)->Unit={},onCancel:(String)->Unit={},onCorrect:(String,String)->Unit={_,_->},onSelect:(String)->Unit={},onControls:()->Unit={},onChat:()->Unit={}) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ringer = remember(state.offer?.callId) { IncomingCallRinger(context, state.offer?.expiresAt ?: 0) }
    DisposableEffect(ringer, lifecycle, state.phase, ringEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && state.phase == LivePhase.OFFERED && !TelecomRuntime.claimed) ringer.start(ringEnabled)
            if (event == Lifecycle.Event.ON_PAUSE) ringer.pause()
        }
        lifecycle.addObserver(observer)
        if (state.phase == LivePhase.OFFERED && !TelecomRuntime.claimed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) ringer.start(ringEnabled)
        else ringer.pause()
        onDispose { lifecycle.removeObserver(observer); ringer.pause() }
    }
    var details by remember(state.offer?.callId){mutableStateOf(false)}
    LaunchedEffect(conversation.proposal?.id){if(conversation.proposal!=null)details=true}
    var clock by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.offer?.callId) { while (true) { delay(1000); clock = SystemClock.elapsedRealtime() } }
    val duration = if (state.activeAt == 0L) 0 else ((if (state.endedAt > 0) state.endedAt else clock) - state.activeAt).coerceAtLeast(0) / 1000
    val task = remote?.snapshot?.threads?.find { it.id == state.offer?.threadId }?.label ?: "Your workspace"
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            // Native code mark inspired by the product name; no external logo asset.
            Icon(Icons.Default.Code, "Codex Voice", Modifier.size(38.dp), tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(12.dp))
            Text("Codex Voice", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(task, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            Text(when (state.phase) {
                LivePhase.OFFERED -> "Incoming audio session"
                LivePhase.CONNECTING -> "Connecting…"
                LivePhase.ACTIVE -> "Connected · %02d:%02d".format(duration / 60, duration % 60)
                LivePhase.RECONNECTING -> "Reconnecting…"
                LivePhase.EXPIRED -> "Invitation expired"
                LivePhase.DECLINED -> "Declined"
                else -> "Call ended"
            }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.phase == LivePhase.OFFERED) Text(when (state.offer?.kind) {
                "failed" -> "Task failed"; "approval" -> "Approval needs attention"; "interrupted" -> "Task interrupted"; "review" -> "Existing task review"; else -> "Task completed"
            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.phase == LivePhase.ACTIVE) {
                Spacer(Modifier.height(12.dp))
                Text(if (state.capturing) "Mic on · ${state.route}" else "Mic off · ${state.route}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { (state.inputLevel * 4).coerceIn(0f, 1f) }, modifier = Modifier.width(64.dp).height(2.dp).semantics { contentDescription = "Measured microphone level" })
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(progress = { (state.outputLevel * 4).coerceIn(0f, 1f) }, modifier = Modifier.width(64.dp).height(2.dp).semantics { contentDescription = "Measured PC audio level" })
            }
            Spacer(Modifier.height(12.dp))
            Text(if (state.offer?.mode == "mock") "Simulated task · Audio only" else "Private audio", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if(conversation.enabled)"Voice instructions need confirmation" else "Local speech", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (state.problem.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(state.problem, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.weight(1f))
            when (state.phase) {
                LivePhase.OFFERED -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                        CallControl("Decline", Icons.Default.CallEnd, { ringer.close(); onDecline() }, Color(0xFFFF453A), Color.White)
                        CallControl("Answer", Icons.Default.Call, { ringer.close(); onAnswer() }, Color(0xFF30B963), Color.White, description = "Answer real audio call")
                    }
                    Spacer(Modifier.height(24.dp))
                    TextButton({ ringer.close(); onResults() }) { Text("View task results") }
                    if (state.permissionDenied) TextButton(onPermissions) { Text("Microphone app settings") }
                }
                LivePhase.CONNECTING, LivePhase.RECONNECTING -> {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(24.dp))
                    CallControl("Cancel call", Icons.Default.CallEnd, onEnd, Color(0xFFFF453A), Color.White)
                }
                LivePhase.ACTIVE -> {
                    Text(speechLabel(state.capturing,state.muted,speech), style = MaterialTheme.typography.titleMedium)
                    speech.transcript.lastOrNull()?.let{Text(it.text,maxLines=2,overflow=TextOverflow.Ellipsis)}
                    Row{TextButton({details=true}){Text("Conversation")};TextButton(onChat){Text("Text chat")}}
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        CallControl(if (state.muted) "Unmute" else "Mute", if (state.muted) Icons.Default.MicOff else Icons.Default.Mic,
                            onMute, if (state.muted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
                            if (state.muted) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface)
                        CallControl(if (state.speaker) "Earpiece" else "Speaker", Icons.AutoMirrored.Filled.VolumeUp, onSpeaker,
                            if (state.speaker) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
                            if (state.speaker) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                            description = if (state.speaker) "Use earpiece" else "Use speaker", enabled = state.routeSupported)
                    }
                    Spacer(Modifier.height(28.dp))
                    CallControl("Hang up", Icons.Default.CallEnd, onEnd, Color(0xFFFF453A), Color.White)
                    Spacer(Modifier.height(20.dp))

                }
                else -> {
                    if (duration > 0) Text("%02d:%02d".format(duration / 60, duration % 60), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onReturn) { Text("Return to dashboard") }
                    if (state.offer != null) TextButton(onResults) { Text("View task results") }
                }
            }
        }
        if(details)ModalBottomSheet(onDismissRequest={details=false}){Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)){
                    Text("Conversation",style=MaterialTheme.typography.titleLarge)
                    ConversationPanel(conversation,remote?.snapshot?.threads ?: emptyList(),onConfirm,onCancel,onCorrect,onSelect)
                    if (speech.summary.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Task summary",style = MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(speech.summary,style=MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    for (utterance in speech.transcript.takeLast(3)) {
                        Spacer(Modifier.height(8.dp))
                        Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Text("Recognized",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(utterance.text,style=MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    if(speech.error.isNotEmpty()) Text(speechErrorText(speech.error),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(onRepeat,enabled=speech.summary.isNotEmpty() && speech.ttsStatus=="ready") { Text(if(conversation.enabled)"Repeat response" else "Repeat summary") }
                        TextButton(onStopSpeech,enabled=speech.speaking || speech.phase=="speaking") { Text("Stop speaking") }
                    }
                    TextButton(onFinishSpeech,enabled=state.capturing && !speech.speaking) { Text("Finish speaking") }

                    TextButton(onControls){Text("Task controls")}
                    TextButton(onTone){Text("Diagnostic audio tone")}
                    TextButton({details=false}){Text("Back to call")}
        }}
    }
}

@Composable private fun CallControl(label: String, icon: ImageVector, action: () -> Unit, background: Color, foreground: Color,
    description: String = label, enabled: Boolean = true) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FilledIconButton(action, Modifier.size(72.dp).semantics { contentDescription = description }, enabled = enabled,
            shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = background, contentColor = foreground)) {
            Icon(icon, null, Modifier.size(30.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
