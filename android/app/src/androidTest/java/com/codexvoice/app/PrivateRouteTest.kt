package com.codexvoice.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.codexvoice.app.core.remote.*
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Real phone/Keystore and normal WSS through Serve. All task outcomes are mock fixtures. */
class PrivateRouteTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
    }
    @Test fun physicalPrivateRoutePairsSynchronizesRestartsAndRevokes() = runBlocking {
        assumeTrue("Run scripts/device-verification.ps1", InstrumentationRegistry.getArguments().getString("privateRoute") == "true")
        val fixture = JsonParser.parseString(shell("cat /data/local/tmp/codex-voice-fixture.json")).asJsonObject
        val endpoint = fixture.get("endpoint").asString
        val testDirectory = File(instrumentation.targetContext.noBackupFilesDir, "device-verification").apply { mkdirs() }
        val context = object : ContextWrapper(instrumentation.targetContext) { override fun getNoBackupFilesDir() = testDirectory }
        // Same production storage implementation, isolated file/key so normal pairing is preserved.
        val store = KeystoreCredentialStore(context, "codex-voice-device-verification-v1")
        store.clear() // Reset only this fixture's owned file/key after any interrupted prior run.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var client = RemoteClient(scope, OkHttpSocketFactory(), store)
        suspend fun until(label: String, predicate: () -> Boolean) {
            try { withTimeout(45000) { while (!predicate()) { if (client.state.value.status in setOf(RemoteStatus.TLS_ERROR, RemoteStatus.PROTOCOL_INCOMPATIBLE, RemoteStatus.PAIR_FAILED, RemoteStatus.STORAGE_ERROR)) error("$label: ${client.state.value.status}"); delay(100) } } }
            catch (_: TimeoutCancellationException) { throw AssertionError("$label timed out: ${client.state.value.status}") }
            println("Verified: $label")
        }
        fun control(command: String) { File(testDirectory, "control.txt").writeText(command) }
        try {
            client.foreground(true); client.pair(endpoint, fixture.get("code").asString)
            until("Authenticated WSS") { client.state.value.status == RemoteStatus.CONNECTED }
            assertEquals("mock", client.state.value.snapshot!!.mode)
            val credential = checkNotNull(store.load())
            assertTrue("Keystore decrypt reload", credential.token.length == 43)
            assertFalse("Credential absent from ciphertext", File(testDirectory, "paired-pc.enc").readBytes().toString(Charsets.UTF_8).contains(credential.token))
            val baseline = client.state.value.snapshot!!.history.map { it.id }.toSet()
            control("complete"); until("Completion") { client.state.value.snapshot!!.history.any { it.type == "turn.completed" && it.id !in baseline } }
            assertEquals(1, client.state.value.snapshot!!.history.count { it.type == "turn.completed" && it.id !in baseline })
            control("failure"); until("Failure") { client.state.value.snapshot!!.history.any { it.type == "turn.failed" && it.id !in baseline } }
            control("waiting"); until("Running") { client.state.value.snapshot!!.turns.any { it.status == "running" } }
            control("interrupt"); until("Interruption") { client.state.value.snapshot!!.history.any { it.type == "turn.interrupted" && it.id !in baseline } }
            control("approval"); until("Approval") { client.state.value.snapshot!!.turns.any { it.status == "awaitingApproval" } }
            control("decline"); until("Local rejection") { client.state.value.snapshot!!.turns.none { it.status in setOf("running", "awaitingApproval") } }
            val oldEpoch = client.state.value.snapshot!!.epoch
            control("restart"); until("Bridge restart") { client.state.value.status == RemoteStatus.CONNECTED && client.state.value.snapshot!!.epoch != oldEpoch }
            client.foreground(false); delay(500); client.foreground(true)
            until("Foreground reconnect") { client.state.value.status == RemoteStatus.CONNECTED }
            client.disconnect(); until("Explicit disconnect") { client.state.value.status == RemoteStatus.DISCONNECTED }
            assertFalse(store.load()!!.enabled); client.close()
            client = RemoteClient(scope, OkHttpSocketFactory(), store); client.foreground(true); delay(1500)
            assertEquals(RemoteStatus.DISCONNECTED, client.state.value.status)
            client.connect(); until("Credential reload") { client.state.value.status == RemoteStatus.CONNECTED }
            if (InstrumentationRegistry.getArguments().getString("mobileData") == "true") {
                shell("svc data enable"); shell("svc wifi disable")
                delay(10000); client.refresh()
                until("Mobile data synchronization") { client.state.value.status == RemoteStatus.CONNECTED && client.state.value.lastConnected > System.currentTimeMillis() - 5000 }
                assertEquals("0", shell("settings get global wifi_on").trim())
                control("complete"); val oldSequence = client.state.value.snapshot!!.sequence
                until("Mobile live event") { client.state.value.snapshot!!.sequence > oldSequence && client.state.value.snapshot!!.turns.last().status == "completed" }
                shell("svc wifi enable"); delay(8000); client.refresh()
                until("Wi-Fi return") { client.state.value.status == RemoteStatus.CONNECTED }
            }
            control("revoke"); until("Revocation") { client.state.value.status == RemoteStatus.AUTH_FAILED }
            assertFalse(client.state.value.paired); assertNull(store.load())
            println("Private-route checks passed: normal TLS, Keystore reload, mock outcomes, restart, disconnect and revocation.")
        } finally {
            client.close(); scope.cancel()
            store.clear()
            if (InstrumentationRegistry.getArguments().getString("mobileData") == "true") {
                shell("svc wifi " + if (fixture.get("wifiInitiallyEnabled").asBoolean) "enable" else "disable")
                shell("svc data " + if (fixture.get("dataInitiallyEnabled").asBoolean) "enable" else "disable")
            }
        }
    }
}
