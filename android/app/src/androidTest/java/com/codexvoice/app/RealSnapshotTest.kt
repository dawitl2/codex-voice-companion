package com.codexvoice.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.codexvoice.app.core.data.remoteCurrent
import com.codexvoice.app.core.remote.*
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class RealSnapshotTest {
    @Test fun phoneReceivesExistingRealThreadAndUnauthenticatedSessionGetsNoData() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Run scripts/device-verification.ps1 -RealSnapshot", InstrumentationRegistry.getArguments().getString("privateRoute") == "true")
        val fixture = instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/codex-voice-fixture.json").use {
            JsonParser.parseString(android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()).asJsonObject
        }
        val endpoint = fixture.get("endpoint").asString
        val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build()
        val rejection = CompletableDeferred<Boolean>()
        val probe = http.newWebSocket(Request.Builder().url(endpoint).build(), object : WebSocketListener() {
            override fun onOpen(socket: WebSocket, response: Response) { socket.send(RemoteProtocol.message("client.hello", mapOf("name" to "Unauthenticated device test"))) }
            override fun onMessage(socket: WebSocket, text: String) {
                val message = RemoteProtocol.parse(text)
                if (message.type == "server.hello") socket.send(RemoteProtocol.message("state.snapshot.request"))
                else rejection.complete(message.type == "protocol.error" && message.snapshot == null && message.payload.get("code").asString == "authentication_required")
            }
            override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) { rejection.complete(false) }
        })
        try { assertTrue("Unauthenticated real WSS session rejected", withTimeout(20000) { rejection.await() }) }
        finally { probe.cancel(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
        val directory = File(instrumentation.targetContext.noBackupFilesDir, "device-verification-real").apply { mkdirs() }
        val context = object : ContextWrapper(instrumentation.targetContext) { override fun getNoBackupFilesDir() = directory }
        val store = KeystoreCredentialStore(context, "codex-voice-device-real-v1"); store.clear()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = RemoteClient(scope, OkHttpSocketFactory(), store)
        try {
            client.foreground(true); client.pair(endpoint, fixture.get("code").asString)
            withTimeout(45000) { while (client.state.value.status != RemoteStatus.CONNECTED) {
                check(client.state.value.status !in setOf(RemoteStatus.PAIR_FAILED, RemoteStatus.TLS_ERROR, RemoteStatus.STORAGE_ERROR, RemoteStatus.PROTOCOL_INCOMPATIBLE)) { "Real snapshot authentication failed" }; delay(100)
            } }
            val snapshot = checkNotNull(client.state.value.snapshot)
            assertEquals("real", snapshot.mode); assertTrue(snapshot.available)
            assertEquals(fixture.get("expectedThreadId").asString, snapshot.selectedThreadId)
            assertTrue(snapshot.turns.any { it.id == fixture.get("expectedTurnId").asString && it.status == "completed" })
            assertFalse(remoteCurrent(snapshot)!!.simulated)
            assertTrue("No raw original response text", snapshot.history.none { it.summary.contains("CODEX VOICE BRIDGE CONNECTED") })
            println("Existing real managed thread synchronized to physical phone; zero inference submitted.")
        } finally { client.close(); scope.cancel(); store.clear() }
    }
}
