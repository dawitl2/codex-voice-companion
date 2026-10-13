package com.codexvoice.app.core.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.codexvoice.app.core.notifications.TaskNotifications
import com.google.gson.JsonParser
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** One authorized PCM session. Android APIs perform the configured 16 kHz conversion. */
class NativeAudioEngine(private val context: Context, private val grant: MediaGrant,
    private val stopped: (String, Boolean) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS).pingInterval(5, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    @Volatile private var record: AudioRecord? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var ready = false
    @Volatile private var muted = grant.call.muted
    @Volatile private var speechPaused = false
    private val recorderControl = Mutex()
    private var focus: AudioFocusRequest? = null
    private var previousMode: Int? = null
    private var previousDevice: AudioDeviceInfo? = null
    @Volatile private var systemMuted=false
    private val jitter = PcmJitterBuffer()
    private val sequence = FrameSequence()
    private var captureJob: Job? = null; private var playJob: Job? = null
    @Volatile private var lastMessage = SystemClock.elapsedRealtime()
    private var window = lastMessage; private var receivedInWindow = 0
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { routeState() }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { routeState() }
    }
    fun connect() {
        scope.launch{TelecomRuntime.muted.collect{systemMuted=it;if(ready)applyCaptureGate()}}
        if (grant.expiresAt <= System.currentTimeMillis()) { finish("Media authorization expired", false); return }
        val request = Request.Builder().url(grant.endpoint).header("Authorization", "Bearer ${grant.token}").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed.get()) { webSocket.cancel(); return }
                webSocket.send(Gson().toJson(mapOf("version" to 1, "type" to "media.hello", "callId" to grant.call.callId, "format" to CallWire.format)))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (closed.get()) return
                try {
                    require(text.toByteArray().size <= 1024)
                    val message = JsonParser.parseString(text).asJsonObject
                    require(message["version"].asInt == 1)
                    when (message["type"].asString) {
                        "media.ready" -> {
                            require(!ready && message.keySet() == setOf("version","type","callId","format") && message["callId"].asString == grant.call.callId)
                            CallWire.format(message.getAsJsonObject("format")); ready = true; CallDiagnostics.record(context, "media_ready"); scope.launch { startAudio() }
                        }
                        "media.metrics" -> {
                            require(ready && message.keySet() == setOf("version","type","callId","uplinkFrames","downlinkFrames","droppedFrames","sequenceErrors","inputLevel","relativeDelayMs","rttMs","muted") && message["callId"].asString == grant.call.callId)
                            val received = message["uplinkFrames"].asBigDecimal.longValueExact(); val rtt = message["rttMs"].asBigDecimal.longValueExact()
                            require(received >= 0 && rtt in 0..60000)
                            CallRuntime.update { it.copy(pcReceivedFrames = received, rttMs = rtt) }
                        }
                        "media.ended" -> { require(message.keySet() == setOf("version","type","reason")); finish("Call ended: ${safeReason(message["reason"].asString)}", false) }
                        "media.error" -> { require(message.keySet() == setOf("version","type","code")); CallRuntime.update { it.copy(problem = "Diagnostic tone unavailable; wait briefly and retry.") } }
                        else -> error("invalid_media")
                    }
                    lastMessage = SystemClock.elapsedRealtime()
                } catch (_: Exception) { finish("Invalid media response", false) }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (closed.get()) return
                try {
                    require(ready); val now = SystemClock.elapsedRealtime()
                    if (now - window >= 1000) { window = now; receivedInWindow = 0 }
                    require(++receivedInWindow <= 75)
                    val frame = AudioFrames.decode(bytes.toByteArray(), 1)
                    if (sequence.accept(frame, now)) { jitter.add(PcmJitterBuffer.Chunk(frame.pcm, now, frame.level)); CallRuntime.update { it.copy(downlinkFrames = it.downlinkFrames + 1, dropped = jitter.dropped + sequence.dropped) } }
                    else frame.pcm.fill(0)
                    lastMessage = now
                } catch (_: Exception) { finish("Invalid audio frame", false) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { finish("Media connection lost", response == null) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); finish(if (code == 1000) "Call ended" else "Media connection closed", code == 1001 || code == 1006) }
        })
        scope.launch { while (isActive && !closed.get()) { delay(1000); if (SystemClock.elapsedRealtime() - lastMessage > 8000) { finish("Media health timeout", true); break } } }
    }
    private fun safeReason(reason: String) = when (reason) {
        "user_ended" -> "ended by user"; "revoked" -> "pairing revoked"; "duration_limit" -> "duration limit"; "bridge_stopped", "bridge_restarted" -> "PC stopped"; else -> "session closed"
    }
    @Suppress("MissingPermission")
    private fun startAudio() {
        try {
            check(!closed.get() && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && TaskNotifications.permission(context))
            val minRecord = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val minTrack = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minRecord in 1..16000 && minTrack in 1..16000)
            if(!TelecomRuntime.claimed){
            previousMode = audio.mode; previousDevice = audio.communicationDevice
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener { change -> if (change != AudioManager.AUDIOFOCUS_GAIN) finish("Audio focus interrupted", false) }.build()
            check(audio.requestAudioFocus(focus!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            audio.registerAudioDeviceCallback(devices, android.os.Handler(android.os.Looper.getMainLooper()))
            }
            record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(max(minRecord, 640 * 8)).build()
            check(record!!.state == AudioRecord.STATE_INITIALIZED && record!!.sampleRate == 16000 && record!!.channelCount == 1 && record!!.audioFormat == AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(max(minTrack, 640 * 4)).build()
            check(track!!.state == AudioTrack.STATE_INITIALIZED && track!!.sampleRate == 16000)
            track!!.setVolume(.35f); track!!.play()
            if (!muted && !systemMuted) { record!!.startRecording(); check(record!!.recordingState == AudioRecord.RECORDSTATE_RECORDING) }
            if (closed.get()) { releaseAudio(); return }
            CallRuntime.update { it.copy(phase = LivePhase.ACTIVE, muted = muted, capturing = !muted, playing = true, problem = "", activeAt = if (it.activeAt == 0L) SystemClock.elapsedRealtime() else it.activeAt,
                captureSampleRate = record!!.sampleRate, playbackSampleRate = track!!.sampleRate) }
            routeState()
            CallDiagnostics.record(context, "capture_started")
            captureJob = scope.launch { capture() }; playJob = scope.launch { playback() }
        } catch (_: Exception) { finish("Microphone or playback initialization failed", false) }
    }
    private suspend fun capture() {
        val samples = ShortArray(320); val bytes = ByteArray(660); var sequence = 0L; var offset = 0
        var sent = 0L; var publishAt = 0L
        val recorder = record ?: return
        try {
            while (scope.isActive && !closed.get()) {
                if (muted || systemMuted || speechPaused) { samples.fill(0); offset = 0; delay(20); continue }
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) { delay(20); continue }
                val count = recorder.read(samples, offset, 320 - offset, AudioRecord.READ_BLOCKING)
                if (closed.get()) break
                if (muted || systemMuted || speechPaused) { offset = 0; continue }
                check(count > 0); offset += count
                if (offset < 320) continue
                offset = 0
                if (!canTransmit(CallRuntime.state.value.phase, muted, ready)) continue
                if (socket!!.queueSize() > 4096) { CallRuntime.update { it.copy(dropped = it.dropped + 1) }; continue }
                AudioFrames.encode(bytes, samples, sequence++, SystemClock.elapsedRealtime())
                if (muted || systemMuted || speechPaused || closed.get()) continue
                check(socket!!.send(bytes.toByteString()))
                sent++
                val now = SystemClock.elapsedRealtime()
                if (now >= publishAt) {
                    publishAt = now + 200
                    CallRuntime.update { it.copy(uplinkFrames = sent, inputLevel = AudioFrames.level(samples), capturing = recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) }
                }
            }
        } catch (_: Exception) { if (!closed.get()) finish("Microphone capture failed", false) }
        finally { samples.fill(0); bytes.fill(0) }
    }
    private suspend fun playback() {
        val silence = ByteArray(640); val player = track ?: return; var started = false
        var publishAt = 0L
        try {
            while (scope.isActive && !closed.get()) {
                if (!started && jitter.size() < 2) { delay(20); continue }
                started = true; val chunk = jitter.take(SystemClock.elapsedRealtime()); val pcm = chunk?.pcm ?: silence
                var offset = 0
                while (offset < pcm.size && !closed.get()) { val count = player.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING); check(count > 0); offset += count }
                val now = SystemClock.elapsedRealtime()
                if (now >= publishAt) {
                    publishAt = now + 200
                    CallRuntime.update { it.copy(outputLevel = chunk?.level ?: 0f, underruns = player.underrunCount.toLong(), dropped = jitter.dropped + sequence.dropped, renderedSamples = player.playbackHeadPosition.toLong() and 0xffffffffL) }
                }
                chunk?.pcm?.fill(0)
            }
        } catch (_: Exception) { if (!closed.get()) finish("Audio playback failed", false) }
    }
    fun mute(value: Boolean): Job {
        muted = value
        return scope.launch { applyCaptureGate() }
    }
    suspend fun pauseForSpeech(value: Boolean): Boolean {
        speechPaused = value
        return applyCaptureGate()
    }
    private suspend fun applyCaptureGate(): Boolean = recorderControl.withLock {
        if (!ready || closed.get()) return@withLock false
        try {
            val blocked = muted || systemMuted || speechPaused
            if (blocked) { if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) record?.stop() }
            else if (record?.recordingState != AudioRecord.RECORDSTATE_RECORDING) { record?.startRecording(); check(record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) }
            socket?.send("""{"version":1,"type":"media.mute","muted":$blocked}""")
            CallRuntime.update { it.copy(muted = muted || systemMuted, capturing = !blocked && record?.recordingState == AudioRecord.RECORDSTATE_RECORDING, inputLevel = 0f) }
            true
        } catch (_: Exception) { if (!closed.get()) finish("Microphone mute control failed", false); false }
    }
    fun tone() { if (ready && !closed.get()) socket?.send("""{"version":1,"type":"media.tone"}""") }
    fun speaker(value: Boolean) = scope.launch {
        if (!ready || closed.get()) return@launch
        if(TelecomRuntime.claimed){TelecomRuntime.speaker(value);return@launch}
        try {
            val type = if (value) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val target = audio.availableCommunicationDevices.firstOrNull { it.type == type }
            if (target == null || !audio.setCommunicationDevice(target)) CallRuntime.update { it.copy(problem = "Requested audio route is unavailable") }
            routeState()
        } catch (_: Exception) { CallRuntime.update { it.copy(routeSupported = false, problem = "Audio route cannot be controlled on this device") } }
    }
    private fun routeState() {
        if (closed.get()) return
        if(TelecomRuntime.claimed)return
        try {
            val devices = audio.availableCommunicationDevices; val current = audio.communicationDevice
            val name = when (current?.type) { AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"; AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone earpiece"; AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headset"; AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth headset"; else -> "System route" }
            CallRuntime.update { it.copy(speaker = current?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, route = name, routeSupported = devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } && devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }) }
        } catch (_: Exception) { CallRuntime.update { it.copy(routeSupported = false) } }
    }
    fun end() { socket?.send("""{"version":1,"type":"media.end"}"""); finish("Ended by you", false) }
    private fun finish(reason: String, recoverable: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        CallDiagnostics.record(context, "media_lost")
        ready = false; muted = true
        scope.launch {
            try { record?.stop() } catch (_: Exception) { }
            try { track?.stop(); track?.flush() } catch (_: Exception) { }
            withTimeoutOrNull(500) { captureJob?.cancelAndJoin() }; withTimeoutOrNull(500) { playJob?.cancelAndJoin() }; releaseAudio()
            socket?.close(1000, null); socket?.cancel(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
            stopped(reason, recoverable); scope.cancel()
        }
    }
    private fun releaseAudio() {
        try { record?.release() } catch (_: Exception) { }; record = null
        try { track?.stop(); track?.release() } catch (_: Exception) { }; track = null; jitter.clear()
        try { audio.unregisterAudioDeviceCallback(devices) } catch (_: Exception) { }
        try { focus?.let(audio::abandonAudioFocusRequest); if (previousMode != null && audio.mode == AudioManager.MODE_IN_COMMUNICATION) { audio.clearCommunicationDevice(); previousDevice?.let { if (audio.availableCommunicationDevices.any { device -> device.id == it.id }) audio.setCommunicationDevice(it) }; previousMode?.let { audio.mode = it } } } catch (_: Exception) { }
        focus = null
    }
    fun destroy() { finish("Audio service stopped", false) }
}
