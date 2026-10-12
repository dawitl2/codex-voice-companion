package com.codexvoice.app.core.remote

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.net.URI
import java.time.Instant
import java.util.UUID
import com.codexvoice.app.core.calls.CallWire
import com.codexvoice.app.core.speech.SpeechWire
import com.codexvoice.app.core.conversation.ConversationWire

data class RemoteThread(val id: String, val label: String, val project: String, val status: String)
data class RemoteTurn(val id: String, val threadId: String, val status: String, val startedAt: Long, val finishedAt: Long?)
data class RemoteEvent(val id: String, val type: String, val timestamp: Long, val summary: String, val threadId: String?, val turnId: String?)
data class RemoteSnapshot(val epoch: String, val sequence: Long, val bridgeName: String, val available: Boolean,
    val mode: String, val selectedThreadId: String?, val threads: List<RemoteThread>, val turns: List<RemoteTurn>,
    val history: List<RemoteEvent>, val historyComplete: Boolean, val syncedAt: Long)
data class ServerMessage(val type: String, val id: String, val correlationId: String?, val payload: JsonObject, val snapshot: RemoteSnapshot?)
class RemoteProtocolException(val reason: String) : Exception(reason)

object RemoteProtocol {
    private val gson = GsonBuilder().setStrictness(Strictness.STRICT).create()
    val snapshotTypes = setOf("state.snapshot", "thread.list.response", "task.history.response", "connection.status",
        "turn.progress", "turn.completed", "turn.failed", "turn.interrupted", "approval.pending")
    private val eventTypes = setOf("bridge.connected", "bridge.disconnected", "thread.created", "thread.resumed", "turn.started",
        "turn.progress", "turn.completed", "turn.failed", "turn.interrupted", "approval.requested", "approval.resolved", "codex.error")
    fun endpoint(value: String): String {
        val uri = try { URI(value.trim()) } catch (_: Exception) { throw RemoteProtocolException("invalid_address") }
        if (uri.scheme != "wss" || uri.host == null || !uri.host.matches(Regex("[a-z0-9-]+\\.[a-z0-9.-]+\\.ts\\.net")) ||
            uri.port !in listOf(-1, 443) || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.rawPath != "/remote") throw RemoteProtocolException("invalid_address")
        return uri.toASCIIString()
    }
    fun message(type: String, payload: Map<String, Any> = emptyMap(), id: String = UUID.randomUUID().toString()): String =
        gson.toJson(mapOf("version" to 2, "type" to type, "id" to id, "timestamp" to Instant.now().toString(), "payload" to payload))
    fun parse(raw: String): ServerMessage {
        if (raw.toByteArray(Charsets.UTF_8).size > 262144) throw RemoteProtocolException("message_too_large")
        try {
            val reader = JsonReader(StringReader(raw)).apply { strictness = Strictness.STRICT }
            val root = JsonParser.parseReader(reader).asJsonObject
            require(reader.peek() == JsonToken.END_DOCUMENT)
            if (number(root, "version") != 2L) throw RemoteProtocolException("incompatible_version")
            keys(root, setOf("version", "type", "id", "timestamp", "payload", "correlationId"), setOf("correlationId"))
            val type = string(root, "type", 64); val id = string(root, "id", 128); date(root, "timestamp")
            val correlation = nullableString(root, "correlationId", 128)
            val p = root.getAsJsonObject("payload") ?: error("payload")
            val snapshot = if (type in snapshotTypes) snapshot(p) else {
                if (type.startsWith("call.")) CallWire.validateResponse(type, p)
                else if (type.startsWith("speech.")) SpeechWire.validate(type, p)
                else if (type.startsWith("conversation.")) ConversationWire.validate(type, p)
                else when (type) {
                    "server.hello" -> { keys(p, setOf("bridgeName")); string(p, "bridgeName", 80) }
                    "pair.success" -> { keys(p, setOf("deviceId", "token", "bridgeName")); credential(p); string(p, "bridgeName", 80) }
                    "auth.success" -> { keys(p, setOf("deviceId", "bridgeName")); require(string(p, "deviceId", 36).matches(Regex("[a-f0-9-]{36}"))); string(p, "bridgeName", 80) }
                    "auth.failed", "pair.failed", "protocol.error", "request.error" -> { keys(p, setOf("code")); string(p, "code", 64) }
                    "heartbeat" -> keys(p, emptySet())
                    "notification.status.response" -> {
                        keys(p, setOf("registered", "configured", "preferences", "diagnostic")); boolean(p, "registered"); boolean(p, "configured"); string(p, "diagnostic", 64)
                        val preferences = p.getAsJsonObject("preferences"); keys(preferences, setOf("enabled", "completed", "failed", "interrupted", "approval"))
                        preferences.keySet().forEach { boolean(preferences, it) }
                    }
                    "notification.event.response" -> {
                        keys(p, setOf("eventId", "threadId", "turnId", "kind", "mode", "occurredAt", "expiresAt", "approvalStatus", "expired", "result"))
                        require(string(p, "eventId", 64).matches(Regex("[a-f0-9]{64}"))); string(p, "threadId", 128); string(p, "turnId", 128)
                        require(string(p, "kind", 16) in setOf("completed", "failed", "interrupted", "approval")); require(string(p, "mode", 4) in setOf("mock", "real"))
                        date(p, "occurredAt"); date(p, "expiresAt"); boolean(p, "expired")
                        if (!p["result"].isJsonNull) require(p["result"].isJsonPrimitive && p["result"].asJsonPrimitive.isString && p["result"].asString.length <= 16000)
                        val status = nullableString(p, "approvalStatus", 16); require(status == null || status in setOf("pending", "accepted", "declined", "expired", "unavailable", "resolved"))
                    }
                    "notification.receipt.response" -> {
                        keys(p, setOf("eventId", "stage")); require(string(p, "eventId", 64).matches(Regex("[a-f0-9]{64}")))
                        require(string(p, "stage", 16) in setOf("received", "posted", "opened", "dismissed"))
                    }
                    "notification.error" -> { keys(p, setOf("code")); string(p, "code", 64) }
                    else -> throw RemoteProtocolException("unknown_message")
                }; null
            }
            return ServerMessage(type, id, correlation, p, snapshot)
        } catch (e: RemoteProtocolException) { throw e } catch (_: Exception) { throw RemoteProtocolException("invalid_message") }
    }
    private fun credential(p: JsonObject) {
        require(string(p, "deviceId", 36).matches(Regex("[a-f0-9-]{36}")))
        require(string(p, "token", 43).matches(Regex("[A-Za-z0-9_-]{43}")))
    }
    private fun snapshot(p: JsonObject): RemoteSnapshot {
        keys(p, setOf("epoch", "sequence", "bridgeName", "available", "mode", "selectedThreadId", "threads", "turns", "history", "historyComplete", "syncedAt"))
        val mode = string(p, "mode", 4); require(mode in setOf("real", "mock"))
        val threads = array(p, "threads", 50).map { v -> v.asJsonObject.let { r ->
            keys(r, setOf("id", "label", "project", "status")); val status = string(r, "status", 16); require(status in setOf("ready", "unavailable"))
            RemoteThread(string(r, "id", 128), string(r, "label", 80), string(r, "project", 80), status)
        } }
        require(threads.map { it.id }.distinct().size == threads.size)
        val turns = array(p, "turns", 100).map { v -> v.asJsonObject.let { r ->
            keys(r, setOf("id", "threadId", "status", "startedAt", "finishedAt")); val status = string(r, "status", 32)
            require(status in setOf("starting", "running", "awaitingApproval", "completed", "failed", "interrupted", "unknown"))
            val threadId = string(r, "threadId", 128); require(threads.any { it.id == threadId })
            RemoteTurn(string(r, "id", 128), threadId, status, date(r, "startedAt"), nullableString(r, "finishedAt", 40)?.let { Instant.parse(it).toEpochMilli() })
        } }
        require(turns.map { it.threadId + ":" + it.id }.distinct().size == turns.size)
        val history = array(p, "history", 100).map { v -> v.asJsonObject.let { r ->
            keys(r, setOf("id", "type", "timestamp", "summary", "threadId", "turnId")); val type = string(r, "type", 64); require(type in eventTypes)
            RemoteEvent(string(r, "id", 128), type, date(r, "timestamp"), string(r, "summary", 240), nullableString(r, "threadId", 128), nullableString(r, "turnId", 128))
        } }
        require(history.map { it.id }.distinct().size == history.size)
        val selected = nullableString(p, "selectedThreadId", 128); require(selected == null || threads.any { it.id == selected })
        return RemoteSnapshot(string(p, "epoch", 128), number(p, "sequence"), string(p, "bridgeName", 80), boolean(p, "available"), mode,
            selected, threads, turns, history, boolean(p, "historyComplete"), date(p, "syncedAt"))
    }
    private fun keys(p: JsonObject, allowed: Set<String>, optional: Set<String> = emptySet()) {
        require(p.keySet().all { it in allowed } && p.keySet().containsAll(allowed - optional))
    }
    fun string(p: JsonObject, key: String, max: Int): String {
        val v = p.get(key); require(v != null && v.isJsonPrimitive && v.asJsonPrimitive.isString)
        return v.asString.also { require(it.isNotEmpty() && it.length <= max && it.none { c -> c.code < 32 }) }
    }
    private fun nullableString(p: JsonObject, key: String, max: Int): String? = if (!p.has(key) || p.get(key).isJsonNull) null else string(p, key, max)
    private fun number(p: JsonObject, key: String): Long {
        val value = p.get(key); require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        return value.asBigDecimal.longValueExact().also { require(it in 0..9007199254740991L) }
    }
    private fun boolean(p: JsonObject, key: String): Boolean { val v = p.get(key); require(v.isJsonPrimitive && v.asJsonPrimitive.isBoolean); return v.asBoolean }
    private fun date(p: JsonObject, key: String): Long = Instant.parse(string(p, key, 40)).toEpochMilli()
    private fun array(p: JsonObject, key: String, max: Int): JsonArray = p.getAsJsonArray(key).also { require(it.size() <= max) }
}
