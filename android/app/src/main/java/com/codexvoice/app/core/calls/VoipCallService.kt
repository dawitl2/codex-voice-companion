package com.codexvoice.app.core.calls

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.telecom.DisconnectCause
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.telecom.*
import com.codexvoice.app.CompanionRuntime
import com.codexvoice.app.MainActivity
import com.codexvoice.app.R
import com.codexvoice.app.core.notifications.*
import com.codexvoice.app.core.remote.KeystoreCredentialStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Supported self-managed VoIP + CallStyle. Incoming presentation never starts a microphone. */
class VoipCallService:Service(){
    companion object {const val INCOMING="com.codexvoice.voip.INCOMING";const val OUTGOING="com.codexvoice.voip.OUTGOING";const val END="com.codexvoice.voip.END"
        fun incoming(context:android.content.Context,event:PushEvent){try{ContextCompat.startForegroundService(context,Intent(context,VoipCallService::class.java).setAction(INCOMING).putExtra(TaskNotifications.EXTRA_EVENT,event.eventId))}catch(_:Exception){TaskNotifications.post(context,event,PushStore.get(context).state.value.preferences)}}}
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var runtime:CompanionRuntime?=null;private var ringer:IncomingCallRinger?=null;private var added=false;private var event:PushEvent?=null
    private var activeNotified=false
    private var answered=false
    private val manager by lazy{CallsManager(this)}
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==END){runtime?.calls?.let{if(CallRuntime.state.value.phase==LivePhase.OFFERED)it.decline()else it.end()};stopSelf();return START_NOT_STICKY}
        if(added)return START_NOT_STICKY;added=true
        runtime=CompanionRuntime.acquire(applicationContext).also{it.holdInvitation()}
        try{
            if(intent?.action==INCOMING){
                val id=intent.getStringExtra(TaskNotifications.EXTRA_EVENT)?:error("invalid_invitation")
                event=PushStore.get(this).state.value.events.find{it.eventId==id}?.let{PushParser.parse(it.data(),KeystoreCredentialStore(this).load(),System.currentTimeMillis())}?:error("expired_invitation")
                TelecomRuntime.claimed=true;ongoing(true);runtime!!.calls.open(id)
                scope.launch{
                    val offer=withTimeoutOrNull(20000){CallRuntime.state.first{it.offer?.eventId==id&&it.phase==LivePhase.OFFERED}}?.offer
                    if(offer==null){event?.let{TaskNotifications.post(this@VoipCallService,it,PushStore.get(this@VoipCallService).state.value.preferences)};stopSelf();return@launch}
                    TelecomRuntime.claimed=true;TelecomRuntime.callId=offer.callId
                    ongoing(true)
                    TaskNotifications.cancel(this@VoipCallService,id)
                    val prefs=PushStore.get(this@VoipCallService).state.value.preferences
                    ringer=IncomingCallRinger(this@VoipCallService,offer.expiresAt).also{it.start(prefs.sound)}
                    scope.launch{delay((offer.expiresAt-System.currentTimeMillis()).coerceIn(1,30000));if(CallRuntime.state.value.phase==LivePhase.OFFERED){runtime?.calls?.decline();CallRuntime.update{it.copy(phase=LivePhase.EXPIRED)};stopSelf()}}
                    add(offer,true)
                }
            }else if(intent?.action==OUTGOING){val offer=CallRuntime.state.value.offer?:error("no_call");require(offer.callId==intent.getStringExtra("call_id"));ongoing(false);scope.launch{add(offer,false)}}
            else stopSelf()
        }catch(_:Exception){stopSelf()}
        scope.launch{CallRuntime.state.collect{live->
            if(live.phase!=LivePhase.OFFERED)ringer?.pause()
            if(TelecomRuntime.callId.isNotBlank()&&live.offer?.callId==TelecomRuntime.callId){
                if(live.phase==LivePhase.ACTIVE){answered=true;if(!activeNotified&&TelecomRuntime.control!=null){activeNotified=true;TelecomRuntime.control?.setActive();ongoing(false)}}
                if(live.phase in setOf(LivePhase.ENDED,LivePhase.FAILED,LivePhase.EXPIRED,LivePhase.DECLINED,LivePhase.IDLE)){TelecomRuntime.control?.disconnect(DisconnectCause(DisconnectCause.LOCAL));stopSelf()}
            }
        }}
        scope.launch{com.codexvoice.app.core.conversation.ConversationRuntime.state.collect{if(TelecomRuntime.callId.isNotBlank())ongoing(CallRuntime.state.value.phase==LivePhase.OFFERED)}}
        scope.launch{CallNotifications.visible.collect{ongoing(event!=null&&!answered&&CallRuntime.state.value.phase !in setOf(LivePhase.ACTIVE,LivePhase.RECONNECTING,LivePhase.ENDED,LivePhase.DECLINED,LivePhase.EXPIRED,LivePhase.FAILED))}}
        scope.launch{delay(1800000);runtime?.calls?.end();stopSelf()}
        return START_NOT_STICKY
    }
    private suspend fun add(offer:CallOffer,incoming:Boolean){
        try{
            manager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
            manager.addCall(CallAttributesCompat("Codex Voice",Uri.parse("codexvoice:${offer.callId}"),if(incoming)CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,CallAttributesCompat.CALL_TYPE_AUDIO_CALL),
                onAnswer={TelecomRuntime.incomingIntent(this@VoipCallService,offer.eventId,true).send()},
                onDisconnect={runtime?.calls?.let{if(CallRuntime.state.value.phase==LivePhase.OFFERED)it.decline()else it.end()};stopSelf()},
                onSetActive={if(CallRuntime.state.value.phase!=LivePhase.ACTIVE)error("media_not_active")},
                onSetInactive={runtime?.calls?.end()}) {
                TelecomRuntime.control=this
                if(CallRuntime.state.value.phase==LivePhase.ACTIVE)launch{setActive();activeNotified=true;ongoing(false)}
                var available:List<CallEndpointCompat> = emptyList();var current:CallEndpointCompat?=null
                launch{availableEndpoints.collect{available=it;current?.let{c->TelecomRuntime.routes(available,c)}}}
                launch{currentCallEndpoint.collect{current=it;TelecomRuntime.routes(available,it)}}
                launch{isMuted.collect{TelecomRuntime.muted.value=it}}
            }
        }catch(_:CancellationException){throw CancellationException()}
        catch(_:Exception){TelecomRuntime.claimed=false;CallRuntime.update{it.copy(problem="System VoIP integration unavailable; use the app call controls")}}
    }
    private fun ongoing(incoming:Boolean){
        val notifications=getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("codex_voip_calls","Codex Voice calls",NotificationManager.IMPORTANCE_HIGH).apply{setSound(null,null);enableVibration(PushStore.get(this@VoipCallService).state.value.preferences.vibration);lockscreenVisibility=Notification.VISIBILITY_PUBLIC})
        val end=PendingIntent.getService(this,710,Intent(this,VoipCallService::class.java).setAction(END),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val selection=com.codexvoice.app.core.conversation.ConversationRuntime.state.value.selection
        val thread=runtime?.client?.state?.value?.snapshot?.threads?.find{it.id==(selection?.threadId?:CallRuntime.state.value.offer?.threadId)}
        val identity=selection?.let{"${it.project} · ${it.label}"}?:thread?.let{"${it.project} · ${it.label}"}?:"Codex Voice"
        if(!incoming){startForeground(CallNotifications.ID,CallNotifications.ongoing(this,end,identity),ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);return}
        val open=event?.let{TelecomRuntime.incomingIntent(this,it.eventId,false)}?:PendingIntent.getActivity(this,711,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val inApp=CallNotifications.visible.value
        if(inApp)CallNotifications.quietChannel(this)
        val builder=NotificationCompat.Builder(this,if(inApp)CallNotifications.QUIET_CHANNEL else "codex_voip_calls").setSmallIcon(R.drawable.ic_launcher).setContentTitle(identity).setContentText("Incoming private call")
            .setContentIntent(open).setCategory(NotificationCompat.CATEGORY_CALL).setOngoing(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setSilent(inApp)
            .setPriority(if(inApp)NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
        val person=Person.Builder().setName(identity).setImportant(true).build()
        if(incoming&&event!=null){val answer=TelecomRuntime.incomingIntent(this,event!!.eventId,true);builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(person,end,answer))
            if(!inApp&&(Build.VERSION.SDK_INT<34||notifications.canUseFullScreenIntent()))builder.setFullScreenIntent(open,true)
            builder.setTimeoutAfter((event!!.expires-System.currentTimeMillis()).coerceIn(1,120000))
        }else builder.setStyle(NotificationCompat.CallStyle.forOngoingCall(person,end))
        startForeground(CallNotifications.ID,builder.build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
    }
    override fun onDestroy(){event?.let{RecentCalls.record(this,it.eventId,if(answered)"Answered" else if(CallRuntime.state.value.phase==LivePhase.DECLINED)"Declined" else "Missed")};ringer?.close();scope.cancel();TelecomRuntime.clear();runtime?.releaseInvitation();runtime=null;stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()}
}
