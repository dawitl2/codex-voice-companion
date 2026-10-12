package com.codexvoice.app.core.calls

import com.google.gson.JsonObject
import com.codexvoice.app.core.remote.RemoteProtocol
import java.time.Instant

data class CallOffer(val callId: String, val eventId: String, val threadId: String, val turnId: String, val kind: String,
    val mode: String, val createdAt: Long, val expiresAt: Long, val acceptedAt: Long?, val endedAt: Long?,
    val state: String, val muted: Boolean, val reason: String)
data class MediaGrant(val call: CallOffer, val token: String, val expiresAt: Long, val endpoint: String) {
    override fun toString() = "MediaGrant(redacted)"
}
object CallWire {
    val format: Map<String, Any> = linkedMapOf("codec" to "pcm_s16le", "sampleRate" to 16000, "channels" to 1, "frameMs" to 20, "samplesPerFrame" to 320)
    private fun keys(p: JsonObject, expected: Set<String>) { require(p.keySet() == expected) }
    private fun string(p: JsonObject, key: String, max: Int = 128) = RemoteProtocol.string(p, key, max)
    private fun bool(p: JsonObject, key: String): Boolean { require(p[key].isJsonPrimitive && p[key].asJsonPrimitive.isBoolean); return p[key].asBoolean }
    private fun date(p: JsonObject, key: String): Long { val value = string(p, key, 40); require(value.endsWith("Z")); return Instant.parse(value).toEpochMilli() }
    private fun nullableDate(p: JsonObject, key: String) = if (p[key].isJsonNull) null else date(p, key)
    fun format(p: JsonObject) {
        keys(p, format.keys)
        for ((key, value) in format) if (value is String) require(string(p, key) == value)
        else { require(p[key].isJsonPrimitive && p[key].asJsonPrimitive.isNumber); require(p[key].asBigDecimal.intValueExact() == value) }
    }
    fun offer(p: JsonObject): CallOffer {
        keys(p, setOf("callId","eventId","threadId","turnId","kind","mode","createdAt","expiresAt","acceptedAt","endedAt","state","muted","reason"))
        val id = string(p, "callId", 36); require(id.matches(Regex("[a-f0-9-]{36}")))
        val event = string(p, "eventId", 64); require(event.matches(Regex("[a-f0-9]{64}")))
        val kind = string(p, "kind", 16); require(kind in setOf("completed","failed","interrupted","approval","review"))
        val mode = string(p, "mode", 4); require(mode in setOf("mock","real"))
        val state = string(p, "state", 16); require(state in setOf("offered","connecting","active","reconnecting","ended","declined","expired","failed"))
        return CallOffer(id, event, string(p, "threadId"), string(p, "turnId"), kind, mode, date(p, "createdAt"), date(p, "expiresAt"), nullableDate(p, "acceptedAt"), nullableDate(p, "endedAt"), state, bool(p, "muted"), string(p, "reason"))
    }
    fun validateResponse(type: String, p: JsonObject) {
        when (type) {
            "call.preferences.response" -> { keys(p, setOf("enabled")); bool(p, "enabled") }
            "call.status.response" -> { keys(p, setOf("enabled","call")); bool(p, "enabled"); if (!p["call"].isJsonNull) offer(p.getAsJsonObject("call")) }
            "call.media.response" -> grant(p)
            "call.error" -> { keys(p, setOf("code")); require(string(p, "code", 64).matches(Regex("[a-z_]{1,64}"))) }
            else -> error("unknown_call_message")
        }
    }
    fun grant(p: JsonObject): MediaGrant {
        keys(p, setOf("call","token","tokenExpiresAt","mediaUrl","format"))
        val token = string(p, "token", 43); require(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        val url = string(p, "mediaUrl", 240); val uri = java.net.URI(url); require(uri.rawPath == "/media")
        RemoteProtocol.endpoint(java.net.URI(uri.scheme, uri.rawAuthority, "/remote", uri.rawQuery, uri.rawFragment).toASCIIString())
        format(p.getAsJsonObject("format")); return MediaGrant(offer(p.getAsJsonObject("call")), token, date(p, "tokenExpiresAt"), url)
    }
}
enum class LivePhase { IDLE, OFFERED, CONNECTING, ACTIVE, RECONNECTING, ENDED, DECLINED, EXPIRED, FAILED }
data class LiveCallState(val phase: LivePhase = LivePhase.IDLE, val offer: CallOffer? = null,
    val permissionDenied: Boolean = false, val problem: String = "", val muted: Boolean = false,
    val capturing: Boolean = false, val playing: Boolean = false, val speaker: Boolean = false, val route: String = "System route",
    val routeSupported: Boolean = false, val activeAt: Long = 0, val endedAt: Long = 0,
    val uplinkFrames: Long = 0, val downlinkFrames: Long = 0, val pcReceivedFrames: Long = 0,
    val inputLevel: Float = 0f, val outputLevel: Float = 0f, val dropped: Long = 0, val underruns: Long = 0,
    val rttMs: Long = 0, val serviceRunning: Boolean = false,
    val captureSampleRate: Int = 0, val playbackSampleRate: Int = 0, val renderedSamples: Long = 0)
fun canAnswer(offer: CallOffer?, now: Long, foreground: Boolean, permitted: Boolean) =
    offer?.state == "offered" && offer.expiresAt > now && foreground && permitted
fun canTransmit(phase: LivePhase, muted: Boolean, authorized: Boolean) = phase == LivePhase.ACTIVE && !muted && authorized
fun mayRecover(phase: LivePhase, foreground: Boolean, connected: Boolean, attempts: Int) =
    phase == LivePhase.RECONNECTING && foreground && connected && attempts in 0..1
fun acceptsCallSnapshot(phase: LivePhase, serverState: String) = phase == LivePhase.OFFERED || serverState in setOf("ended","failed","expired","declined")
