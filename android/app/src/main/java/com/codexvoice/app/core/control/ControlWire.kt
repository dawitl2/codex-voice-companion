package com.codexvoice.app.core.control

import com.google.gson.JsonObject
import com.codexvoice.app.core.remote.RemoteProtocol
import java.time.Instant

data class ControlOperation(val id:String,val kind:String,val state:String,val turnId:String,val expiresAt:Long,val text:String)
data class ApprovalReview(val id:String,val requestId:String,val turnId:String,val itemId:String?,val category:String,val preview:String,val reason:String,val scope:String,val risk:String,val status:String,val serverConfirmed:Boolean,val expiresAt:Long,val allowed:List<String>)
data class ControlTask(val id:String,val status:String,val result:String)
data class ApprovalChallenge(val id:String,val transactionId:String,val message:String,val expiresAt:Long)
data class ControlState(val threadId:String="",val project:String="",val mode:String="",val task:ControlTask?=null,val progress:String="",val operations:List<ControlOperation> = emptyList(),val approvals:List<ApprovalReview> = emptyList(),val fingerprint:String="",val trusted:Boolean=false,val error:String="",val challenge:ApprovalChallenge?=null,val authorizingApprovalId:String="",val connected:Boolean=false)
object ControlWire {
    private fun keys(p:JsonObject,vararg names:String){require(p.keySet()==names.toSet())}
    private fun s(p:JsonObject,k:String,max:Int=128)=RemoteProtocol.string(p,k,max)
    private fun text(p:JsonObject,k:String,max:Int):String {val v=p[k];require(v.isJsonPrimitive&&v.asJsonPrimitive.isString);return v.asString.also{require(it.length<=max&&it.none{c->c.code in 0..8||c.code in 11..31||c.code==127})}}
    private fun id(p:JsonObject,k:String)=s(p,k).also{require(it.matches(Regex("[A-Za-z0-9_-]{1,128}")))}
    private fun uuid(p:JsonObject,k:String)=s(p,k,36).also{require(it.matches(Regex("[a-f0-9-]{36}")))}
    private fun date(p:JsonObject,k:String)=Instant.parse(s(p,k,40)).toEpochMilli()
    private fun bool(p:JsonObject,k:String):Boolean {val v=p[k];require(v.isJsonPrimitive&&v.asJsonPrimitive.isBoolean);return v.asBoolean}
    fun validate(type:String,p:JsonObject){when(type){
        "control.error"->{keys(p,"code");s(p,"code",64)}
        "control.approval.challenge.response"->challenge(p)
        "control.status.response"->status(p)
        else->error("Unknown control message")
    }}
    fun challenge(p:JsonObject):ApprovalChallenge {keys(p,"id","transactionId","message","expiresAt");return ApprovalChallenge(uuid(p,"id"),uuid(p,"transactionId"),text(p,"message",8192),date(p,"expiresAt"))}
    fun status(p:JsonObject):ControlState {
        keys(p,"threadId","project","mode","task","progress","operations","approvals","reviewer")
        val mode=s(p,"mode",4);require(mode in setOf("mock","real"))
        val task=if(p["task"].isJsonNull)null else p.getAsJsonObject("task").let{t->keys(t,"id","status","result");val state=s(t,"status",32);require(state in setOf("starting","running","awaitingApproval","completed","failed","interrupted","unknown"));ControlTask(id(t,"id"),state,text(t,"result",16000))}
        val operations=p.getAsJsonArray("operations").also{require(it.size()<=8)}.map{v->v.asJsonObject.let{o->keys(o,"id","kind","state","turnId","expiresAt","text");val kind=s(o,"kind",16);require(kind in setOf("queue","interrupt","deny"));ControlOperation(uuid(o,"id"),kind,s(o,"state",32),id(o,"turnId"),date(o,"expiresAt"),text(o,"text",2000))}}
        require(operations.map{it.id}.distinct().size==operations.size)
        val approvals=p.getAsJsonArray("approvals").also{require(it.size()<=8)}.map{v->v.asJsonObject.let{a->
            keys(a,"id","requestId","turnId","itemId","category","preview","reason","scope","risk","status","serverConfirmed","createdAt","expiresAt","allowedDecisions");date(a,"createdAt")
            val decisions=a.getAsJsonArray("allowedDecisions").also{require(it.size()<=2)}.map{require(it.isJsonPrimitive&&it.asJsonPrimitive.isString);it.asString.also{d->require(d in setOf("accept","decline"))}}
            ApprovalReview(uuid(a,"id"),s(a,"requestId"),id(a,"turnId"),if(a["itemId"].isJsonNull)null else id(a,"itemId"),s(a,"category",64),text(a,"preview",4000),text(a,"reason",1000),text(a,"scope",1000),s(a,"risk",240),s(a,"status",32),bool(a,"serverConfirmed"),date(a,"expiresAt"),decisions)
        }}
        require(approvals.map{it.id}.distinct().size==approvals.size)
        val reviewer=if(p["reviewer"].isJsonNull)null else p.getAsJsonObject("reviewer").also{keys(it,"fingerprint","trusted");require(s(it,"fingerprint",64).matches(Regex("[a-f0-9]{64}")));bool(it,"trusted")}
        return ControlState(id(p,"threadId"),s(p,"project",80),mode,task,text(p,"progress",240),operations,approvals,reviewer?.let{s(it,"fingerprint",64)}?:"",reviewer?.let{bool(it,"trusted")}?:false,connected=true)
    }
}
