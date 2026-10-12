package com.codexvoice.app.core.calls

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import kotlin.math.sqrt

object AudioFrames {
    const val BYTES = 660
    fun encode(target: ByteArray, samples: ShortArray, sequence: Long, timestamp: Long, direction: Int = 0) {
        require(target.size == BYTES && samples.size == 320 && sequence in 0..0xffffffffL && timestamp in 0..9007199254740991L && direction in 0..1)
        ByteBuffer.wrap(target).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x5643); put(1); put(direction.toByte()); putInt(sequence.toInt()); putLong(timestamp); putShort(320); putShort(0)
            samples.forEach { putShort(it) }
        }
    }
    data class Frame(val sequence: Long, val timestamp: Long, val pcm: ByteArray, val level: Float)
    fun decode(bytes: ByteArray, direction: Int): Frame {
        require(bytes.size == BYTES && direction in 0..1)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(b.short.toInt() == 0x5643 && b.get().toInt() == 1 && b.get().toInt() == direction)
        val sequence = b.int.toLong() and 0xffffffffL; val timestamp = b.long
        require(timestamp in 0..9007199254740991L && b.short.toInt() == 320 && b.short.toInt() == 0)
        var squares = 0.0; while (b.hasRemaining()) squares += (b.short.toDouble() / 32768).let { it * it }
        return Frame(sequence, timestamp, bytes.copyOfRange(20, BYTES), sqrt(squares / 320).toFloat())
    }
    fun level(samples: ShortArray): Float = sqrt(samples.sumOf { (it.toDouble() / 32768).let { sample -> sample * sample } } / samples.size).toFloat()
}
class FrameSequence {
    private var sequence = -1L; private var timestamp = -1L
    private var senderOrigin = -1L; private var arrivalOrigin = -1L
    var dropped = 0L; private set
    fun accept(frame: AudioFrames.Frame, arrival: Long): Boolean {
        require(frame.sequence > sequence && frame.timestamp >= timestamp)
        if (sequence >= 0) dropped += frame.sequence - sequence - 1
        sequence = frame.sequence; timestamp = frame.timestamp
        if (senderOrigin < 0) { senderOrigin = timestamp; arrivalOrigin = arrival }
        if (arrival - arrivalOrigin - (timestamp - senderOrigin) > 500) { dropped++; return false }
        return true
    }
}
class PcmJitterBuffer(private val capacity: Int = 10) {
    data class Chunk(val pcm: ByteArray, val arrivedAt: Long, val level: Float)
    private val queue = ArrayDeque<Chunk>(); var dropped = 0L; private set
    init { require(capacity in 1..25) }
    @Synchronized fun add(chunk: Chunk) { require(chunk.pcm.size == 640); if (queue.size == capacity) { queue.removeFirst().pcm.fill(0); dropped++ }; queue.addLast(chunk) }
    @Synchronized fun take(now: Long): Chunk? { while (queue.isNotEmpty()) { val next = queue.removeFirst(); if (now - next.arrivedAt <= 250) return next; next.pcm.fill(0); dropped++ }; return null }
    @Synchronized fun clear() { queue.forEach { it.pcm.fill(0) }; queue.clear() }
    @Synchronized fun size() = queue.size
}
