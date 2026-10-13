package com.codexvoice.app.feature.conversation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codexvoice.app.core.conversation.ChatState
import com.codexvoice.app.core.remote.RemoteThread

@Composable fun ChatScreen(state:ChatState,threads:List<RemoteThread>,onSelect:(String)->Unit,onSend:(String)->Unit,onBack:()->Unit,onControls:()->Unit){
    var draft by remember(state.threadId){mutableStateOf("")};var choose by remember{mutableStateOf(false)}
    Surface(Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onBack){Text("Back")};TextButton(onControls){Text("Task controls")}}
            Box{TextButton({choose=true}){Text(state.label.ifBlank{"Choose conversation"})};DropdownMenu(choose,{choose=false}){threads.forEach{t->DropdownMenuItem(text={Text(t.project+" · "+t.label)},onClick={choose=false;onSelect(t.id)})}}}
            Text(state.project+" · "+state.policy+if(state.mode=="mock")" · Simulated" else "",style=MaterialTheme.typography.bodySmall)
            if(state.error.isNotBlank())Text(state.error,color=MaterialTheme.colorScheme.error)
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(16.dp)){
                if(state.turns.isEmpty())item{Text("Your confirmed instructions and Codex responses appear here.",style=MaterialTheme.typography.bodyMedium)}
                items(state.turns,key={it.id}){turn->Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                    if(turn.instruction.isNotBlank()){Text("You",style=MaterialTheme.typography.labelSmall);Text(turn.instruction)}
                    Text("Codex · "+turn.status,style=MaterialTheme.typography.labelSmall);Text(turn.response.ifBlank{"Waiting for the actual response…"});HorizontalDivider()
                }}
            }
            Text(state.progress,style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(draft,{draft=it.take(2000)},label={Text("Instruction to this conversation")},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=4)
            val busy=state.turns.lastOrNull()?.status in setOf("starting","running","awaitingApproval","unknown")
            Button({onSend(draft);draft=""},enabled=state.connected&&state.selected&&!state.sending&&!busy&&draft.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text(if(state.sending)"Sending…" else "Send instruction")}
            Text("Send confirms this exact instruction and selected project.",style=MaterialTheme.typography.bodySmall)
        }
    }
}
