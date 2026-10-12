package com.codexvoice.app.core.conversation

import com.google.gson.JsonObject
import com.codexvoice.app.core.remote.RemoteProtocol
import java.time.Instant

data class ConversationSelection(val threadId:String,val label:String,val project:String,val mode:String)
data class VoiceProposal(val id:String,val text:String,val threadId:String,val project:String,val expiresAt:Long)
data class VoiceSubmission(val id:String,val threadId:String,val turnId:String?,val state:String,val submittedAt:Long,val acceptedAt:Long?,val firstOutputAt:Long?,val finishedAt:Long?)
data class ConversationEntry(val id:String,val sequence:Long,val kind:String,val text:String,val spoken:String,val timestamp:Long,val receivedAt:Long)
data class ConversationState(val callId:String="",val enabled:Boolean=false,val phase:String="ready",val error:String="",val selection:ConversationSelection?=null,
    val proposal:VoiceProposal?=null,val submission:VoiceSubmission?=null,val entries:List<ConversationEntry> = emptyList(),val lastSpokenSequence:Long=0)
object ConversationWire {
    private fun keys(p:JsonObject,fields:Set<String>){require(p.keySet()==fields)}
    private fun string(p:JsonObject,key:String,max:Int)=RemoteProtocol.string(p,key,max)
    private fun uuid(p:JsonObject,key:String)=string(p,key,36).also{require(it.matches(Regex("[a-f0-9-]{36}")))}
    private fun id(p:JsonObject,key:String)=string(p,key,128).also{require(it.matches(Regex("[A-Za-z0-9_-]{1,128}")) && it !in setOf("__proto__","constructor","prototype"))}
    private fun number(p:JsonObject,key:String):Long {val v=p[key].asJsonPrimitive;require(v.isNumber);return v.asBigDecimal.longValueExact().also{require(it in 0..9007199254740991L)}}
    private fun date(p:JsonObject,key:String)=Instant.parse(string(p,key,40)).toEpochMilli()
    private fun nullableDate(p:JsonObject,key:String)=if(p[key].isJsonNull)null else date(p,key)
    private fun prose(p:JsonObject,key:String):String {val v=p[key].asJsonPrimitive;require(v.isString);return v.asString.also{require(it.isNotEmpty() && it.length<=16000 && it.none{c->c.code in 0..8 || c.code in 11..31 || c.code==127})}}
    private val submissionStates=setOf("submitting","accepted","running","awaitingApproval","completed","failed","interrupted","unknown","rejected")
    fun validate(type:String,p:JsonObject){
        if(type=="conversation.error"){keys(p,setOf("code"));require(string(p,"code",64).matches(Regex("[a-z_]+")));return}
        require(type=="conversation.status.response");parse(p)
    }
    fun parse(p:JsonObject,receivedAt:Long=System.currentTimeMillis()):ConversationState {
        keys(p,setOf("callId","phase","error","selection","proposal","submission","entries"));val callId=uuid(p,"callId")
        val phase=string(p,"phase",32);require(phase in submissionStates+setOf("ready","awaiting_confirmation","cancelled","expired","busy","error"))
        val error=p["error"].asJsonPrimitive;require(error.isString && error.asString.matches(Regex("[a-z_]{0,64}")))
        val selection=if(p["selection"].isJsonNull)null else p["selection"].asJsonObject.let{s->keys(s,setOf("threadId","label","project","mode"));val mode=string(s,"mode",4);require(mode in setOf("mock","real"));ConversationSelection(id(s,"threadId"),string(s,"label",80),string(s,"project",80),mode)}
        val proposal=if(p["proposal"].isJsonNull)null else p["proposal"].asJsonObject.let{s->keys(s,setOf("id","text","expiresAt","threadId","project"));VoiceProposal(uuid(s,"id"),string(s,"text",2000),id(s,"threadId"),string(s,"project",80),date(s,"expiresAt"))}
        val submission=if(p["submission"].isJsonNull)null else p["submission"].asJsonObject.let{s->keys(s,setOf("id","threadId","turnId","state","submittedAt","acceptedAt","firstOutputAt","finishedAt"));val state=string(s,"state",32);require(state in submissionStates);VoiceSubmission(uuid(s,"id"),id(s,"threadId"),if(s["turnId"].isJsonNull)null else id(s,"turnId"),state,date(s,"submittedAt"),nullableDate(s,"acceptedAt"),nullableDate(s,"firstOutputAt"),nullableDate(s,"finishedAt"))}
        val rows=p["entries"].asJsonArray;require(rows.size()<=4);var last=0L
        val entries=rows.map{v->v.asJsonObject.let{e->keys(e,setOf("id","sequence","kind","text","spoken","timestamp"));val sequence=number(e,"sequence");require(sequence>last);last=sequence;val kind=string(e,"kind",8);require(kind in setOf("system","codex"));ConversationEntry(uuid(e,"id"),sequence,kind,prose(e,"text"),string(e,"spoken",1000),date(e,"timestamp"),receivedAt)}}
        return ConversationState(callId,true,phase,error.asString,selection,proposal,submission,entries)
    }
}
fun conversationLabel(state:ConversationState)=when(state.phase){
    "awaiting_confirmation"->"Review instruction";"submitting"->"Sending instruction…";"running","accepted"->"Codex working";"awaitingApproval"->"Approval needed on PC"
    "completed"->"Turn completed";"failed"->"Turn failed";"interrupted"->"Turn interrupted";"unknown"->"Submission uncertain";"expired"->"Confirmation expired";"busy"->"Conversation busy";"rejected"->"Instruction not sent";else->"Ready for your next request"
}
