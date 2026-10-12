package com.codexvoice.app.feature.history
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codexvoice.app.core.designsystem.*
import com.codexvoice.app.core.model.*

@Composable
fun HistoryScreen(tasks: List<Task>, reduceMotion: Boolean, onClear: () -> Unit, onReset: () -> Unit,
    demoMode: Boolean = true, connection: ConnectionStatus = ConnectionStatus.DEMO, bridgeMock: Boolean = false) {
    var failuresOnly by rememberSaveable { mutableStateOf(false) }
    val visible = sortedHistory(tasks, if (failuresOnly) TaskStatus.FAILED else null)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenHeading("Task history", "") }
        item {
            if (demoMode) DemoBadge() else Text(if (bridgeMock) "PC bridge mock · simulated events" else "PC bridge history",
                color = MaterialTheme.colorScheme.primary)
        }
        if (!demoMode && connection != ConnectionStatus.CONNECTED) item { Text("Not synchronized. Displayed history may be stale.", color = MaterialTheme.colorScheme.error) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(!failuresOnly, { failuresOnly = false }, label = { Text("All tasks") })
                FilterChip(failuresOnly, { failuresOnly = true }, label = { Text("Failures") })
            }
        }
        if (visible.isEmpty()) item { EmptyState("No tasks here", if (demoMode) "This view contains sample data only. Reset the demo to explore." else "Connect your paired PC to load recent task events.") }
        items(visible, key = { it.id }) { TaskCard(it, reduceMotion) }
        if (demoMode) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onReset) { Text("Reset demo") }
                TextButton(onClear) { Text("Clear sample history") }
            }
        }
        item { Text(if (demoMode) "Sample history stays in memory." else "Recent events only. Earlier or offline history may be incomplete; reconnect loads a fresh snapshot.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

