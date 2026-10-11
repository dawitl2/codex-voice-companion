package com.codexvoice.app

import com.codexvoice.app.core.remote.*
import com.google.gson.JsonParser
import kotlinx.coroutines.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

private const val endpoint = "wss://test.example.ts.net/remote"
class MemoryCredentials(var value: Credential? = null) : CredentialStore {
    var failSave = false
    override fun load() = value
    override fun save(credential: Credential) { if (failSave) error("disk"); value = credential }
    override fun clear() { value = null }
}
class FakeSockets : SocketFactory {
    data class Session(val observer: SocketObserver, val sent: MutableList<String> = mutableListOf(), var closed: Boolean = false)
    val sessions = mutableListOf<Session>()
    override fun open(address: String, observer: SocketObserver): RemoteSocket {
        assertEquals(endpoint, address); val session = Session(observer); sessions.add(session)
        return object : RemoteSocket {
            override fun send(text: String): Boolean { session.sent.add(text); return !session.closed }
            override fun close() { session.closed = true }
        }
    }
    fun openLatest() = sessions.last().observer.opened()
    fun response(type: String, payload: Map<String, Any> = emptyMap(), correlation: String? = lastId()) {
        val json = JsonParser.parseString(RemoteProtocol.message(type, payload)).asJsonObject
        correlation?.let { json.addProperty("correlationId", it) }; sessions.last().observer.message(json.toString())
    }
    fun lastId(): String = JsonParser.parseString(sessions.last().sent.last()).asJsonObject.get("id").asString
}
fun snapshotFixture(sequence: Long = 1, epoch: String = "fixture-epoch", available: Boolean = true): Map<String, Any> = mapOf(
    "epoch" to epoch, "sequence" to sequence, "bridgeName" to "Fixture PC", "available" to available, "mode" to "mock",
    "selectedThreadId" to "thread-1", "threads" to listOf(mapOf("id" to "thread-1", "label" to "Fixture", "project" to "Project", "status" to "ready")),
    "turns" to listOf(mapOf("id" to "turn-1", "threadId" to "thread-1", "status" to "completed", "startedAt" to "2026-10-11T10:00:00Z", "finishedAt" to "2026-10-11T10:01:00Z")),
    "history" to listOf(mapOf("id" to "event-1", "type" to "turn.completed", "timestamp" to "2026-10-11T10:01:00Z", "summary" to "Turn completed", "threadId" to "thread-1", "turnId" to "turn-1")),
    "historyComplete" to false, "syncedAt" to "2026-10-11T10:01:01Z")
