package com.codexvoice.app.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.codexvoice.app.core.notifications.LocalFirebaseConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun FirebaseClientSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf(LocalFirebaseConfiguration.project(context)) }
    var message by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            try {
                withContext(Dispatchers.IO) { LocalFirebaseConfiguration.importClient(context, uri) }
                project = LocalFirebaseConfiguration.project(context)
                message = "Saved on this phone. Toggle notifications off and on to register."
            } catch (e: Exception) { message = when (e) {
                is IllegalArgumentException, is IllegalStateException -> e.message ?: "Invalid Android client configuration"
                else -> "Could not import. Choose google-services.json from your Firebase Android app."
            } } finally { importing = false }
        }
    }
    Column {
        Text(project?.let { "Firebase project: $it" } ?: "Firebase client not configured", style = MaterialTheme.typography.bodySmall)
        TextButton({ picker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }, enabled = !importing) { Text("Import Android Firebase client") }
        if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
    }
}
