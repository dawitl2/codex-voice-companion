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
import androidx.compose.ui.unit.dp
import com.codexvoice.app.core.designsystem.*
import com.codexvoice.app.core.model.*

@Composable
fun DashboardScreen(state: DashboardState, reduceMotion: Boolean, onDemo: (CallReason) -> Unit, onHistory: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { ScreenHeading("Codex Voice", "Your work, within reach.") }
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DemoBadge()
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Computer, null, tint = MaterialTheme.colorScheme.primary)
                        Text("PC bridge is not connected", style = MaterialTheme.typography.titleMedium)
                    }
                    Text("Explore local sample tasks. Pairing arrives in Step 4.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Eyebrow("Current task · sample") }
        item { state.current?.let { TaskCard(it, reduceMotion) } ?: EmptyState("No active task", "Your next task will appear here.") }
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Try a demo call", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("In-app preview. No ringing, microphone or audio.", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Button(onClick = { onDemo(CallReason.COMPLETION) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Call, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                        Text("Preview completed-task call")
                    }
                    OutlinedButton(onClick = { onDemo(CallReason.FAILURE) }, modifier = Modifier.fillMaxWidth()) { Text("Preview failed-task call") }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Recent activity", style = MaterialTheme.typography.titleLarge)
                TextButton(onHistory) { Text("View all") }
            }
        }
        if (state.history.isEmpty()) item { EmptyState("A quiet workspace", "No sample activity. Reset the demo from History.") }
        items(state.history.take(2), key = { it.id }) { TaskCard(it, reduceMotion) }
        item { Text("All tasks on this screen are simulated.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

