export const PCM = Object.freeze({ codec: 'pcm_s16le', sampleRate: 16000, channels: 1, frameMs: 20, samplesPerFrame: 320 });
export const AUDIO_FRAME_BYTES = 660;
export function audioFrame(sequence: number, timestamp: number, pcm: Buffer, direction: 0 | 1): Buffer {
  if (pcm.length !== 640 || !Number.isInteger(sequence) || sequence < 0 || sequence > 0xffffffff || !Number.isSafeInteger(timestamp) || timestamp < 0) throw new Error('invalid_audio_frame');
  const frame = Buffer.alloc(AUDIO_FRAME_BYTES); frame.writeUInt16LE(0x5643); frame[2] = 1; frame[3] = direction;
  frame.writeUInt32LE(sequence, 4); frame.writeBigUInt64LE(BigInt(timestamp), 8); frame.writeUInt16LE(320, 16); pcm.copy(frame, 20); return frame;
}
export function validateAudioFrame(frame: Buffer, direction: 0 | 1): { sequence: number; timestamp: number; pcm: Buffer; level: number } {
  if (frame.length !== AUDIO_FRAME_BYTES || frame.readUInt16LE(0) !== 0x5643 || frame[2] !== 1 || frame[3] !== direction ||
      frame.readUInt16LE(16) !== 320 || frame.readUInt16LE(18) !== 0) throw new Error('invalid_audio_frame');
  const timestamp = Number(frame.readBigUInt64LE(8)); if (!Number.isSafeInteger(timestamp)) throw new Error('invalid_audio_timestamp');
  let squares = 0; for (let i = 20; i < frame.length; i += 2) squares += (frame.readInt16LE(i) / 32768) ** 2;
  return { sequence: frame.readUInt32LE(4), timestamp, pcm: frame.subarray(20), level: Math.sqrt(squares / 320) };
}
/** Timestamps are sender-monotonic: only relative transit delay can be inferred. */
export class AudioSequence {
  private sequence = -1; private timestamp = -1; private origin: { sender: number; arrival: number } | undefined;
  private window = -1; private count = 0;
  dropped = 0; sequenceErrors = 0; relativeDelayMs = 0;
  accept(sequence: number, timestamp: number, arrival: number): boolean {
    if (arrival - this.window >= 1000 || this.window < 0) { this.window = arrival; this.count = 0; }
    if (++this.count > 75) throw new Error('audio_rate_limited');
    if (sequence <= this.sequence || timestamp < this.timestamp) { this.sequenceErrors++; throw new Error('audio_sequence_error'); }
    if (this.sequence >= 0) this.dropped += sequence - this.sequence - 1;
    this.sequence = sequence; this.timestamp = timestamp;
    this.origin ??= { sender: timestamp, arrival };
    this.relativeDelayMs = Math.max(0, arrival - this.origin.arrival - (timestamp - this.origin.sender));
    if (this.relativeDelayMs > 500) { this.dropped++; return false; }
    return true;
  }
}
export class BoundedAudioQueue<T> {
  private readonly items: T[] = []; dropped = 0;
  constructor(readonly capacity = 10) { if (capacity < 1 || capacity > 25) throw new Error('invalid_audio_capacity'); }
  push(value: T) { if (this.items.length >= this.capacity) { this.items.shift(); this.dropped++; } this.items.push(value); }
  shift(): T | undefined { return this.items.shift(); }
  clear() { this.items.length = 0; }
  get size() { return this.items.length; }
}
