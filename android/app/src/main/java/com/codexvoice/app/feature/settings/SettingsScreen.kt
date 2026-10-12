package com.codexvoice.app.feature.settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.codexvoice.app.core.designsystem.*
import com.codexvoice.app.core.model.*
import com.codexvoice.app.core.remote.ClientState
import com.codexvoice.app.core.notifications.NotificationUiState

@Composable
fun SettingsScreen(p: UserPreferences, onTheme: (ThemeMode) -> Unit,
    onCalling: (Boolean, Boolean, Boolean) -> Unit, onMotion: (Boolean) -> Unit,
    remote: ClientState? = null, demoMode: Boolean = true, onDemo: (Boolean) -> Unit = {},
    onPair: (String, String) -> Unit = { _, _ -> }, onConnect: () -> Unit = {}, onDisconnect: () -> Unit = {}, onForget: () -> Unit = {},
    notificationState: NotificationUiState = NotificationUiState(), onNotifications: (UserPreferences) -> Unit = {}, onSystemSettings: () -> Unit = {},
    onChannelSettings: (Boolean) -> Unit = {}, callStatus: String = "Call invitations disabled", onCallInvitations: (Boolean) -> Unit = {}) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenHeading("Settings", "") }
        item { Eyebrow("Appearance") }
        item {
            SettingsSurface {
                ThemeMode.entries.forEach { mode ->
                    Row(Modifier.fillMaxWidth().selectable(selected = p.theme == mode, onClick = { onTheme(mode) }, role = Role.RadioButton).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(when (mode) { ThemeMode.DARK -> "Dark"; ThemeMode.LIGHT -> "Light"; ThemeMode.SYSTEM -> "Follow system" }, Modifier.weight(1f))
                        if (p.theme == mode) Icon(Icons.Default.Check, "Selected", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                PreferenceSwitch("Reduce motion", "Use immediate transitions.", p.reduceMotion, onMotion)
            }
        }
        item { Eyebrow("Connection") }
        item {
            SettingsSurface {
                ConnectionPanel(remote, demoMode, onDemo, onPair, onConnect, onDisconnect, onForget)
                Text("This device: Android ${android.os.Build.VERSION.RELEASE}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { Eyebrow("Task notifications") }
        item {
            SettingsSurface {
                Text(notificationState.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (notificationState.pending) "PC settings: pending synchronization" else "PC settings: synchronized", style = MaterialTheme.typography.bodySmall)
                PreferenceSwitch("Enable notifications", "Receive task alerts through Firebase while the app is closed.", p.notificationsEnabled) { onNotifications(p.copy(notificationsEnabled = it)) }
                PreferenceSwitch("Task completion", "Alert when a turn completes.", p.callOnCompletion) { onCalling(it, p.callOnFailure, p.callOnApproval) }
                PreferenceSwitch("Task failure", "Alert when a turn fails.", p.callOnFailure) { onCalling(p.callOnCompletion, it, p.callOnApproval) }
                PreferenceSwitch("Task interrupted", "Alert when a turn is interrupted.", p.alertOnInterruption) { onNotifications(p.copy(alertOnInterruption = it)) }
                PreferenceSwitch("Approval needed", "Alert when permission needs attention on the PC.", p.callOnApproval) { onCalling(p.callOnCompletion, p.callOnFailure, it) }
                PreferenceSwitch("Notification sound", "Use the Android notification sound; system settings take precedence.", p.notificationSound) { onNotifications(p.copy(notificationSound = it)) }
                PreferenceSwitch("Notification vibration", "Use a short vibration where supported.", p.notificationVibration) { onNotifications(p.copy(notificationVibration = it)) }
                TextButton(onSystemSettings) { Text("Android notification settings") }
                TextButton({ onChannelSettings(false) }) { Text("Task outcome channel settings") }
                TextButton({ onChannelSettings(true) }) { Text("Attention channel settings") }
            }
        }
        item { Eyebrow("Voice") }
        item {
            SettingsSurface {
                Text("Preferred language · English", style = MaterialTheme.typography.titleMedium)
                PreferenceSwitch("Offer audio calls", "Task alerts can offer an authenticated private audio session. Answer explicitly starts the microphone.", p.callInvitations, onCallInvitations)
                Text(callStatus, style = MaterialTheme.typography.bodySmall)
                Text("AI voice coming later", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Eyebrow("About") }
        item {
            SettingsSurface {
                Text("Codex Voice", style = MaterialTheme.typography.titleMedium)
                Text("Version 0.6.0 · Experimental companion")
            }
        }
    }
}
@Composable
private fun SettingsSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
@Composable
private fun PreferenceSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (title == "Offer audio calls") Text("Answer starts the microphone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, modifier = Modifier.semantics { contentDescription = title },
            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF34C759), checkedThumbColor = Color.White,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh, uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent))
    }
}

