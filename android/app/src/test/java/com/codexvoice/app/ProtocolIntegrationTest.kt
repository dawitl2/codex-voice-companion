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
        suspend fun until(predicate: () -> Boolean) { withTimeout(15000) { while (!predicate()) delay(20) } }
        try {
            first.foreground(true); first.pair(fixture.get("endpoint").asString, fixture.get("code").asString)
            until { first.state.value.snapshot?.turns?.any { it.status == "completed" } == true }
            assertEquals(RemoteStatus.CONNECTED, first.state.value.status); assertEquals("mock", first.state.value.snapshot!!.mode)
            assertEquals(1, first.state.value.snapshot!!.history.count { it.type == "turn.completed" })
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
