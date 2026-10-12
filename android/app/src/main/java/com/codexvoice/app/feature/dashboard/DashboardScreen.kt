package com.codexvoice.app.feature.dashboard
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.codexvoice.app.core.designsystem.*
import com.codexvoice.app.core.model.*
import com.codexvoice.app.core.remote.RemoteStatus
import com.codexvoice.app.feature.settings.label

@Composable
fun DashboardScreen(state: DashboardState, reduceMotion: Boolean, onDemo: (CallReason) -> Unit, onHistory: () -> Unit, onSettings: () -> Unit = {}) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Code, "Codex Voice", Modifier.size(36.dp))
                Text("Codex Voice", style = MaterialTheme.typography.headlineMedium)
            }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.demoMode) DemoBadge() else Text(if (state.remote?.snapshot?.mode == "mock") "PC BRIDGE MOCK · SIMULATED" else "PRIVATE PC MONITOR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Computer, null, tint = MaterialTheme.colorScheme.primary)
                        Text(if (state.demoMode) "PC bridge is not connected" else state.remote?.status?.label() ?: "No PC paired", style = MaterialTheme.typography.titleMedium)
                    }
                    state.remote?.problem?.takeIf { it.isNotEmpty() && !state.demoMode }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (!state.demoMode) {
                        state.remote?.snapshot?.let { Text("Last synchronized · ${formattedTime(it.syncedAt)}", style = MaterialTheme.typography.bodySmall) }
                        if (state.remote?.status != RemoteStatus.CONNECTED && state.remote?.snapshot != null) Text("Last known state · may be stale", color = MaterialTheme.colorScheme.error)
                        TextButton(onSettings) { Text("Connection settings") }
                    }
                }
            }
        }
        item { Eyebrow(if (state.demoMode) "Current task · sample" else "Selected conversation · latest turn") }
        item { state.current?.let { TaskCard(it, reduceMotion) } ?: EmptyState("No active task", "Your next task will appear here.") }
        if (state.demoMode) item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Try a demo call", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Simulated · Microphone off", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Button(onClick = { onDemo(CallReason.COMPLETION) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Call, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                        Text("Preview completed-task call")
                    }
                    OutlinedButton(onClick = { onDemo(CallReason.FAILURE) }, modifier = Modifier.fillMaxWidth()) { Text("Preview failed-task call") }
                }
            }
        }
        if (!state.demoMode && state.remote?.snapshot?.threads?.isNotEmpty() == true) {
            item { Eyebrow("Recent managed conversations") }
            items(state.remote.snapshot.threads, key = { "thread-" + it.id }) { thread ->
                Card(shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(thread.label, style = MaterialTheme.typography.titleMedium)
                        Text(thread.project + if (thread.id == state.remote.snapshot.selectedThreadId) " · Selected on PC" else "", style = MaterialTheme.typography.bodyMedium)
                        Text(if (thread.status == "ready") "Available" else "Resume on PC to reconcile", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Recent activity", style = MaterialTheme.typography.titleLarge)
                TextButton(onHistory) { Text("View all") }
            }
        }
        if (state.history.isEmpty()) item { EmptyState("A quiet workspace", if (state.demoMode) "No sample activity. Reset the demo from History." else "No recent synchronized task events.") }
        items(state.history.take(2), key = { it.id }) { TaskCard(it, reduceMotion) }
    }
}

