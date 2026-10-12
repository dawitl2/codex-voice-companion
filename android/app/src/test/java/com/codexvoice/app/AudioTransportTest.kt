package com.codexvoice.app

import com.codexvoice.app.core.calls.*
import com.codexvoice.app.core.remote.RemoteProtocol
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AudioTransportTest {
    @Test fun pcmFrameMatchesTheActualNodeLittleEndianWireContract() {
        val target = ByteArray(660); AudioFrames.encode(target, ShortArray(320) { 16384 }, 7, 1234)
        assertEquals("4356010007000000d20400000000000040010000", target.take(20).joinToString("") { "%02x".format(it) })
        val decoded = AudioFrames.decode(target, 0); assertEquals(7, decoded.sequence); assertEquals(1234, decoded.timestamp); assertEquals(.5f, decoded.level)
    }
    @Test fun framesRejectWrongDirectionSizeSampleCountReservedBytesAndUnsafeTimestamp() {
        val frame = ByteArray(660); AudioFrames.encode(frame, ShortArray(320), 0, 0)
        for (bad in listOf(frame.copyOf(659), frame.copyOf(661), frame.copyOf().apply { this[16] = 0 }, frame.copyOf().apply { this[18] = 1 }, frame.copyOf().apply { this[15] = 127 })) assertThrows(IllegalArgumentException::class.java) { AudioFrames.decode(bad, 0) }
        assertThrows(IllegalArgumentException::class.java) { AudioFrames.decode(frame, 1) }
    }
    @Test fun frameSequenceRejectsDuplicatesAndClockReversalAndDropsStalePackets() {
        val stream = FrameSequence(); val frame = AudioFrames.Frame(0, 1000, ByteArray(640), 0f)
        assertTrue(stream.accept(frame, 0)); assertThrows(IllegalArgumentException::class.java) { stream.accept(frame, 20) }
        assertFalse(stream.accept(frame.copy(sequence = 2, timestamp = 1040), 1000)); assertEquals(2, stream.dropped)
    }
    @Test fun jitterBufferBoundsMemoryDropsOldestAndDiscardsLateAudio() {
        val buffer = PcmJitterBuffer(3)
        repeat(100) { buffer.add(PcmJitterBuffer.Chunk(ByteArray(640), it.toLong(), 0f)) }
        assertEquals(3, buffer.size()); assertEquals(97L, buffer.dropped); assertEquals(97L, buffer.take(100)?.arrivedAt)
        assertNull(buffer.take(1000)); assertEquals(0, buffer.size()); buffer.clear()
    }
    @Test fun muteAndMediaAuthorizationActuallyGateTransmission() {
        assertTrue(canTransmit(LivePhase.ACTIVE, false, true))
        assertFalse(canTransmit(LivePhase.ACTIVE, true, true)); assertFalse(canTransmit(LivePhase.ACTIVE, false, false))
        for (phase in LivePhase.entries.filter { it != LivePhase.ACTIVE }) assertFalse(canTransmit(phase, false, true))
    }
    private fun offer() = CallOffer(UUID.randomUUID().toString(), "a".repeat(64), "thread", "turn", "completed", "mock", 0, 2000, null, null, "offered", false, "task_event")
    @Test fun permissionDeniedExpiredOrBackgroundAnswerCannotStartCapture() {
        val offer = offer(); assertTrue(canAnswer(offer, 1000, true, true))
        assertFalse(canAnswer(offer, 1000, true, false)); assertFalse(canAnswer(offer, 1000, false, true)); assertFalse(canAnswer(offer, 2000, true, true))
        assertFalse(canAnswer(offer.copy(state = "active"), 1000, true, true))
    }
    @Test fun recoveryRequiresForegroundAuthenticatedControlAndHasATwoAttemptLimit() {
        assertTrue(mayRecover(LivePhase.RECONNECTING, true, true, 0)); assertTrue(mayRecover(LivePhase.RECONNECTING, true, true, 1))
        assertFalse(mayRecover(LivePhase.RECONNECTING, true, true, 2)); assertFalse(mayRecover(LivePhase.RECONNECTING, false, true, 0)); assertFalse(mayRecover(LivePhase.RECONNECTING, true, false, 0))
    }
    @Test fun delayedOfferedStatusCannotRegressAnswerOrResurrectACancelledCall() {
        assertTrue(acceptsCallSnapshot(LivePhase.OFFERED, "offered"))
        assertFalse(acceptsCallSnapshot(LivePhase.CONNECTING, "offered"))
        assertFalse(acceptsCallSnapshot(LivePhase.ENDED, "connecting"))
        assertTrue(acceptsCallSnapshot(LivePhase.CONNECTING, "expired"))
    }
    @Test fun callProtocolRemainsStrictAndMediaSecretsAreRedacted() {
        val row = JsonParser.parseString("""{"callId":"${UUID.randomUUID()}","eventId":"${"a".repeat(64)}","threadId":"thread","turnId":"turn","kind":"completed","mode":"mock","createdAt":"2026-10-12T00:00:00Z","expiresAt":"2026-10-12T00:02:00Z","acceptedAt":null,"endedAt":null,"state":"offered","muted":false,"reason":"task_event"}""").asJsonObject
        assertEquals("offered", CallWire.offer(row).state)
        row.addProperty("deviceId", UUID.randomUUID().toString()); assertThrows(IllegalArgumentException::class.java) { CallWire.offer(row) }
        assertEquals("MediaGrant(redacted)", MediaGrant(offer(), "secret", 1000, "wss://test.example.ts.net/media").toString())
        assertThrows(Exception::class.java) { RemoteProtocol.parse(RemoteProtocol.message("call.unknown")) }
    }
}
