package com.codexvoice.app.feature.call

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codexvoice.app.core.conversation.*
import com.codexvoice.app.core.remote.RemoteThread

@OptIn(ExperimentalLayoutApi::class)
@Composable fun ConversationPanel(state:ConversationState,threads:List<RemoteThread>,onConfirm:(String)->Unit,onCancel:(String)->Unit,
    onCorrect:(String,String)->Unit,onSelect:(String)->Unit,onEdit:(String)->Unit={}) {
    if(!state.enabled)return
    var selectOpen by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(conversationLabel(state),style=MaterialTheme.typography.titleMedium)
        Box {
            TextButton({selectOpen=true},enabled=state.phase!="submitting"){Text(state.selection?.let{"${it.project} · ${it.label}"} ?: "Choose conversation")}
            DropdownMenu(selectOpen,{selectOpen=false}){for(thread in threads)DropdownMenuItem(text={Text("${thread.project} · ${thread.label}")},onClick={selectOpen=false;onSelect(thread.id)})}
        }
        state.proposal?.let{proposal->
            var edited by remember(proposal.id){mutableStateOf(proposal.text)}
            Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerHigh){
                Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Send to ${proposal.project}?",style=MaterialTheme.typography.labelLarge)
                    if(state.phase=="countdown"||state.phase=="dictating"){Text(proposal.text);TextButton({onEdit(proposal.id)}){Text("Pause to edit")}}
                    else OutlinedTextField(edited,{if(it.length<=2000)edited=it},label={Text("Proposed instruction")},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=5)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button({if(edited==proposal.text)onConfirm(proposal.id)else onCorrect(proposal.id,edited)},enabled=edited.isNotBlank()) {Text(if(edited==proposal.text)"Send Now" else "Review correction")}
                        TextButton({onCancel(proposal.id)}){Text("Cancel instruction")}
                    }
                    Text(if(state.phase=="countdown")"Auto-send is active. Cancel or pause to edit." else "Review, then Send Now or say “yes, send it”.",style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        for(entry in state.entries.takeLast(4)) {
            var expanded by remember(entry.id){mutableStateOf(false)}
            Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainer){
                Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(if(entry.kind=="codex")"Codex response" else "Voice status",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(entry.text,style=MaterialTheme.typography.bodyMedium,maxLines=if(expanded)Int.MAX_VALUE else 6)
                    if(entry.text.length>400)TextButton({expanded=!expanded}){Text(if(expanded)"Show less" else "Read full response")}
                }
            }
        }
        if(state.error=="submission_unknown")Text("Acceptance is uncertain. Check the PC; this instruction will not be resent automatically.",style=MaterialTheme.typography.bodySmall)
    }
}
