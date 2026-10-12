package com.codexvoice.app.core.notifications

import com.codexvoice.app.core.remote.Credential
import com.codexvoice.app.core.model.UserPreferences
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class PushPreferences(val enabled: Boolean = false, val completed: Boolean = true, val failed: Boolean = true,
    val interrupted: Boolean = false, val approval: Boolean = true, val sound: Boolean = true, val vibration: Boolean = true) {
    fun allows(kind: String) = enabled && when (kind) { "completed" -> completed; "failed" -> failed; "interrupted" -> interrupted; "approval" -> approval; else -> false }
    fun server() = mapOf("enabled" to enabled, "completed" to completed, "failed" to failed, "interrupted" to interrupted, "approval" to approval)
}
fun UserPreferences.pushPreferences() = PushPreferences(notificationsEnabled, callOnCompletion, callOnFailure, alertOnInterruption, callOnApproval, notificationSound, notificationVibration)
data class PushEvent(val eventId: String, val pairingId: String, val kind: String, val occurredAt: String, val expiresAt: String, val mode: String, val signature: String) {
    val title get() = when (kind) { "completed" -> "Task completed"; "failed" -> "Task failed"; "interrupted" -> "Task interrupted"; else -> "Codex needs attention" }
    val expires get() = Instant.parse(expiresAt).toEpochMilli()
    fun data() = mapOf("version" to "1", "eventId" to eventId, "pairingId" to pairingId, "kind" to kind, "occurredAt" to occurredAt, "expiresAt" to expiresAt, "mode" to mode, "signature" to signature)
    override fun toString() = "PushEvent(redacted)"
}
object PushParser {
    private val fields = setOf("version", "eventId", "pairingId", "kind", "occurredAt", "expiresAt", "mode", "signature")
    fun parse(data: Map<String, String>, credential: Credential?, now: Long, allowExpired: Boolean = false): PushEvent? = try {
        require(credential != null && data.keys == fields && data.values.all { it.length in 1..80 && it.none { c -> c.code < 32 } })
        require(data["version"] == "1" && data["eventId"]!!.matches(Regex("[a-f0-9]{64}")) && data["pairingId"] == credential.deviceId)
        require(data["kind"] in setOf("completed", "failed", "interrupted", "approval") && data["mode"] in setOf("mock", "real"))
        val started = Instant.parse(data["occurredAt"]).toEpochMilli(); val expires = Instant.parse(data["expiresAt"]).toEpochMilli()
        require(data["occurredAt"]!!.endsWith("Z") && data["expiresAt"]!!.endsWith("Z") && started <= now + 5000 && expires > started && expires - started <= 1800000)
        require(allowExpired || expires > now)
        require(data["signature"]!!.matches(Regex("[A-Za-z0-9_-]{43}")))
        val canonical = listOf("version", "eventId", "pairingId", "kind", "occurredAt", "expiresAt", "mode").joinToString("\n") { data[it]!! }
        val key = MessageDigest.getInstance("SHA-256").digest(credential.token.toByteArray(Charsets.UTF_8))
        val expected = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(canonical.toByteArray(Charsets.UTF_8)) }
        require(MessageDigest.isEqual(expected, Base64.getUrlDecoder().decode(data["signature"])))
        PushEvent(data["eventId"]!!, data["pairingId"]!!, data["kind"]!!, data["occurredAt"]!!, data["expiresAt"]!!, data["mode"]!!, data["signature"]!!)
    } catch (_: Exception) { null }
}
