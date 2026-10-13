package com.codexvoice.app.core.calls
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class RecentCall(val event:String,val status:String,val time:Long)
/** Bounded call outcomes only. No audio, transcripts, tokens or private task content. */
object RecentCalls{
    fun list(context:Context):List<RecentCall> = try{val rows=JSONArray(context.getSharedPreferences("recent-calls",Context.MODE_PRIVATE).getString("rows","[]"));(0 until rows.length()).map{rows.getJSONObject(it).let{v->RecentCall(v.getString("event"),v.getString("status"),v.getLong("time"))}}.take(12)}catch(_:Exception){emptyList()}
    @Synchronized fun record(context:Context,event:String,status:String){val rows=JSONArray();(listOf(RecentCall(event,status,System.currentTimeMillis()))+list(context).filter{it.event!=event}).take(12).forEach{rows.put(JSONObject().put("event",it.event).put("status",it.status).put("time",it.time))};context.getSharedPreferences("recent-calls",Context.MODE_PRIVATE).edit().putString("rows",rows.toString()).commit()}
}