private fun credential() = Credential(endpoint, UUID.randomUUID().toString(), "A".repeat(43), "Fixture PC")

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteClientTest {
    @Test fun failedHelloSendRemainsRecoverableAndRepeatedForegroundDoesNotOpenAnotherSocket() = runTest {
        val sockets = FakeSockets(); val client = RemoteClient(backgroundScope, sockets, MemoryCredentials(credential()), jitter = { .5 })
        client.foreground(true); runCurrent(); client.foreground(true); runCurrent()
        assertEquals(1, sockets.sessions.size)
        sockets.sessions.last().closed = true; sockets.openLatest(); runCurrent()
        assertEquals(RemoteStatus.RECONNECTING, client.state.value.status)
        advanceTimeBy(1000); runCurrent(); assertEquals(2, sockets.sessions.size)
    }
    @Test fun validatesPrivateEndpointAndRejectsTokenUrlsOrCleartext() {
        assertEquals(endpoint, RemoteProtocol.endpoint(endpoint))
        for (url in listOf("ws://test.example.ts.net/remote", "wss://public.example/remote", "$endpoint?token=x", "wss://user@test.example.ts.net/remote", "wss://test.example.ts.net:8080/remote")) {
            try { RemoteProtocol.endpoint(url); fail("accepted unsafe endpoint") } catch (_: RemoteProtocolException) { }
        }
    }
    @Test fun parsesBoundedSnapshotAndTimestamps() {
        val snapshot = RemoteProtocol.parse(RemoteProtocol.message("state.snapshot", snapshotFixture())).snapshot!!
        assertEquals(1L, snapshot.sequence); assertEquals(1, snapshot.turns.size); assertEquals(60000L, snapshot.turns[0].finishedAt!! - snapshot.turns[0].startedAt)
        assertFalse(snapshot.historyComplete)
    }
    @Test fun rejectsMalformedUnknownAndOversizedMessages() {
        for (raw in listOf("null", "{", "{'version':1}", RemoteProtocol.message("shell.execute"),
            RemoteProtocol.message("state.snapshot", snapshotFixture().plus("sequence" to -1)), "😀".repeat(70000),
            RemoteProtocol.message("state.snapshot", snapshotFixture().plus("mode" to "fake")))) {
            try { RemoteProtocol.parse(raw); fail("accepted bad message") } catch (_: RemoteProtocolException) { }
        }
    }
    @Test fun rejectsUnknownVersionsWithCompatibilityError() {
        val raw = RemoteProtocol.message("heartbeat").replace("\"version\":1", "\"version\":2")
        try { RemoteProtocol.parse(raw); fail() } catch (e: RemoteProtocolException) { assertEquals("incompatible_version", e.reason) }
    }
    @Test fun backoffIsBoundedAndJittered() {
        assertEquals(750L, reconnectDelay(0, 0.0)); assertEquals(1250L, reconnectDelay(0, 1.0))
        assertEquals(32000L, reconnectDelay(5, .5)); assertNull(reconnectDelay(6, .5))
    }
    @Test fun connectsOnlyAfterAuthAndSnapshotAndIgnoresOlderState() = runTest {
        val c = credential(); val store = MemoryCredentials(c); val sockets = FakeSockets(); val client = RemoteClient(backgroundScope, sockets, store)
        client.foreground(true); runCurrent(); sockets.openLatest(); runCurrent()
        assertEquals(RemoteStatus.CONNECTING, client.state.value.status)
        sockets.response("server.hello", mapOf("bridgeName" to "PC")); runCurrent()
        assertEquals(RemoteStatus.AUTHENTICATING, client.state.value.status)
        sockets.response("auth.success", mapOf("deviceId" to c.deviceId, "bridgeName" to "PC")); runCurrent()
        assertNotEquals(RemoteStatus.CONNECTED, client.state.value.status)
        sockets.response("state.snapshot", snapshotFixture(3)); runCurrent()
        assertEquals(RemoteStatus.CONNECTED, client.state.value.status)
        sockets.response("turn.completed", snapshotFixture(3)); sockets.response("state.snapshot", snapshotFixture(2)); runCurrent()
        assertEquals(3L, client.state.value.snapshot!!.sequence); assertEquals(1, client.state.value.snapshot!!.history.size)
    }
    @Test fun pairsAndStoresCredentialWithoutExposingItInState() = runTest {
        val store = MemoryCredentials(); val sockets = FakeSockets(); val client = RemoteClient(backgroundScope, sockets, store)
        client.foreground(true); client.pair(endpoint, "0123-4567-89AB-CDEF"); runCurrent(); sockets.openLatest(); runCurrent()
        sockets.response("server.hello", mapOf("bridgeName" to "PC")); runCurrent(); assertEquals(RemoteStatus.PAIRING, client.state.value.status)
        val c = credential(); sockets.response("pair.success", mapOf("deviceId" to c.deviceId, "token" to c.token, "bridgeName" to "PC")); runCurrent()
        sockets.response("state.snapshot", snapshotFixture()); runCurrent()
        assertTrue(client.state.value.paired); assertEquals(c.token, store.value!!.token)
        assertFalse(client.state.value.toString().contains(c.token)); assertFalse(store.value.toString().contains(c.token))
    }
    @Test fun authFailureErasesCredentialAndStopsReconnecting() = runTest {
        val store = MemoryCredentials(credential()); val sockets = FakeSockets(); val client = RemoteClient(backgroundScope, sockets, store)
        client.foreground(true); runCurrent(); sockets.openLatest(); runCurrent(); sockets.response("server.hello", mapOf("bridgeName" to "PC")); runCurrent()
        sockets.response("auth.failed", mapOf("code" to "invalid_credentials")); runCurrent(); advanceTimeBy(120000); runCurrent()
        assertEquals(RemoteStatus.AUTH_FAILED, client.state.value.status); assertNull(store.value); assertEquals(1, sockets.sessions.size)
    }
    @Test fun explicitDisconnectPersistsAndStaleCallbacksCannotReconnect() = runTest {
        val store = MemoryCredentials(credential()); val sockets = FakeSockets(); val client = RemoteClient(backgroundScope, sockets, store)
        client.foreground(true); runCurrent(); val old = sockets.sessions.last().observer
        client.disconnect(); runCurrent(); old.failed("unreachable"); client.foreground(false); client.foreground(true); runCurrent(); advanceTimeBy(120000); runCurrent()
        assertFalse(store.value!!.enabled); assertEquals(1, sockets.sessions.size); assertEquals(RemoteStatus.DISCONNECTED, client.state.value.status)
    }
    @Test fun networkLossRetriesButTlsFailureStops() = runTest {
        val sockets = FakeSockets(); val client = RemoteClient(backgroundScope, sockets, MemoryCredentials(credential()), jitter = { .5 })
        client.foreground(true); runCurrent(); sockets.sessions.last().observer.failed("unreachable"); runCurrent()
        assertEquals(RemoteStatus.RECONNECTING, client.state.value.status); advanceTimeBy(1000); runCurrent(); assertEquals(2, sockets.sessions.size)
        sockets.sessions.last().observer.failed("tls_failure"); runCurrent(); advanceTimeBy(120000); runCurrent()
        assertEquals(RemoteStatus.TLS_ERROR, client.state.value.status); assertEquals(2, sockets.sessions.size)
    }
    @Test fun pairingStorageFailureDoesNotClaimConnected() = runTest {
        val store = MemoryCredentials().apply { failSave = true }; val sockets = FakeSockets(); val client = RemoteClient(backgroundScope, sockets, store)
        client.foreground(true); client.pair(endpoint, "0123456789ABCDEF"); runCurrent(); sockets.openLatest(); runCurrent()
        sockets.response("server.hello", mapOf("bridgeName" to "PC")); runCurrent(); val c = credential()
        sockets.response("pair.success", mapOf("deviceId" to c.deviceId, "token" to c.token, "bridgeName" to "PC")); runCurrent()
        assertEquals(RemoteStatus.STORAGE_ERROR, client.state.value.status); assertFalse(client.state.value.paired)
    }
    @Test fun foregroundReturnReconnectsAndForgetClearsState() = runTest {
        val store = MemoryCredentials(credential()); val sockets = FakeSockets(); val client = RemoteClient(backgroundScope, sockets, store)
        client.foreground(true); runCurrent(); client.foreground(false); runCurrent(); client.foreground(true); runCurrent()
        assertEquals(2, sockets.sessions.size); client.forget(); runCurrent()
        assertNull(store.value); assertEquals(ClientState(), client.state.value)
    }
    @Test fun connectionDeadlinePreventsIndefiniteConnecting() = runTest {
        val sockets = FakeSockets(); val client = RemoteClient(backgroundScope, sockets, MemoryCredentials(credential()), jitter = { .5 })
        client.foreground(true); runCurrent(); advanceTimeBy(15000); runCurrent()
        assertEquals(RemoteStatus.RECONNECTING, client.state.value.status)
    }
    @Test fun snapshotCannotArriveBeforeAuthentication() = runTest {
        val sockets = FakeSockets(); val client = RemoteClient(backgroundScope, sockets, MemoryCredentials(credential()))
        client.foreground(true); runCurrent(); sockets.response("state.snapshot", snapshotFixture(), null); runCurrent()
        assertEquals(RemoteStatus.PROTOCOL_INCOMPATIBLE, client.state.value.status); assertNull(client.state.value.snapshot)
    }
    @Test fun unavailableBridgeAndRestartedEpochAreRepresentedAfterReauthentication() = runTest {
        val c = credential(); val sockets = FakeSockets(); val client = RemoteClient(backgroundScope, sockets, MemoryCredentials(c), jitter = { .5 })
        suspend fun authenticate() {
            sockets.openLatest(); runCurrent(); sockets.response("server.hello", mapOf("bridgeName" to "PC")); runCurrent()
            sockets.response("auth.success", mapOf("deviceId" to c.deviceId, "bridgeName" to "PC")); runCurrent()
        }
        client.foreground(true); runCurrent(); authenticate()
        sockets.response("state.snapshot", snapshotFixture(10, "old-epoch", false)); runCurrent()
        assertEquals(RemoteStatus.BRIDGE_UNAVAILABLE, client.state.value.status)
        sockets.sessions.last().observer.failed("unreachable"); runCurrent(); advanceTimeBy(1000); runCurrent(); authenticate()
        sockets.response("state.snapshot", snapshotFixture(0, "new-epoch")); runCurrent()
        assertEquals("new-epoch", client.state.value.snapshot!!.epoch); assertEquals(RemoteStatus.CONNECTED, client.state.value.status)
        sockets.response("state.snapshot", snapshotFixture(0, "unexpected-epoch")); runCurrent()
        assertEquals(RemoteStatus.PROTOCOL_INCOMPATIBLE, client.state.value.status)
    }
    @Test fun repeatedNetworkFailuresExhaustBoundedRetries() = runTest {
        val sockets = FakeSockets(); val client = RemoteClient(backgroundScope, sockets, MemoryCredentials(credential()), jitter = { .5 })
        client.foreground(true); runCurrent()
        for (attempt in 0..5) { sockets.sessions.last().observer.failed("unreachable"); runCurrent(); advanceTimeBy(1000L shl attempt); runCurrent() }
        sockets.sessions.last().observer.failed("unreachable"); runCurrent(); advanceTimeBy(120000); runCurrent()
        assertEquals(7, sockets.sessions.size); assertEquals(RemoteStatus.DISCONNECTED, client.state.value.status)
    }
    @Test fun unreadableCredentialsFailClosed() = runTest {
        val sockets = FakeSockets(); val client = RemoteClient(backgroundScope, sockets, object : CredentialStore {
            override fun load(): Credential? = error("invalid cipher")
            override fun save(credential: Credential) = Unit
            override fun clear() = Unit
        })
        client.foreground(true); runCurrent(); assertEquals(RemoteStatus.STORAGE_ERROR, client.state.value.status); assertEquals(0, sockets.sessions.size)
    }
}
