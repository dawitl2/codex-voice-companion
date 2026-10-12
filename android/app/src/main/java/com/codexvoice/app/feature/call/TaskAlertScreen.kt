package com.codexvoice.app.feature.call

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codexvoice.app.core.notifications.NotificationUiState
import com.codexvoice.app.core.remote.ClientState
import com.codexvoice.app.core.remote.RemoteStatus
import com.codexvoice.app.core.notifications.notificationDetailsAvailable

@Composable
fun TaskAlertScreen(state: NotificationUiState, remote: ClientState?, onView: () -> Unit, onDismiss: () -> Unit, onConnect: () -> Unit) {
    val event = state.alert ?: return
    val connected = remote?.status == RemoteStatus.CONNECTED
    val details = if (notificationDetailsAvailable(event, remote?.paired == true, connected, remote?.snapshot, state.details)) state.details else null
    val turn = details?.let { response -> remote?.snapshot?.turns?.find { it.threadId == response["threadId"].asString && it.id == response["turnId"].asString } }
    val thread = turn?.let { current -> remote?.snapshot?.threads?.find { it.id == current.threadId } }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp).safeDrawingPadding(),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Spacer(Modifier.height(28.dp))
            Text("Codex Voice", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(if (event.kind == "completed") Icons.Default.TaskAlt else Icons.Default.NotificationsActive, null, Modifier.padding(28.dp).size(64.dp))
            }
            Text(event.title, style = MaterialTheme.typography.headlineLarge)
            Text(if (event.mode == "mock") "Simulated task · Task alert" else "Task alert", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!state.viewingDetails) {
                Text(if (event.kind == "approval") "Codex requested permission on your PC. Open to check whether it still needs attention." else "Open the private PC connection to inspect this turn's actual outcome.", style = MaterialTheme.typography.bodyLarge)
                Button(onView, Modifier.fillMaxWidth()) { Text("View results") }
            } else {
                if (!connected) {
                    Text(if (remote?.paired == true) "Live details unavailable. Connect to your PC through Tailscale; the alert contains only the event type and time." else "Pair your PC in Settings to retrieve task details. This alert cannot authorize access.")
                    if (remote?.paired == true) Button(onConnect) { Text("Connect to PC") }
                } else if (details == null) Text("Waiting for authenticated task details. ${state.status}")
                else {
                    Text(thread?.label ?: "Managed task", style = MaterialTheme.typography.titleLarge)
                    Text("Current turn state: ${turn?.status ?: "Outside the current snapshot"}")
                    if (event.kind == "approval") Text("Approval state: ${if (details["approvalStatus"].isJsonNull) "unavailable" else details["approvalStatus"].asString}. Decisions remain on the PC.")
                    if (details["expired"].asBoolean) Text("This alert has expired. Current task state may have changed.")
                    val result = details["result"].takeUnless { it.isJsonNull }?.asString
                    Text(result ?: "No response text is available for this turn.")
                    if (event.kind == "completed") Text("A completed turn does not independently prove that all changes or tests succeeded.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(java.time.Instant.parse(event.occurredAt).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm:ss")))
            OutlinedButton(onDismiss, Modifier.fillMaxWidth()) { Text("Dismiss alert") }
            Text("Dismiss only closes this alert. Task execution and approvals stay on the PC.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
