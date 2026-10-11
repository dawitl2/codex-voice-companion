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
fun HistoryScreen(tasks: List<Task>, reduceMotion: Boolean, onClear: () -> Unit, onReset: () -> Unit) {
    var failuresOnly by rememberSaveable { mutableStateOf(false) }
    val visible = sortedHistory(tasks, if (failuresOnly) TaskStatus.FAILED else null)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenHeading("Task history", "A clear record of your work.") }
        item { DemoBadge() }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(!failuresOnly, { failuresOnly = false }, label = { Text("All tasks") })
                FilterChip(failuresOnly, { failuresOnly = true }, label = { Text("Failures") })
            }
        }
        if (visible.isEmpty()) item { EmptyState("No tasks here", "This view contains sample data only. Reset the demo to explore.") }
        items(visible, key = { it.id }) { TaskCard(it, reduceMotion) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onReset) { Text("Reset demo") }
                TextButton(onClear) { Text("Clear sample history") }
            }
        }
        item { Text("Sample history stays in memory. Real bridge history arrives with the connection layer.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

