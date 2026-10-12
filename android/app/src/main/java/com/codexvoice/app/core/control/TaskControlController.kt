package com.codexvoice.app.core.control

import com.codexvoice.app.core.remote.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

class TaskControlController(private val client:RemoteClient,private val scope:CoroutineScope){
    private val mutable=MutableStateFlow(ControlState());val state=mutable.asStateFlow()
    private val pending=mutableMapOf<String,Pair<String,String>>()
    private var opened=false;private var selected="";private var ready=false
    private var approvalId="";private var transactionId=""
    private val jobs=mutableListOf<Job>()
    init{
        jobs+=scope.launch(Dispatchers.Main.immediate){client.state.collect{value->ready=value.status==RemoteStatus.CONNECTED;if(!ready){pending.clear();approvalId="";transactionId="";mutable.value=ControlState(threadId=selected,error="Connection unavailable")}}}
        jobs+=scope.launch(Dispatchers.Main.immediate){client.controls.collect{m->
            val target=pending.remove(m.correlationId)?:return@collect
            if(target.second!=selected)return@collect
            if(m.type=="control.error")mutable.value=state.value.copy(error=when(RemoteProtocol.string(m.payload,"code",64)){
                "controls_disabled"->"Enable task controls on the PC."
                "desktop_approval_required","desktop_action_required"->"This action needs review on your PC."
                "authorization_failed"->"Authentication was not accepted. Review on your PC."
                "queue_exists"->"Cancel or replace the existing follow-up first."
                "stale_control","task_unavailable","approval_unavailable"->"This request is no longer available. Review the current task."
                else->"The request could not be completed. Review the task on your PC."
            },challenge=null)
            else if(m.type=="control.approval.challenge.response"){
                val c=ControlWire.challenge(m.payload)
                if(c.transactionId==transactionId&&approvalId.isNotBlank()&&opened)mutable.value=state.value.copy(challenge=c,authorizingApprovalId=approvalId)
            }else{
                val incoming=ControlWire.status(m.payload);if(incoming.threadId!=selected)return@collect
                val c=state.value.challenge?.takeIf{challenge->incoming.approvals.any{it.id==approvalId&&it.status=="pending"&&"accept" in it.allowed}&&challenge.expiresAt>System.currentTimeMillis()}
                mutable.value=incoming.copy(challenge=c,authorizingApprovalId=if(c==null)"" else approvalId)
            }
        }}
        jobs+=scope.launch(Dispatchers.Main.immediate){while(isActive){delay(1500);if(opened&&ready&&selected.isNotBlank()&&pending.values.none{it.first=="poll"})send("control.status.request",kind="poll")}}
    }
    private fun send(type:String,payload:Map<String,Any> = emptyMap(),kind:String="action"){
        if(!ready||selected.isBlank()||pending.size>=8)return
        val id=client.control(type,mapOf("threadId" to selected)+payload);pending[id]=kind to selected
    }
    fun open(threadId:String){selected=threadId;opened=true;approvalId="";transactionId="";mutable.value=ControlState(threadId=threadId,connected=ready);send("control.status.request")}
    fun hide(){opened=false;approvalId="";transactionId="";mutable.value=ControlState(threadId=selected,connected=ready)}
    fun interrupt(){state.value.task?.let{send("control.interrupt.propose",mapOf("turnId" to it.id))}}
    fun queue(text:String){state.value.task?.let{send("control.queue.propose",mapOf("turnId" to it.id,"text" to text))}}
    fun confirm(id:String){send("control.confirm",mapOf("operationId" to id))}
    fun cancel(id:String){send("control.cancel",mapOf("operationId" to id))}
    fun deny(a:ApprovalReview){send("control.deny.propose",mapOf("turnId" to a.turnId,"approvalId" to a.id))}
    fun register(){try{send("control.reviewer.register",mapOf("publicKey" to SecureApproval.publicKey()))}catch(_:Exception){mutable.value=state.value.copy(error="Secure authentication unavailable; review on PC")}}
    fun approve(a:ApprovalReview){if(!ready||!state.value.trusted||a.status!="pending"||a.expiresAt<=System.currentTimeMillis()||"accept" !in a.allowed)return;approvalId=a.id;transactionId=UUID.randomUUID().toString();send("control.approval.challenge",mapOf("approvalId" to a.id,"transactionId" to transactionId))}
    fun authorized(challenge:ApprovalChallenge,signature:String?){
        if(state.value.challenge?.id!=challenge.id)return
        mutable.value=state.value.copy(challenge=null)
        if(signature==null||!ready||challenge.expiresAt<=System.currentTimeMillis()){mutable.value=state.value.copy(error="Authentication unavailable. Review on your PC.");return}
        send("control.approval.decide",mapOf("approvalId" to approvalId,"transactionId" to challenge.transactionId,"decision" to "accept","challengeId" to challenge.id,"signature" to signature));approvalId="";transactionId=""
    }
    fun close(){jobs.forEach{it.cancel()};pending.clear();hide()}
}
