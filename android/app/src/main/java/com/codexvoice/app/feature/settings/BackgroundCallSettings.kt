package com.codexvoice.app.feature.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.codexvoice.app.BackgroundCompanionService
import com.codexvoice.app.core.notifications.TaskNotifications

/** Opens Android settings only. The user grants every permission themselves. */
@Composable fun BackgroundCallSettings(){
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    var revision by remember{mutableIntStateOf(0)}
    DisposableEffect(owner){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)revision++}
        owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer)}
    }
    val status by BackgroundCompanionService.status.collectAsState()
    val microphone=remember(revision){ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED}
    val battery=remember(revision){context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)}
    val fullScreen=remember(revision){Build.VERSION.SDK_INT<34||context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()}
    val appSettings={context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))}
    Column {
        Text(status,style=MaterialTheme.typography.bodyMedium)
        Text("Switch apps or lock the screen. Stop all turns the companion off.",style=MaterialTheme.typography.bodySmall)
        TextButton({context.startActivity(TaskNotifications.systemSettings(context))}){Text("Call notifications")}
        TextButton(appSettings){Text(if(microphone)"Microphone · allowed" else "Microphone permission")}
        if(Build.VERSION.SDK_INT>=34)TextButton({context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,Uri.parse("package:${context.packageName}")))}){Text(if(fullScreen)"Full-screen calls · allowed" else "Allow full-screen calls")}
        TextButton({context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))}){Text(if(battery)"Background battery use · unrestricted" else "Allow background battery use")}
        Text("Internet calls use Android's own-call integration; a Phone permission is not needed. Full-screen calls are a separate Android setting. While you use another app, Android may show an incoming-call banner. Answer starts the microphone.",style=MaterialTheme.typography.bodySmall)
    }
}
