package com.codexvoice.app

import com.codexvoice.app.core.remote.*
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import com.codexvoice.app.core.notifications.PushParser
import com.codexvoice.app.core.notifications.PushPreferences
import kotlinx.coroutines.flow.collect
import java.util.concurrent.ConcurrentLinkedQueue
import com.codexvoice.app.core.calls.*
import okio.ByteString.Companion.toByteString
import java.util.concurrent.atomic.AtomicInteger

class ProtocolIntegrationTest {
    @Test fun actualKotlinClientPairsWithNodeBridgeCompletesReconnectsAndIsRevoked() = runBlocking {
        val path = System.getenv("CODEX_VOICE_PROTOCOL_FIXTURE")
        assumeTrue("Run scripts/protocol-integration.ps1 to start the real offline WebSocket fixture", path != null)
        val fixture = JsonParser.parseString(File(path!!).readText()).asJsonObject
        val port = fixture.get("port").asInt
        val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build()
        // Test-only routing replaces WSS with loopback and simulates Serve headers.
        // Production OkHttpSocketFactory has no such route or header override.
        val sockets = SocketFactory { address, observer ->
            assertEquals(fixture.get("endpoint").asString, address)
            val socket = http.newWebSocket(Request.Builder().url("ws://127.0.0.1:$port/remote")
                .header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "test.example.ts.net")
                .header("Tailscale-User-Login", "fixture@example.invalid").build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = observer.opened()
                override fun onMessage(webSocket: WebSocket, text: String) = observer.message(text)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = observer.failed("unreachable")
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); observer.failed("closed") }
            })
            object : RemoteSocket { override fun send(text: String) = socket.send(text); override fun close() { socket.cancel() } }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val store = MemoryCredentials()
        val first = RemoteClient(scope, sockets, store); var second: RemoteClient? = null
        val notificationResponses = ConcurrentLinkedQueue<ServerMessage>()
        val callResponses = ConcurrentLinkedQueue<ServerMessage>()
        val speechResponses = ConcurrentLinkedQueue<ServerMessage>()
        var mediaSocket: WebSocket? = null
        scope.launch(start = CoroutineStart.UNDISPATCHED) { first.notifications.collect { notificationResponses.add(it) } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { first.calls.collect { callResponses.add(it) } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { first.speech.collect { speechResponses.add(it) } }
        suspend fun until(predicate: () -> Boolean) { withTimeout(15000) { while (!predicate()) delay(20) } }
        try {
            first.foreground(true); first.pair(fixture.get("endpoint").asString, fixture.get("code").asString)
            until { first.state.value.status == RemoteStatus.CONNECTED }
            first.call("call.preferences.update", mapOf("enabled" to true))
            until { callResponses.any { it.type == "call.preferences.response" && it.payload["enabled"].asBoolean } }
            first.notification("notification.register", mapOf("fid" to ("c" + "A".repeat(21)), "projectId" to "codex-voice-example", "preferences" to PushPreferences(enabled = true).server()))
            until { notificationResponses.any { it.type == "notification.status.response" && it.payload["registered"].asBoolean } }
            until { first.state.value.snapshot?.turns?.any { it.status == "completed" } == true }
            assertEquals(RemoteStatus.CONNECTED, first.state.value.status); assertEquals("mock", first.state.value.snapshot!!.mode)
            assertEquals(1, first.state.value.snapshot!!.history.count { it.type == "turn.completed" })
            val pushFile = File(fixture["pushFile"].asString); until { pushFile.exists() }
            val push = JsonParser.parseString(pushFile.readText()).asJsonObject.entrySet().associate { it.key to it.value.asString }
            val event = PushParser.parse(push, store.value, System.currentTimeMillis())
            assertNotNull("Actual Node payload signature and fields must validate in Kotlin", event)
            assertFalse(push.containsKey("threadId")); assertFalse(push.containsKey("result"))
            first.notification("notification.event.request", mapOf("eventId" to event!!.eventId))
            until { notificationResponses.any { it.type == "notification.event.response" } }
            val details = notificationResponses.first { it.type == "notification.event.response" }.payload
            assertEquals(first.state.value.snapshot!!.turns.single().id, details["turnId"].asString)
            first.notification("notification.receipt", mapOf("eventId" to event.eventId, "stage" to "received"))
            until { notificationResponses.any { it.type == "notification.receipt.response" } }
            first.call("call.offer.request", mapOf("eventId" to event.eventId))
            until { callResponses.any { it.type == "call.status.response" } }
            val call = CallWire.offer(callResponses.first { it.type == "call.status.response" }.payload.getAsJsonObject("call"))
            assertEquals(event.eventId, call.eventId); assertEquals("offered", call.state)
            first.call("call.accept", mapOf("callId" to call.callId))
            until { callResponses.any { it.type == "call.media.response" } }
            val grant = CallWire.grant(callResponses.first { it.type == "call.media.response" }.payload)
            val mediaMessages = ConcurrentLinkedQueue<com.google.gson.JsonObject>(); val toneFrames = AtomicInteger(0)
            // Real separate Node media endpoint and Kotlin codec; microphone input here is synthetic.
            mediaSocket = http.newWebSocket(Request.Builder().url("ws://127.0.0.1:$port/media")
                .header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "test.example.ts.net")
                .header("Tailscale-User-Login", "fixture@example.invalid").header("Authorization", "Bearer ${grant.token}").build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(com.google.gson.Gson().toJson(mapOf("version" to 1, "type" to "media.hello", "callId" to call.callId, "format" to CallWire.format))) }
                override fun onMessage(webSocket: WebSocket, text: String) { mediaMessages.add(JsonParser.parseString(text).asJsonObject) }
                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) { val frame = AudioFrames.decode(bytes.toByteArray(), 1); assertTrue(frame.level > 0); toneFrames.incrementAndGet() }
            })
            until { mediaMessages.any { it["type"].asString == "media.ready" } }
            first.speech("speech.listening",mapOf("callId" to call.callId,"enabled" to true))
            until { speechResponses.any { it.type=="speech.status.response" } }
            first.speech("speech.summary.request",mapOf("callId" to call.callId))
            until { speechResponses.any { it.type=="speech.summary.response" } }
            assertTrue(speechResponses.first { it.type=="speech.summary.response" }.payload["text"].asString.startsWith("Simulated task."))
            repeat(20) { index -> val bytes = ByteArray(660); AudioFrames.encode(bytes, ShortArray(320) { 4000 }, index.toLong(), index * 20L); assertTrue(mediaSocket!!.send(bytes.toByteString())); delay(20) }
            until { mediaMessages.any { it["type"].asString == "media.metrics" && it["uplinkFrames"].asInt >= 20 } }
            first.speech("speech.finish",mapOf("callId" to call.callId))
            until {
                first.speech("speech.status.request",mapOf("callId" to call.callId,"afterSequence" to 0))
                Thread.sleep(300)
                speechResponses.any { it.type=="speech.status.response" && it.payload["utterances"].asJsonArray.size()>0 }
            }
            val transcript=speechResponses.first { it.type=="speech.status.response" && it.payload["utterances"].asJsonArray.size()>0 }.payload["utterances"].asJsonArray.first().asJsonObject
            assertEquals("Synthetic protocol fixture transcript",transcript["text"].asString)
            assertTrue(transcript["final"].asBoolean)
            mediaSocket!!.send("""{"version":1,"type":"media.tone"}""")
            until { toneFrames.get() >= 10 }
            mediaSocket!!.send("""{"version":1,"type":"media.mute","muted":true}""")
            until { mediaMessages.any { it["type"].asString == "media.metrics" && it["muted"].asBoolean } }
            val receivedBeforeMute = mediaMessages.last { it["type"].asString == "media.metrics" }["uplinkFrames"].asInt
            repeat(5) { index -> val bytes = ByteArray(660); AudioFrames.encode(bytes, ShortArray(320) { 4000 }, 20L + index, 400L + index * 20); mediaSocket!!.send(bytes.toByteString()); delay(20) }
            delay(1100)
            assertEquals(receivedBeforeMute, mediaMessages.last { it["type"].asString == "media.metrics" }["uplinkFrames"].asInt)
            mediaSocket!!.send("""{"version":1,"type":"media.end"}""")
            until { mediaMessages.any { it["type"].asString == "media.ended" } }
            first.speech("speech.status.request",mapOf("callId" to call.callId,"afterSequence" to 0))
            until { speechResponses.any { it.type=="speech.error" && it.payload["code"].asString=="call_not_active" } }
            val epoch = first.state.value.snapshot!!.epoch; first.close()
            val resumed = RemoteClient(scope, sockets, store); second = resumed; resumed.foreground(true)
            until { resumed.state.value.status == RemoteStatus.CONNECTED }
            assertEquals(epoch, resumed.state.value.snapshot!!.epoch)
            assertEquals(1, resumed.state.value.snapshot!!.history.count { it.type == "turn.completed" })
            File(fixture.get("control").asString).writeText("revoke")
            until { resumed.state.value.status == RemoteStatus.AUTH_FAILED }
            assertFalse(resumed.state.value.paired); assertNull(store.value)
        } finally { mediaSocket?.cancel(); first.close(); second?.close(); scope.cancel(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }
}
