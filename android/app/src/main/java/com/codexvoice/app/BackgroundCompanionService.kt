package com.codexvoice.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.codexvoice.app.core.calls.*
import com.codexvoice.app.core.remote.KeystoreCredentialStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** User-started phone/PC messaging continuity. Never starts or authorizes a microphone. */
class BackgroundCompanionService : Service() {
    companion object {
        private const val ID = CallNotifications.ID
        private const val CHANNEL = "codex_background_companion"
        private const val START = "com.codexvoice.companion.START"
        const val STOP = "com.codexvoice.companion.STOP"
        @Volatile private var starting = false
        @Volatile private var running = false
        private val mutableStatus = MutableStateFlow("Stopped")
        val status = mutableStatus.asStateFlow()
        fun enabled(context: Context) = context.getSharedPreferences("background-companion", Context.MODE_PRIVATE).getBoolean("enabled", true)
        @Synchronized fun start(context: Context, userRequested: Boolean = false) {
            if(userRequested)context.getSharedPreferences("background-companion",Context.MODE_PRIVATE).edit().putBoolean("enabled",true).commit()
            if(!enabled(context)||running||starting)return
            val credential=try{KeystoreCredentialStore(context).load()}catch(_:Exception){null}
            if(credential==null||(!userRequested&&!credential.enabled))return
            if(context.getSharedPreferences("connection-mode",Context.MODE_PRIVATE).getBoolean("demo",false))return
            starting=true;mutableStatus.value="Starting background companion…"
            try{ContextCompat.startForegroundService(context,Intent(context,BackgroundCompanionService::class.java).setAction(START))}
            catch(_:Exception){starting=false;mutableStatus.value="Open Codex Voice and tap Connect to enable background operation"}
        }
        fun stop(context: Context) {
            context.getSharedPreferences("background-companion",Context.MODE_PRIVATE).edit().putBoolean("enabled",false).commit()
            CompanionRuntime.disconnectActive()
            CallRuntime.command(context,CallAudioService.END)
            context.stopService(Intent(context,VoipCallService::class.java))
            context.stopService(Intent(context,BackgroundCompanionService::class.java))
            mutableStatus.value="Stopped by you"
        }
        fun refresh(context:Context){if(running)try{context.startService(Intent(context,BackgroundCompanionService::class.java))}catch(_:Exception){}}
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var runtime:CompanionRuntime?=null
    private var networkRegistered=false
    private var backgroundNotification:Notification?=null
    private var foregroundType=0
    private val networks by lazy{getSystemService(ConnectivityManager::class.java)}
    private val callback=object:ConnectivityManager.NetworkCallback(){override fun onAvailable(network:Network){runtime?.client?.networkAvailable()}}
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate(){
        super.onCreate()
        val notifications=getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,"Background companion",NotificationManager.IMPORTANCE_LOW).apply{setSound(null,null);enableVibration(false);setShowBadge(false)})
        val open=PendingIntent.getActivity(this,ID,Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getBroadcast(this,ID,Intent(this,CompanionStopReceiver::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        backgroundNotification=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_launcher).setContentTitle("Codex Voice")
            .setContentText("Running in background").setContentIntent(open).addAction(0,"Stop all",stop).setOngoing(true)
            .setOnlyAlertOnce(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_LOW).setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        foregroundType=if(Build.VERSION.SDK_INT>=34)ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        // Keep an existing genuine call indicator while adding the connection lease.
        val current=notifications.activeNotifications.firstOrNull{it.id==ID&&it.tag==null}?.notification
        startForeground(ID,current?:backgroundNotification!!,foregroundType)
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val credential=try{KeystoreCredentialStore(this).load()}catch(_:Exception){null}
        if(!enabled(this)||credential==null||(intent==null&&!credential.enabled)||getSharedPreferences("connection-mode",MODE_PRIVATE).getBoolean("demo",false)){
            stopSelf();return START_NOT_STICKY
        }
        running=true;starting=false
        restoreIndicator()
        if(runtime==null){
            runtime=CompanionRuntime.acquire(applicationContext).also{it.client.backgroundCompanion(true)}
            try{networks.registerDefaultNetworkCallback(callback);networkRegistered=true}catch(_:Exception){/* Timed retries and push still apply. */}
            scope.launch{runtime!!.client.state.collect{state->
                mutableStatus.value=when(state.status){
                    com.codexvoice.app.core.remote.RemoteStatus.CONNECTED->"Running in background · connected"
                    com.codexvoice.app.core.remote.RemoteStatus.BRIDGE_UNAVAILABLE->"Running in background · Codex unavailable on PC"
                    else->"Running in background · reconnecting when available"
                }
                if(state.status in setOf(com.codexvoice.app.core.remote.RemoteStatus.AUTH_FAILED,com.codexvoice.app.core.remote.RemoteStatus.STORAGE_ERROR,com.codexvoice.app.core.remote.RemoteStatus.TLS_ERROR,com.codexvoice.app.core.remote.RemoteStatus.PROTOCOL_INCOMPATIBLE)){
                    mutableStatus.value="Connection needs attention · open Codex Voice";stopSelf()
                }
            }}
            scope.launch{CallRuntime.state.collectLatest{live->
                if(live.phase in setOf(LivePhase.IDLE,LivePhase.ENDED,LivePhase.DECLINED,LivePhase.EXPIRED,LivePhase.FAILED)&&!live.serviceRunning){
                    delay(500)
                    restoreIndicator()
                }
            }}
        }
        return START_STICKY
    }
    private fun restoreIndicator(){
        val live=CallRuntime.state.value
        if(enabled(this)&&!live.serviceRunning&&!TelecomRuntime.claimed&&live.phase in setOf(LivePhase.IDLE,LivePhase.ENDED,LivePhase.DECLINED,LivePhase.EXPIRED,LivePhase.FAILED))backgroundNotification?.let{startForeground(ID,it,foregroundType)}
    }
    override fun onDestroy(){
        if(networkRegistered)try{networks.unregisterNetworkCallback(callback)}catch(_:Exception){}
        scope.cancel();runtime?.client?.backgroundCompanion(false);runtime?.release();runtime=null
        running=false;starting=false
        if(!enabled(this))mutableStatus.value="Stopped by you"
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
}

class CompanionStopReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){if(intent.action==BackgroundCompanionService.STOP)BackgroundCompanionService.stop(context)}}
class CompanionResumeReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){
    if(intent.action in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED))BackgroundCompanionService.start(context)
}}
