package com.codexvoice.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.codexvoice.app.core.designsystem.formattedTime
import com.codexvoice.app.core.remote.*

fun RemoteStatus.label() = when (this) {
    RemoteStatus.UNPAIRED -> "No PC paired"
    RemoteStatus.DISCONNECTED -> "Disconnected"
    RemoteStatus.CONNECTING -> "Connecting to PC"
    RemoteStatus.PAIRING -> "Pairing securely"
    RemoteStatus.AUTHENTICATING -> "Authenticating device"
    RemoteStatus.CONNECTED -> "Connected"
    RemoteStatus.RECONNECTING -> "Reconnecting"
    RemoteStatus.AUTH_FAILED -> "Pairing required"
    RemoteStatus.PAIR_FAILED -> "Pairing failed"
    RemoteStatus.BRIDGE_UNAVAILABLE -> "Codex unavailable on PC"
    RemoteStatus.PROTOCOL_INCOMPATIBLE -> "Connection protocol error"
    RemoteStatus.TLS_ERROR -> "Secure connection failed"
    RemoteStatus.STORAGE_ERROR -> "Protected storage unavailable"
}
@Composable
fun ConnectionPanel(state: ClientState?, demo: Boolean, onDemo: (Boolean) -> Unit, onPair: (String, String) -> Unit,
    onConnect: () -> Unit, onDisconnect: () -> Unit, onForget: () -> Unit) {
    var address by rememberSaveable { mutableStateOf(state?.endpoint ?: "") }
    // A short-lived invitation must not enter saved instance state or preferences.
    var code by remember { mutableStateOf("") }
    var confirmForget by remember { mutableStateOf(false) }
    LaunchedEffect(state?.endpoint) { if (state != null) address = state.endpoint }
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        FilterChip(!demo, { onDemo(false) }, label = { Text("PC monitor") })
        FilterChip(demo, { onDemo(true) }, label = { Text("Offline demo") })
    }
    if (demo) {
        Text("Offline demo", style = MaterialTheme.typography.titleMedium)
        Text("Simulated tasks · Microphone off")
    } else if (state != null) {
        val busy = state.status in setOf(RemoteStatus.CONNECTING, RemoteStatus.PAIRING, RemoteStatus.AUTHENTICATING, RemoteStatus.RECONNECTING)
        Text(state.status.label(), style = MaterialTheme.typography.titleMedium)
        if (state.bridgeName.isNotEmpty()) Text((if (state.paired) "Paired PC" else "Last PC") + " · ${state.bridgeName}")
        if (state.endpoint.isNotEmpty()) Text(state.endpoint, style = MaterialTheme.typography.bodySmall)
        if (state.lastConnected > 0) Text("Last connection · ${formattedTime(state.lastConnected)}", style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.problem.isNotEmpty()) Text(state.problem, color = MaterialTheme.colorScheme.error)
        if (!state.paired) {
            TextField(address, { address = it }, label = { Text("Private server address") },
                placeholder = { Text("wss://your-pc.your-tailnet.ts.net/remote") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(), colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
            TextField(code, { code = it.take(24) }, label = { Text("One-time pairing code") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(), colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
            Button({ val invitation = code; code = ""; onPair(address, invitation) }, enabled = !busy && address.isNotBlank() && code.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Pair this PC") }
            Text("Tailscale required · Get a fresh code from the PC", style = MaterialTheme.typography.bodySmall)
        } else {
            Button(onConnect, enabled = !busy && state.status != RemoteStatus.CONNECTED, modifier = Modifier.fillMaxWidth()) { Text("Connect") }
        }
        if (busy || state.paired || state.status == RemoteStatus.BRIDGE_UNAVAILABLE) OutlinedButton(onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Stop all") }
        if (state.paired || state.status == RemoteStatus.STORAGE_ERROR) TextButton({ confirmForget = true }) { Text("Forget paired PC") }
        Text("Stays connected in the background until Stop all.", style = MaterialTheme.typography.bodySmall)
    }
    if (confirmForget) AlertDialog(onDismissRequest = { confirmForget = false }, title = { Text("Forget paired PC?") },
        text = { Text("This removes the phone’s saved credential. Revoke this device on the PC before pairing it again.") },
        confirmButton = { TextButton({ confirmForget = false; code = ""; onForget() }) { Text("Forget") } },
        dismissButton = { TextButton({ confirmForget = false }) { Text("Cancel") } })
}
