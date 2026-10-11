package com.codexvoice.app.feature.settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.codexvoice.app.core.designsystem.*
import com.codexvoice.app.core.model.*

@Composable
fun SettingsScreen(p: UserPreferences, onTheme: (ThemeMode) -> Unit,
    onCalling: (Boolean, Boolean, Boolean) -> Unit, onMotion: (Boolean) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { ScreenHeading("Settings", "Make room for your preferences.") }
        item { Eyebrow("Appearance") }
        item {
            SettingsSurface {
                ThemeMode.entries.forEach { mode ->
                    Row(Modifier.fillMaxWidth().selectable(selected = p.theme == mode, onClick = { onTheme(mode) }, role = Role.RadioButton).padding(vertical = 4.dp)) {
                        RadioButton(p.theme == mode, onClick = null)
                        Text(when (mode) { ThemeMode.DARK -> "Dark"; ThemeMode.LIGHT -> "Light"; ThemeMode.SYSTEM -> "Follow system" }, Modifier.padding(start = 12.dp, top = 12.dp))
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                PreferenceSwitch("Reduce motion", "Use immediate transitions.", p.reduceMotion, onMotion)
            }
        }
        item { Eyebrow("Connection") }
        item {
            SettingsSurface {
                Text("Offline demo", style = MaterialTheme.typography.titleMedium)
                Text("No PC bridge is connected.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton({}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Pair a PC · Step 4") }
                Text("This device: Android ${android.os.Build.VERSION.RELEASE}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { Eyebrow("Calling preferences") }
        item {
            SettingsSurface {
                Text("Saved locally for future calls. Explicit demo previews always open a call.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PreferenceSwitch("Task completion", "Call when a turn completes.", p.callOnCompletion) { onCalling(it, p.callOnFailure, p.callOnApproval) }
                PreferenceSwitch("Task failure", "Call when a turn fails.", p.callOnFailure) { onCalling(p.callOnCompletion, it, p.callOnApproval) }
                PreferenceSwitch("Approval needed", "Call when permission is requested.", p.callOnApproval) { onCalling(p.callOnCompletion, p.callOnFailure, it) }
            }
        }
        item { Eyebrow("Voice") }
        item {
            SettingsSurface {
                Text("Preferred language · English", style = MaterialTheme.typography.titleMedium)
                Text("Language selection and speech engines arrive later. No microphone or audio is active.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Eyebrow("About") }
        item {
            SettingsSurface {
                Text("Codex Voice", style = MaterialTheme.typography.titleMedium)
                Text("Version 0.3.0 · Experimental foundation")
                Text("A personal remote interface for the Codex Voice Bridge. Step 3 is an offline demonstration.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
@Composable
private fun SettingsSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
@Composable
private fun PreferenceSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, modifier = Modifier.semantics { contentDescription = title })
    }
}

