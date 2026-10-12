package com.codexvoice.app.feature.call

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.codexvoice.app.core.calls.*
import com.codexvoice.app.core.remote.ClientState
import kotlinx.coroutines.delay

@Composable fun LiveCallScreen(state: LiveCallState, remote: ClientState?, onAnswer: () -> Unit, onDecline: () -> Unit,
    onEnd: () -> Unit, onMute: () -> Unit, onSpeaker: () -> Unit, onTone: () -> Unit,
    onReturn: () -> Unit, onResults: () -> Unit, onPermissions: () -> Unit) {
    var clock by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.offer?.callId) { while (true) { delay(1000); clock = SystemClock.elapsedRealtime() } }
    val offer = state.offer
    val duration = if (state.activeAt == 0L) 0 else ((if (state.endedAt > 0) state.endedAt else clock) - state.activeAt).coerceAtLeast(0) / 1000
    val association = remote?.snapshot?.threads?.find { it.id == offer?.threadId }?.label ?: "Managed task"
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp).safeDrawingPadding(),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Spacer(Modifier.height(20.dp))
            Text("Codex Voice", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(if (state.phase == LivePhase.ACTIVE) Icons.Default.GraphicEq else Icons.Default.HeadsetMic, null, Modifier.padding(28.dp).size(64.dp))
            }
            Text(when (state.phase) { LivePhase.OFFERED -> "Audio session available"; LivePhase.CONNECTING -> "Connecting to Codex Voice..."; LivePhase.ACTIVE -> "Audio session active"; LivePhase.RECONNECTING -> "Reconnecting audio..."; LivePhase.EXPIRED -> "Invitation expired"; LivePhase.DECLINED -> "Call declined"; LivePhase.FAILED -> "Audio session ended"; else -> "Call ended" }, style = MaterialTheme.typography.headlineMedium)
            Text(association, style = MaterialTheme.typography.titleLarge)
            Text(if (offer?.mode == "mock") "Simulated task · Real private audio" else "Real private audio", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("AI speech is not available in this stage.", style = MaterialTheme.typography.bodyMedium)
            if (state.problem.isNotEmpty()) Text(state.problem, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (state.phase) {
                LivePhase.OFFERED -> {
                    Text(when (offer?.kind) { "failed" -> "A task failed. Review its state or start a diagnostic audio session."; "approval" -> "Approval needs attention on the PC. This call cannot grant permission."; "interrupted" -> "A task was interrupted."; else -> "A Codex turn completed. Review results or answer to connect audio." })
                    Button(onAnswer, Modifier.fillMaxWidth().semantics { contentDescription = "Answer real audio call" }) { Icon(Icons.Default.Call, null); Spacer(Modifier.width(8.dp)); Text("Answer") }
                    OutlinedButton(onDecline, Modifier.fillMaxWidth()) { Text("Decline") }
                    TextButton(onResults) { Text("View task results") }
                    if (state.permissionDenied) TextButton(onPermissions) { Text("Microphone app settings") }
                }
                LivePhase.CONNECTING, LivePhase.RECONNECTING -> {
                    CircularProgressIndicator()
                    Text("Microphone is off until authenticated media is ready.")
                    OutlinedButton(onEnd, Modifier.fillMaxWidth()) { Text("Cancel call") }
                }
                LivePhase.ACTIVE -> {
                    Text("%02d:%02d".format(duration / 60, duration % 60), style = MaterialTheme.typography.headlineMedium)
                    Text(if (state.capturing) "Microphone active" else "Microphone off · Muted")
                    LinearProgressIndicator(progress = { (state.inputLevel * 4).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Measured microphone level" })
                    Text("PC audio activity")
                    LinearProgressIndicator(progress = { (state.outputLevel * 4).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Measured PC audio level" })
                    Text("${state.route} · Private media connected")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(onMute, Modifier.weight(1f)) { Text(if (state.muted) "Unmute" else "Mute") }
                        FilledTonalButton(onSpeaker, Modifier.weight(1f), enabled = state.routeSupported) { Text(if (state.speaker) "Use earpiece" else "Use speaker") }
                    }
                    OutlinedButton(onTone, Modifier.fillMaxWidth()) { Text("Play quiet PC test tone") }
                    Button(onEnd, Modifier.fillMaxWidth()) { Text("Hang up") }
                    Text("PC received ${state.pcReceivedFrames} microphone frames · Phone received ${state.downlinkFrames} PC frames", style = MaterialTheme.typography.bodySmall)
                    Text("Network RTT ${state.rttMs} ms · Dropped ${state.dropped} · Playback underruns ${state.underruns}", style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    if (duration > 0) Text("Duration · %02d:%02d".format(duration / 60, duration % 60))
                    Text("Microphone and playback are stopped.")
                    Button(onReturn, Modifier.fillMaxWidth()) { Text("Return to dashboard") }
                    if (offer != null) TextButton(onResults) { Text("View task results") }
                }
            }
            Text("Audio stays on your paired private PC connection. Ending the call does not cancel Codex work.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
