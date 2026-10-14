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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import com.codexvoice.app.core.designsystem.*
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
    conversation:ConversationState=ConversationState(),onConfirm:(String)->Unit={},onCancel:(String)->Unit={},onCorrect:(String,String)->Unit={_,_->},onSelect:(String)->Unit={},onControls:()->Unit={},onChat:()->Unit={},onEdit:(String)->Unit={},onEditDictation:()->Unit={}) {
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

    LaunchedEffect(conversation.proposal?.id,conversation.phase){if(conversation.proposal!=null && conversation.phase=="awaiting_confirmation")details=true}
    var clock by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.offer?.callId) { while (true) { delay(100); clock = SystemClock.elapsedRealtime() } }
    val duration = if (state.activeAt == 0L) 0 else ((if (state.endedAt > 0) state.endedAt else clock) - state.activeAt).coerceAtLeast(0) / 1000
    val thread = remote?.snapshot?.threads?.find { it.id == (conversation.selection?.threadId?:state.offer?.threadId) }
    val task = conversation.selection?.label ?: thread?.label ?: "Your workspace"
    val project = conversation.selection?.project ?: thread?.project
    CompanionBackdrop(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val compact = maxHeight < 700.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(if(compact) 8.dp else 28.dp))
            Text(when(state.phase) {
                LivePhase.OFFERED -> "Incoming call"
                LivePhase.ACTIVE -> "On call"
                LivePhase.CONNECTING -> "Connecting…"
                LivePhase.RECONNECTING -> "Reconnecting…"
                LivePhase.EXPIRED -> "Invitation expired"
                LivePhase.DECLINED -> "Declined"
                else -> "Call ended"
            }, style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(if(compact) 18.dp else 32.dp))
            CodexCloud(Modifier.size(if(compact) 128.dp else 164.dp))
            Text("Codex",fontSize=42.sp,lineHeight=50.sp,fontWeight=FontWeight.SemiBold,letterSpacing=(-1.5).sp)
            if(state.activeAt>0) Text("%02d:%02d".format(duration/60,duration%60),fontSize=23.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            project?.let { Text(it,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis) }
            Text(task,style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(state.offer?.mode=="mock") Text("Simulated call",color=MaterialTheme.colorScheme.error)
            if(state.problem.isNotBlank()) Text(state.problem,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(if(compact) 16.dp else 24.dp))
            when(state.phase) {
                LivePhase.OFFERED -> {
                    Text(when(state.offer?.kind) { "failed" -> "Task failed"; "approval" -> "Approval needs attention"; "interrupted" -> "Task interrupted"; "review" -> "Task update"; else -> "Task finished" },color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(if(compact) 100.dp else 180.dp))
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        CallControl("Answer",Icons.Default.Call,{ringer.close();onAnswer()},Color.White,Color(0xFF20BF60),description="Answer real audio call")
                        CallControl("Decline",Icons.Default.CallEnd,{ringer.close();onDecline()},Color.White,Color(0xFFFF393E))
                    }
                    Spacer(Modifier.height(20.dp))
                    TextButton({ringer.close();onResults()}){Text("View task results")}
                    if(state.permissionDenied) TextButton(onPermissions){Text("Microphone app settings")}
                }
                LivePhase.CONNECTING,LivePhase.RECONNECTING -> {
                    CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp)
                    Spacer(Modifier.height(48.dp))
                    CallControl("Cancel call",Icons.Default.CallEnd,onEnd,Color(0xFFFF393E),Color.White)
                }
                LivePhase.ACTIVE -> {
                    val proposal=conversation.proposal
                    val remaining=proposal?.countdownMs?.let { (it-(System.currentTimeMillis()-proposal.receivedAt)).coerceAtLeast(0) }
                    Surface(shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.8f)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(if(proposal!=null) {if(remaining!=null) "You said · sending" else "Review instruction"} else if(speech.provisional!=null) "Live · provisional" else speechLabel(state.capturing,state.muted,speech),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(proposal?.text ?: speech.provisional?.text ?: conversation.entries.lastOrNull()?.text ?: speech.transcript.lastOrNull()?.text ?: "What would you like to do?",maxLines=3,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyLarge)
                            }
                            if(remaining!=null) Box(Modifier.size(40.dp),contentAlignment=Alignment.Center) {
                                CircularProgressIndicator(progress={remaining/3000f},modifier=Modifier.fillMaxSize(),strokeWidth=3.dp,trackColor=MaterialTheme.colorScheme.primary.copy(alpha=.15f))
                                Text(((remaining+999)/1000).toString(),style=MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    if(proposal!=null) Row {
                        TextButton({onCancel(proposal.id)}){Text("Cancel")}
                        TextButton({onEdit(proposal.id);details=true}){Text("Edit")}
                        TextButton({onConfirm(proposal.id)}){Text("Send now")}
                    } else Row {
                        TextButton(onFinishSpeech,enabled=state.capturing&&!speech.speaking){Text("Finish speaking")}
                        if(speech.speaking) TextButton(onStopSpeech){Text("Stop speaking")}
                        else TextButton({if(speech.provisional!=null)onEditDictation();details=true}){Text(if(speech.provisional!=null) "Review" else "Conversation")}
                    }
                    Text(if(conversation.autoSend) "Auto-send · 3-second review" else "Manual review",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(conversation.phase in setOf("submitting","running","accepted","awaitingApproval","busy","unknown","rejected","completed","failed","cancelled")) Text(conversationLabel(conversation),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(if(compact) 16.dp else 28.dp))
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                        CallControl(if(state.muted) "Unmute" else "Mute",if(state.muted)Icons.Default.MicOff else Icons.Default.Mic,onMute,
                            if(state.muted)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,if(state.muted)Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                        CallControl("Keypad",Icons.Default.Dialpad,onChat,MaterialTheme.colorScheme.surface,MaterialTheme.colorScheme.onSurfaceVariant,description="Open text keypad")
                        CallControl("Speaker",Icons.AutoMirrored.Filled.VolumeUp,onSpeaker,
                            if(state.speaker)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,if(state.speaker)Color.White else MaterialTheme.colorScheme.onSurfaceVariant,description=if(state.speaker)"Use earpiece" else "Use speaker",enabled=state.routeSupported)
                    }
                    Spacer(Modifier.height(if(compact) 20.dp else 32.dp))
                    CallControl("Hang up",Icons.Default.CallEnd,onEnd,Color(0xFFFF393E),Color.White)
                }
                else -> {
                    Button(onReturn){Text("Return to dashboard")}
                    if(state.offer!=null) TextButton(onResults){Text("View task results")}
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        }
        if(details)ModalBottomSheet(onDismissRequest={details=false}){Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)){
                    Text("Conversation",style=MaterialTheme.typography.titleLarge)
                    ConversationPanel(conversation,remote?.snapshot?.threads ?: emptyList(),onConfirm,onCancel,onCorrect,onSelect,onEdit)
                    if (speech.summary.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Call greeting",style = MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
