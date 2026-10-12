package com.codexvoice.app.feature.control

import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.unit.dp
import com.codexvoice.app.core.control.*
import com.codexvoice.app.core.remote.RemoteThread
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TaskControlSheet(state:ControlState,threads:List<RemoteThread>,onSelect:(String)->Unit,onDismiss:()->Unit,
    onInterrupt:()->Unit,onQueue:(String)->Unit,onConfirm:(String)->Unit,onCancel:(String)->Unit,onDeny:(ApprovalReview)->Unit,
    onRegister:()->Unit,onApprove:(ApprovalReview)->Unit,onAuthorized:(ApprovalChallenge,String?)->Unit){
    val activity=LocalActivity.current
    DisposableEffect(activity){val wasSecure=activity?.window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE)!=0
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose{if(!wasSecure)activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}
    }
    val challenge=state.challenge
    DisposableEffect(challenge?.id){val cancel=if(challenge!=null&&activity!=null)SecureApproval.authenticate(activity,challenge){signature->onAuthorized(challenge,signature)}else null;onDispose{cancel?.cancel()}}
    var now by remember{mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(Unit){while(true){delay(1000);now=System.currentTimeMillis()}}
    var expanded by remember{mutableStateOf(false)}
    var draft by remember(state.threadId){mutableStateOf("")}
    val queued=state.operations.lastOrNull{it.kind=="queue"&&it.state=="queued"}
    val proposed=state.operations.lastOrNull{it.state=="proposed"}
    ModalBottomSheet(onDismissRequest=onDismiss){
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Task controls",style=MaterialTheme.typography.titleLarge);TextButton(onDismiss){Text("Done")}}
            Box{TextButton({expanded=true}){Text(threads.find{it.id==state.threadId}?.label?:"Select conversation")}
                DropdownMenu(expanded,{expanded=false}){threads.forEach{thread->DropdownMenuItem(text={Text(thread.label+" · "+thread.project)},onClick={expanded=false;onSelect(thread.id)})}}}
            if(state.mode=="mock")Text("Simulated task",style=MaterialTheme.typography.labelSmall)
            Text(state.project,style=MaterialTheme.typography.bodyMedium)
            Text(state.task?.status?:"No task",style=MaterialTheme.typography.titleMedium)
            state.task?.let{Text("Turn · "+it.id.takeLast(12),style=MaterialTheme.typography.bodySmall)}
            if(state.progress.isNotBlank())Text(state.progress,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(state.error.isNotBlank())Text(state.error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            val active=state.connected&&state.task?.status in setOf("starting","running","awaitingApproval")
            TextButton(onInterrupt,enabled=active){Text("Interrupt task",color=MaterialTheme.colorScheme.error)}
            proposed?.let{op->
                HorizontalDivider()
                Text(when(op.kind){"interrupt"->"Interrupt this turn and keep the call open?";"deny"->"Deny this pending action?";else->"Send once after this turn completes successfully?"},style=MaterialTheme.typography.titleMedium)
                if(op.text.isNotBlank())Text(op.text)
                Text("Confirmation expires in ${((op.expiresAt-now)/1000).coerceAtLeast(0)}s",style=MaterialTheme.typography.bodySmall)
                Row{TextButton({onConfirm(op.id)},enabled=state.connected&&op.expiresAt>now){Text(when(op.kind){"interrupt"->"Confirm interruption";"deny"->"Confirm denial";else->"Queue once"})};TextButton({onCancel(op.id)}){Text("Cancel")}}
            }
            state.operations.lastOrNull{it.kind=="interrupt"&&it.state!="proposed"}?.let{Text("Interruption · ${it.state}",style=MaterialTheme.typography.bodySmall)}
            HorizontalDivider()
            Text("One follow-up",style=MaterialTheme.typography.titleMedium)
            queued?.let{op->Text(op.text);Text("Auto-send once after successful completion · expires in ${((op.expiresAt-now)/1000).coerceAtLeast(0)}s",style=MaterialTheme.typography.bodySmall);TextButton({onCancel(op.id)}){Text("Cancel queued instruction")}}
            OutlinedTextField(draft,{draft=it.take(2000)},modifier=Modifier.fillMaxWidth(),label={Text("Follow-up instruction")},maxLines=4)
            TextButton({queued?.let{onCancel(it.id)};proposed?.takeIf{it.kind=="queue"}?.let{onCancel(it.id)};onQueue(draft)},enabled=active&&state.task?.status!="awaitingApproval"&&draft.isNotBlank()){Text(if(queued==null)"Review follow-up" else "Replace and review")}
            state.operations.lastOrNull{it.kind=="queue"&&it.state !in setOf("proposed","queued")}?.let{Text("Follow-up · ${it.state}",style=MaterialTheme.typography.bodySmall)}
            HorizontalDivider()
            Text("Approvals",style=MaterialTheme.typography.titleMedium)
            state.approvals.filter{it.status=="pending"}.forEach{a->
                Text(if(a.category.contains("commandExecution"))"Command" else "File change",style=MaterialTheme.typography.titleSmall)
                Text(a.preview,style=MaterialTheme.typography.bodyMedium)
                Text(a.scope,style=MaterialTheme.typography.bodySmall)
                Text(a.reason,style=MaterialTheme.typography.bodySmall)
                Text(a.risk,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Request · ${a.requestId.takeLast(16)} · ${((a.expiresAt-now)/1000).coerceAtLeast(0)}s left",style=MaterialTheme.typography.bodySmall)
                Row{TextButton({onDeny(a)},enabled=state.connected&&a.expiresAt>now&&"decline" in a.allowed){Text("Review denial")}
                    TextButton({onApprove(a)},enabled=state.connected&&state.trusted&&a.expiresAt>now&&"accept" in a.allowed&&state.challenge==null){Text("Approve once")}}
            }
            if(state.approvals.none{it.status=="pending"})Text("No pending approvals",style=MaterialTheme.typography.bodySmall)
            state.approvals.lastOrNull{it.status!="pending"}?.let{Text("Last decision · ${it.status}"+if(it.serverConfirmed)" · server resolved" else " · server resolution unconfirmed",style=MaterialTheme.typography.bodySmall)}
            if(!state.trusted)TextButton(onRegister,enabled=state.connected){Text("Enable secure review")}
            if(state.fingerprint.isNotBlank()){
                Text(if(state.trusted)"Reviewer key trusted on PC" else "Compare and trust this reviewer key on PC",style=MaterialTheme.typography.bodySmall)
                Text(state.fingerprint.chunked(16).joinToString("\n"),style=MaterialTheme.typography.labelSmall)
            }
            state.task?.result?.takeIf{it.isNotBlank()}?.let{result->var show by remember(state.task.id){mutableStateOf(false)};TextButton({show=!show}){Text(if(show)"Hide last result" else "Read last result")};if(show)Text(result)}
        }
    }
}
