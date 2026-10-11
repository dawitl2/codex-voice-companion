package com.codexvoice.app.core.designsystem
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.codexvoice.app.core.model.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun formattedTime(timestamp: Long): String = DateTimeFormatter.ofPattern("MMM d · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(timestamp))
fun durationLabel(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)
@Composable
fun ScreenHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
fun Eyebrow(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable
fun DemoBadge() {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(100.dp)) {
        Text("OFFLINE DEMO", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
@Composable
fun TaskCard(task: Task, reduceMotion: Boolean = false) {
    var expanded by rememberSaveable(task.id) { mutableStateOf(false) }
    val color = if (task.status == TaskStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val icon = when (task.status) {
        TaskStatus.RUNNING -> Icons.Default.Autorenew
        TaskStatus.COMPLETED -> Icons.Default.CheckCircle
        TaskStatus.FAILED -> Icons.Default.ErrorOutline
        TaskStatus.INTERRUPTED -> Icons.Default.PauseCircleOutline
        TaskStatus.AWAITING_APPROVAL -> Icons.Default.Lock
    }
    Card(onClick = { expanded = !expanded }, shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().animateContentSize(tween(if (reduceMotion) 0 else 180)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, Modifier.size(18.dp), tint = color)
                Text(task.status.label(), style = MaterialTheme.typography.labelLarge, color = color)
            }
            Text(task.title, style = MaterialTheme.typography.titleMedium)
            Text(task.project, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formattedTime(task.updatedAt) + (task.durationSeconds?.let { " · ${durationLabel(it)}" } ?: ""),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (expanded) Text(task.summary, style = MaterialTheme.typography.bodyMedium)
            Text(if (expanded) "Hide sample details" else "View sample details", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}
@Composable
fun EmptyState(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Inbox, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

