package com.codexvoice.app.feature.call
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codexvoice.app.core.designsystem.*
import com.codexvoice.app.core.model.CallReason
import kotlinx.coroutines.delay

@Composable
private fun VoiceIdentity(size: Int = 96) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Code, "Codex Voice identity", Modifier.size((size / 2).dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
@Composable
fun IncomingCallScreen(session: CallSession, reduceMotion: Boolean, onAnswer: () -> Unit, onDecline: () -> Unit) {
    var visible by remember(session.id) { mutableStateOf(false) }
    LaunchedEffect(session.id) { visible = true }
    Scaffold { padding ->
        AnimatedVisibility(visible, enter = fadeIn(tween(if (reduceMotion) 0 else 250))) {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                item { DemoBadge() }
                item { Spacer(Modifier.height(24.dp)); VoiceIdentity(112) }
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Codex Voice is calling", style = MaterialTheme.typography.headlineMedium)
                        Text(if (session.reason == CallReason.FAILURE) "Task failed" else "Task completed",
                            color = if (session.reason == CallReason.FAILURE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleLarge)
                    }
                }
                item {
                    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(session.task.title, style = MaterialTheme.typography.titleMedium)
                            Text(session.task.project, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Simulated · Microphone off", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        CallAction("Decline", Icons.Default.CallEnd, androidx.compose.ui.graphics.Color(0xFFFF453A), androidx.compose.ui.graphics.Color.White, onDecline)
                        CallAction("Answer", Icons.Default.Call, androidx.compose.ui.graphics.Color(0xFF30B963), androidx.compose.ui.graphics.Color.White, onAnswer)
                    }
                }
            }
        }
    }
}
@Composable
private fun CallAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: androidx.compose.ui.graphics.Color,
    foreground: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FilledIconButton(onClick, Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = foreground)) {
            Icon(icon, label, Modifier.size(30.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
@Composable
fun ActiveCallScreen(session: CallSession, onMute: () -> Unit, onHangUp: () -> Unit) {
    var elapsed by remember(session.id) { mutableLongStateOf(0L) }
    LaunchedEffect(session.id) {
        while (true) {
            elapsed = ((System.currentTimeMillis() - (session.answeredAt ?: System.currentTimeMillis())) / 1000).coerceAtLeast(0)
            delay(1000)
        }
    }
    Scaffold(bottomBar = {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.navigationBarsPadding().fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledTonalIconToggleButton(session.demoMuted, { onMute() }) {
                            Icon(if (session.demoMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                if (session.demoMuted) "Unmute demo" else "Mute demo")
                        }
                        Text(if (session.demoMuted) "Demo muted" else "Demo mute", style = MaterialTheme.typography.labelLarge)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledTonalIconButton({}, enabled = false) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Speaker unavailable") }
                        Text("Speaker · later", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Button(onHangUp, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFFFF453A), contentColor = androidx.compose.ui.graphics.Color.White)) {
                    Icon(Icons.Default.CallEnd, null); Spacer(Modifier.width(10.dp)); Text("Hang up")
                }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { DemoBadge() }
            item { VoiceIdentity(80) }
            item { Text("Codex Voice", style = MaterialTheme.typography.headlineMedium) }
            item { Text("Demo session · ${durationLabel(elapsed)}", color = MaterialTheme.colorScheme.primary) }
            item { Text(session.task.title, style = MaterialTheme.typography.titleMedium) }
            item {
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Eyebrow("Sample transcript")
                        Text(session.task.summary, style = MaterialTheme.typography.bodyLarge)
                        HorizontalDivider()
                        Text("Microphone off. Speech recognition and playback are not implemented.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

