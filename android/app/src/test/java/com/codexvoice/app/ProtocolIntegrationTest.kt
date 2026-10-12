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
        scope.launch(start = CoroutineStart.UNDISPATCHED) { first.notifications.collect { notificationResponses.add(it) } }
        suspend fun until(predicate: () -> Boolean) { withTimeout(15000) { while (!predicate()) delay(20) } }
        try {
            first.foreground(true); first.pair(fixture.get("endpoint").asString, fixture.get("code").asString)
            until { first.state.value.status == RemoteStatus.CONNECTED }
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
            val epoch = first.state.value.snapshot!!.epoch; first.close()
            val resumed = RemoteClient(scope, sockets, store); second = resumed; resumed.foreground(true)
            until { resumed.state.value.status == RemoteStatus.CONNECTED }
            assertEquals(epoch, resumed.state.value.snapshot!!.epoch)
            assertEquals(1, resumed.state.value.snapshot!!.history.count { it.type == "turn.completed" })
            File(fixture.get("control").asString).writeText("revoke")
            until { resumed.state.value.status == RemoteStatus.AUTH_FAILED }
            assertFalse(resumed.state.value.paired); assertNull(store.value)
        } finally { first.close(); second?.close(); scope.cancel(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }
}
