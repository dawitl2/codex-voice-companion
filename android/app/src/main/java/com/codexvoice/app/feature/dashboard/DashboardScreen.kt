package com.codexvoice.app.feature.dashboard
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.codexvoice.app.core.designsystem.*
import com.codexvoice.app.core.model.*
import com.codexvoice.app.core.remote.RemoteStatus
import com.codexvoice.app.feature.settings.label

@Composable
fun DashboardScreen(state: DashboardState, reduceMotion: Boolean, onDemo: (CallReason) -> Unit, onHistory: () -> Unit, onSettings: () -> Unit = {}, onOpenCall: () -> Unit = {},onControls:()->Unit={},onCallCodex:()->Unit={},onChat:()->Unit={},onSelect:(String)->Unit={},onConnect:()->Unit={},onStopAll:()->Unit={}) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val recentCalls=com.codexvoice.app.core.calls.RecentCalls.list(context)
    var choosing by remember { mutableStateOf(false) }
    CompanionBackdrop(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CodexCloud(Modifier.size(88.dp))
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.demoMode) DemoBadge() else Text(if (state.remote?.snapshot?.mode == "mock") "PC BRIDGE MOCK · SIMULATED" else "PRIVATE PC MONITOR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Computer, null, tint = MaterialTheme.colorScheme.primary)
                        Text(if (state.demoMode) "PC bridge is not connected" else state.remote?.status?.label() ?: "No PC paired", style = MaterialTheme.typography.titleMedium)
                    }
                    state.remote?.problem?.takeIf { it.isNotEmpty() && !state.demoMode }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (!state.demoMode) {
                        Text(state.remote?.bridgeName?.ifBlank{"Paired PC"}?:"No PC paired",style=MaterialTheme.typography.bodyMedium)
                        if(state.remote?.lastConnected!=null&&state.remote.lastConnected>0)Text("Last connected · ${formattedTime(state.remote.lastConnected)}",style=MaterialTheme.typography.bodySmall)
                        if(state.remote?.status!=RemoteStatus.CONNECTED)Button(if(state.remote?.paired==true)onConnect else onSettings,modifier=Modifier.fillMaxWidth(),enabled=state.remote?.status !in setOf(RemoteStatus.CONNECTING,RemoteStatus.AUTHENTICATING,RemoteStatus.PAIRING,RemoteStatus.RECONNECTING)){Text(if(state.remote?.paired==true)"Connect" else "Pair your PC")}
                        state.remote?.snapshot?.let { Text("Last synchronized · ${formattedTime(it.syncedAt)}", style = MaterialTheme.typography.bodySmall) }
                        if (state.remote?.status != RemoteStatus.CONNECTED && state.remote?.snapshot != null) Text("Last known state · may be stale", color = MaterialTheme.colorScheme.error)
                        TextButton(onSettings) { Icon(Icons.Default.Settings,null,Modifier.size(20.dp));Spacer(Modifier.width(10.dp));Text("Connection settings");Spacer(Modifier.weight(1f));Icon(Icons.Default.ChevronRight,null) }
                        if(state.remote?.enabled==true)TextButton(onStopAll,colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Text("Stop all")}
                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))
                        val selected=state.remote?.snapshot?.threads?.find{it.id==state.remote.snapshot?.selectedThreadId}
                        Row(Modifier.fillMaxWidth().clickable { choosing=true },verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(selected?.project ?: "Choose a conversation",style=MaterialTheme.typography.titleMedium)
                                selected?.let { Text(it.label,style=MaterialTheme.typography.bodyMedium,maxLines=2) }
                            }
                            Icon(Icons.Default.ChevronRight,"Choose conversation",tint=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onCallCodex,enabled=state.remote?.status==RemoteStatus.CONNECTED&&selected!=null,modifier=Modifier.fillMaxWidth().height(48.dp),colors=ButtonDefaults.buttonColors(containerColor=androidx.compose.ui.graphics.Color(0xFF30B963),contentColor=androidx.compose.ui.graphics.Color.White)){Icon(Icons.Default.Call,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("Call Codex")}
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){TextButton(onChat,enabled=state.remote?.status==RemoteStatus.CONNECTED){Icon(Icons.Default.ChatBubbleOutline,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("Text Codex")};TextButton(onControls,enabled=state.remote?.status==RemoteStatus.CONNECTED){Icon(Icons.Default.FormatListBulleted,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("Task controls")}}
                    }
                }
            }
        }
        item { Eyebrow(if (state.demoMode) "Current task · sample" else "Current task") }
        if(!state.demoMode&&recentCalls.isNotEmpty())item {
            Surface(Modifier.fillMaxWidth().clickable(onClick=onHistory),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.8f)) {
                Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Default.Call,null,tint=Color(0xFF22BF91),modifier=Modifier.size(32.dp))
                    Column(Modifier.weight(1f)) { Text("Recent calls",style=MaterialTheme.typography.titleMedium);recentCalls.first().let { Text(it.status+" · "+java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(it.time)),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
                    Icon(Icons.Default.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { state.current?.let { TaskCard(it, reduceMotion) } ?: EmptyState("No active task", "Ready when you are.") }
        if (state.demoMode) item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Try a demo call", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Simulated · Microphone off", color = MaterialTheme.colorScheme.onPrimaryContainer)
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
        if (state.history.isEmpty()) item { EmptyState("A quiet workspace", if (state.demoMode) "No sample activity. Reset the demo from History." else "Your completed tasks appear here.") }
        items(state.history.take(2), key = { it.id }) { TaskCard(it, reduceMotion) }
    }
    }
    if(choosing) AlertDialog(onDismissRequest={choosing=false},title={Text("Choose a conversation")},text={
        Column { (state.remote?.snapshot?.threads ?: emptyList()).forEach { thread ->
            TextButton({choosing=false;onSelect(thread.id)},enabled=state.remote?.status==RemoteStatus.CONNECTED) {
                Column(Modifier.fillMaxWidth()) { Text(thread.label,style=MaterialTheme.typography.titleMedium);Text(thread.project,style=MaterialTheme.typography.bodySmall) }
            }
        };if(state.remote?.snapshot?.threads.isNullOrEmpty()) Text("Connect to your PC to synchronize the latest conversation.") }
    },confirmButton={TextButton({choosing=false}){Text("Done")}})
}
