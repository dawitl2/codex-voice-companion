package com.codexvoice.app.core.calls

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import com.codexvoice.app.MainActivity
import com.codexvoice.app.core.notifications.TaskNotifications
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/** Only local call service state. Notification actions carry a one-use UI proof, never media/pairing tokens. */
object TelecomRuntime {
    @Volatile var claimed=false
    @Volatile var callId=""
    @Volatile var control:CallControlScope?=null
    val muted=MutableStateFlow(false)
    private var endpoints:List<CallEndpointCompat> = emptyList()
    private val proofs=mutableMapOf<String,Triple<String,Boolean,Long>>()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    @Synchronized fun incomingIntent(context:Context,eventId:String,answer:Boolean):PendingIntent {
        proofs.entries.removeAll{it.value.third<=System.currentTimeMillis()}
        val proof=UUID.randomUUID().toString();proofs[proof]=Triple(eventId,answer,System.currentTimeMillis()+120000)
        return PendingIntent.getActivity(context,if(answer)708 else 709,Intent(context,MainActivity::class.java)
            .putExtra(TaskNotifications.EXTRA_EVENT,eventId).putExtra("incoming_proof",proof)
            .setAction(if(answer)"com.codexvoice.ANSWER" else "com.codexvoice.INCOMING")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    @Synchronized fun consumeProof(intent:Intent):Pair<String,Boolean>? {
        val value=proofs.remove(intent.getStringExtra("incoming_proof"))?:return null
        if(value.third<=System.currentTimeMillis()||value.first!=intent.getStringExtra(TaskNotifications.EXTRA_EVENT))return null
        return value.first to value.second
    }
    fun outgoing(context:Context,offer:CallOffer) {
        claimed=true;callId=offer.callId
        try { ContextCompat.startForegroundService(context,Intent(context,VoipCallService::class.java).setAction(VoipCallService.OUTGOING).putExtra("call_id",offer.callId)) }
        catch(_:Exception){claimed=false;CallRuntime.update{it.copy(problem="Platform call display unavailable; private audio remains available")}}
    }
    fun routes(available:List<CallEndpointCompat>,current:CallEndpointCompat) {
        endpoints=available
        val name=when(current.type){CallEndpointCompat.TYPE_SPEAKER->"Phone speaker";CallEndpointCompat.TYPE_EARPIECE->"Phone earpiece";CallEndpointCompat.TYPE_BLUETOOTH->"Bluetooth headset";CallEndpointCompat.TYPE_WIRED_HEADSET->"Wired headset";else->"System route"}
        CallRuntime.update{it.copy(route=name,speaker=current.type==CallEndpointCompat.TYPE_SPEAKER,routeSupported=available.any{e->e.type==CallEndpointCompat.TYPE_SPEAKER}&&available.any{e->e.type==CallEndpointCompat.TYPE_EARPIECE})}
    }
    fun speaker(value:Boolean){scope.launch{val endpoint=endpoints.firstOrNull{it.type==if(value)CallEndpointCompat.TYPE_SPEAKER else CallEndpointCompat.TYPE_EARPIECE};if(endpoint==null)CallRuntime.update{it.copy(problem="Requested call route unavailable")}else control?.requestEndpointChange(endpoint)}}
    fun clear(){control=null;claimed=false;callId="";endpoints=emptyList();muted.value=false; synchronized(this){proofs.clear()} }
}
