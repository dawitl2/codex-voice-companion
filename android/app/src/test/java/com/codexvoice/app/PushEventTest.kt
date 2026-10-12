package com.codexvoice.app

import com.codexvoice.app.core.notifications.*
import com.codexvoice.app.core.model.UserPreferences
import com.codexvoice.app.core.remote.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val testCredential = Credential("wss://test.example.ts.net/remote", "00000000-0000-0000-0000-000000000000", "A".repeat(43), "Fixture")
fun signedPush(credential: Credential, now: Long = System.currentTimeMillis(), kind: String = "completed", eventId: String = "a".repeat(64)): Map<String, String> {
    val data = linkedMapOf("version" to "1", "eventId" to eventId, "pairingId" to credential.deviceId, "kind" to kind,
        "occurredAt" to Instant.ofEpochMilli(now).toString(), "expiresAt" to Instant.ofEpochMilli(now + if (kind == "approval") 60000 else 1800000).toString(), "mode" to "mock")
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(credential.token.toByteArray()), "HmacSHA256")) }
    return data + ("signature" to Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.values.joinToString("\n").toByteArray())))
}
class PushEventTest {
    private val now = Instant.parse("2026-10-12T00:00:00Z").toEpochMilli()
    private fun parsed() = PushParser.parse(signedPush(testCredential, now), testCredential, now)!!
    @Test fun parsesSupportedSignedMinimalPush() {
        val event = parsed(); assertEquals("completed", event.kind); assertEquals("Task completed", event.title); assertEquals("mock", event.mode)
        assertFalse(event.toString().contains(testCredential.token))
    }
    @Test fun rejectsUnpairedAndOtherPairing() {
        val data = signedPush(testCredential, now); assertNull(PushParser.parse(data, null, now)); assertNull(PushParser.parse(data, testCredential.copy(deviceId = "other"), now))
    }
    @Test fun rejectsForgedOrTamperedPayload() {
        val data = signedPush(testCredential, now)
        for (bad in listOf(data + ("mode" to "real"), data + ("kind" to "failed"), data + ("signature" to "B".repeat(43)), data + ("command" to "execute"))) assertNull(PushParser.parse(bad, testCredential, now))
    }
    @Test fun rejectsUnknownVersionMalformedIdentifiersAndInvalidTimes() {
        val data = signedPush(testCredential, now)
        for (bad in listOf(data + ("version" to "2"), data + ("eventId" to "url"), data + ("occurredAt" to "tomorrow"), data + ("kind" to "execute"), data - "expiresAt")) assertNull(PushParser.parse(bad, testCredential, now))
        assertNull(PushParser.parse(signedPush(testCredential, now + 6000), testCredential, now))
    }
    @Test fun expiresCompletionAndApprovalAtTheirDeadline() {
        assertNull(PushParser.parse(signedPush(testCredential, now), testCredential, now + 1800000))
        assertNull(PushParser.parse(signedPush(testCredential, now, "approval"), testCredential, now + 60000))
        assertNotNull(PushParser.parse(signedPush(testCredential, now), testCredential, now + 1800000, allowExpired = true))
    }
    @Test fun persistedDedupSuppressesRepeatedAlertBeforeExpiry() {
        val event = parsed(); val prefs = PushPreferences(enabled = true)
        assertTrue(acceptsPush(event, prefs, emptyMap(), now)); assertFalse(acceptsPush(event, prefs, mapOf(event.eventId to event.expires), now))
    }
    @Test fun globalAndCategoryDisabledPreferencesRejectAlerts() {
        val event = parsed(); assertFalse(acceptsPush(event, PushPreferences(), emptyMap(), now))
        assertFalse(acceptsPush(event, PushPreferences(enabled = true, completed = false), emptyMap(), now))
        for (kind in listOf("failed", "interrupted", "approval")) {
            val data = signedPush(testCredential, now, kind); val candidate = PushParser.parse(data, testCredential, now)!!
            assertFalse(acceptsPush(candidate, PushPreferences(enabled = true, failed = false, interrupted = false, approval = false), emptyMap(), now))
        }
    }
    @Test fun duplicateBoundFailsClosedAndExpiredEntriesDoNotConsumeCapacity() {
        val event = parsed(); val full = (0..499).associate { it.toString() to event.expires }; assertFalse(acceptsPush(event, PushPreferences(enabled = true), full, now))
        assertTrue(acceptsPush(event, PushPreferences(enabled = true), full.mapValues { now - 1 }, now))
    }
    @Test fun existingPreferenceDefaultsArePreservedAndNotificationDefaultIsOff() {
        val prefs = UserPreferences(); assertTrue(prefs.callOnCompletion); assertTrue(prefs.callOnFailure); assertTrue(prefs.callOnApproval); assertFalse(prefs.notificationsEnabled)
        assertEquals(false, prefs.pushPreferences().server()["enabled"]); assertTrue(prefs.notificationSound); assertTrue(prefs.notificationVibration)
    }
    @Test fun navigationRequiresPairedConnectedAndMatchingAuthenticatedDetails() {
        val event = parsed(); val snapshot = RemoteProtocol.parse(RemoteProtocol.message("state.snapshot", snapshotFixture())).snapshot!!
        val details = com.google.gson.JsonObject().apply { addProperty("eventId", event.eventId); addProperty("mode", "mock") }
        assertTrue(notificationDetailsAvailable(event, true, true, snapshot, details))
        assertFalse(notificationDetailsAvailable(event, false, true, snapshot, details)); assertFalse(notificationDetailsAvailable(event, true, false, snapshot, details))
        assertFalse(notificationDetailsAvailable(event, true, true, snapshot.copy(mode = "real"), details)); assertFalse(notificationDetailsAvailable(event, true, true, snapshot, null))
    }
    @Test fun notificationProtocolValidatesStatusReceiptsAndExcludesPrivilegedMessages() {
        val status = mapOf("registered" to true, "configured" to false, "preferences" to PushPreferences().server(), "diagnostic" to "provider_disabled")
        assertEquals("notification.status.response", RemoteProtocol.parse(RemoteProtocol.message("notification.status.response", status)).type)
        try { RemoteProtocol.parse(RemoteProtocol.message("notification.status.response", status + ("fid" to "private"))); fail() } catch (_: RemoteProtocolException) { }
        try { RemoteProtocol.parse(RemoteProtocol.message("notification.execute", mapOf("command" to "danger"))); fail() } catch (_: RemoteProtocolException) { }
    }
}
