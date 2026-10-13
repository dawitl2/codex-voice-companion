package com.codexvoice.app.core.conversation
import com.codexvoice.app.core.remote.*
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.util.UUID

data class ChatTurn(val id:String,val status:String,val instruction:String,val response:String)
data class ChatState(val threadId:String="",val label:String="",val project:String="",val policy:String="",val mode:String="real",val connected:Boolean=false,val selected:Boolean=false,val turns:List<ChatTurn> = emptyList(),val progress:String="",val error:String="",val sending:Boolean=false)
object ChatWire {
    fun validate(type:String,p:JsonObject){if(type=="chat.error"){require(p.keySet()==setOf("code"));require(RemoteProtocol.string(p,"code",64).matches(Regex("[a-z_]+")));return};require(type=="chat.status.response");parse(p)}
    fun parse(p:JsonObject):ChatState {
        require(p.keySet()==setOf("threadId","label","project","mode","policy","selected","turns","progress"))
        val rows=p.getAsJsonArray("turns");require(rows.size()<=12)
        val turns=rows.map{it.asJsonObject.let{r->require(r.keySet()==setOf("id","status","instruction","response","startedAt","finishedAt"));Instant.parse(RemoteProtocol.string(r,"startedAt",40));if(!r["finishedAt"].isJsonNull)Instant.parse(RemoteProtocol.string(r,"finishedAt",40));
            val status=RemoteProtocol.string(r,"status",32);require(status in setOf("starting","running","awaitingApproval","completed","failed","interrupted","unknown"));
            fun prose(k:String,max:Int)=r[k].asJsonPrimitive.let{v->require(v.isString&&v.asString.length<=max);v.asString}
            ChatTurn(RemoteProtocol.string(r,"id",128),status,prose("instruction",2000),prose("response",16000))}}
        val mode=RemoteProtocol.string(p,"mode",4);require(mode in setOf("real","mock"));val policy=RemoteProtocol.string(p,"policy",32);require(policy in setOf("read-only","workspace-write"));require(p["selected"].asJsonPrimitive.isBoolean)
        return ChatState(RemoteProtocol.string(p,"threadId",128),RemoteProtocol.string(p,"label",240),RemoteProtocol.string(p,"project",240),policy,mode,true,p["selected"].asBoolean,turns,RemoteProtocol.string(p,"progress",240))
    }
}
class ChatController(private val client:RemoteClient,parent:CoroutineScope) {
    private val scope=CoroutineScope(parent.coroutineContext+Dispatchers.IO.limitedParallelism(1));private val mutable=MutableStateFlow(ChatState());val state=mutable.asStateFlow()
    private val pending=mutableMapOf<String,Pair<String,Long>>();private var opened=false;private var selected="";private var ready=false
    init {
        scope.launch{client.state.collect{ready=it.status==RemoteStatus.CONNECTED;if(!ready){pending.clear();mutable.value=state.value.copy(connected=false,sending=false,error="Codex Voice is unavailable. Check your PC connection.")}}}
        scope.launch{client.chat.collect{m->val p=pending.remove(m.correlationId)?:return@collect;if(p.first!=selected)return@collect;
            if(m.type=="chat.error")mutable.value=state.value.copy(sending=false,error=when(m.payload["code"].asString){"submission_unknown"->"Acceptance uncertain. Check PC; do not resend.";"thread_busy"->"Task active or uncertain. Review its outcome first.";else->"Request unavailable. Reconnect or select the conversation again."})
            else mutable.value=ChatWire.parse(m.payload)
        }}
        scope.launch{while(isActive){delay(1200);val expired=pending.filterValues{System.currentTimeMillis()-it.second>35000}.keys;if(expired.isNotEmpty()){expired.forEach{pending.remove(it)};mutable.value=state.value.copy(sending=false,error="Request timed out. Check PC before resending.")};if(opened&&ready&&selected.isNotBlank()&&pending.isEmpty())request("chat.status.request")}}
    }
    private fun request(type:String,extra:Map<String,Any> = emptyMap()){if(!ready||selected.isBlank()||pending.size>=8)return;pending[client.chat(type,mapOf("threadId" to selected)+extra)]=selected to System.currentTimeMillis()}
    fun open(id:String)=scope.launch{opened=true;selected=id;mutable.value=ChatState(threadId=id,connected=ready);request("chat.status.request")}
    fun select(id:String)=scope.launch{opened=true;selected=id;mutable.value=ChatState(threadId=id,connected=ready);request("chat.select")}
    fun hide(){opened=false}
    fun send(text:String)=scope.launch{if(!ready||!state.value.selected||state.value.sending||text.isBlank())return@launch;mutable.value=state.value.copy(sending=true,error="");request("chat.send",mapOf("requestId" to UUID.randomUUID().toString(),"text" to text))}
}
