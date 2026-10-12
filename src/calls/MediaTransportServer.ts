import type { IncomingMessage } from 'node:http';
import type { Duplex } from 'node:stream';
import { performance } from 'node:perf_hooks';
import { WebSocket, WebSocketServer } from 'ws';
import { CallError, CallSessionManager, TERMINAL, type CallSession } from './CallSessionManager.js';
import { audioFrame, AudioSequence, PCM, validateAudioFrame } from './AudioProtocol.js';

interface Metrics { uplinkFrames: number; downlinkFrames: number; droppedFrames: number; sequenceErrors: number; inputLevel: number; relativeDelayMs: number; rttMs: number; muted: boolean }
interface MediaSession {
  socket: WebSocket; call: CallSession; ready: boolean; sequence: AudioSequence; metrics: Metrics;
  timeout: NodeJS.Timeout; timer: NodeJS.Timeout; tone?: NodeJS.Timeout; toneAt: number; outputSequence: number;
  lastPong: number; pingAt: number; lastAudio: number; controlWindow: number; controls: number;
}
export class MediaTransportServer {
  readonly endpoint: string;
  private readonly server = new WebSocketServer({ noServer: true, maxPayload: 1024, perMessageDeflate: false });
  private readonly sessions = new Map<string, MediaSession>();
  private readonly ended: { callId: string; metrics: Metrics }[] = [];
  private readonly unsubscribe: () => void;
  private closed = false;
  constructor(private readonly calls: CallSessionManager, remoteEndpoint: string) {
    const url = new URL(remoteEndpoint); url.pathname = '/media'; this.endpoint = url.href;
    this.unsubscribe = calls.onChange(call => {
      const session = this.sessions.get(call.callId); if (!session) return;
      session.call = call; session.metrics.muted = call.muted;
      if (TERMINAL.has(call.state)) { this.send(session, { type: 'media.ended', reason: call.reason }); session.socket.close(1000, 'Call ended'); this.remove(session); }
    });
  }
  upgrade(request: IncomingMessage, socket: Duplex, head: Buffer, identity: string) {
    try {
      if (this.closed || request.url !== '/media' || this.sessions.size >= 2 || typeof request.headers.authorization !== 'string' ||
          !/^Bearer [A-Za-z0-9_-]{43}$/.test(request.headers.authorization)) throw new CallError('media_unauthorized');
      const call = this.calls.consume(request.headers.authorization.slice(7), identity);
      if (this.sessions.has(call.callId)) throw new CallError('media_unauthorized');
      this.server.handleUpgrade(request, socket, head, ws => this.accept(ws, call));
    } catch { socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n'); }
  }
  private accept(socket: WebSocket, call: CallSession) {
    const session = { socket, call, ready: false, sequence: new AudioSequence(),
      metrics: { uplinkFrames: 0, downlinkFrames: 0, droppedFrames: 0, sequenceErrors: 0, inputLevel: 0, relativeDelayMs: 0, rttMs: 0, muted: call.muted },
      toneAt: -2000, outputSequence: 0, lastPong: performance.now(), pingAt: 0, lastAudio: 0, controlWindow: 0, controls: 0 } as MediaSession;
    this.sessions.set(call.callId, session);
    session.timeout = setTimeout(() => this.fail(session, 'media_handshake_timeout'), 5000);
    session.timer = setInterval(() => {
      if (!session.ready) return;
      if (performance.now() - session.lastPong > 15000) { this.fail(session, 'media_health_timeout'); return; }
      if (performance.now() - session.pingAt > 5000) { session.pingAt = performance.now(); socket.ping(String(session.pingAt)); }
      if (performance.now() - session.lastAudio > 200) session.metrics.inputLevel = 0;
      this.send(session, { type: 'media.metrics', callId: call.callId, ...session.metrics });
    }, 1000);
    socket.on('pong', data => { if (data.toString() === String(session.pingAt)) { session.lastPong = performance.now(); session.metrics.rttMs = Math.round(session.lastPong - session.pingAt); } });
    socket.on('error', error => { if ('code' in error && String(error.code).startsWith('WS_ERR_')) this.fail(session, 'invalid_media'); });
    socket.on('close', code => { this.remove(session); if ([1002,1007,1008,1009].includes(code)) this.calls.fail(call.deviceId, call.callId, 'invalid_media'); else this.calls.disconnect(call.deviceId, call.callId); });
    socket.on('message', (data, binary) => {
      try {
        if (binary) {
          if (!session.ready || this.calls.status(call.deviceId, call.callId).state !== 'active') throw new Error('media_not_active');
          const frame = validateAudioFrame(Buffer.from(data as Buffer), 0);
          const timely = session.sequence.accept(frame.sequence, frame.timestamp, performance.now());
          session.metrics.droppedFrames = session.sequence.dropped; session.metrics.relativeDelayMs = Math.round(session.sequence.relativeDelayMs);
          if (timely && !session.call.muted) { session.metrics.uplinkFrames++; session.metrics.inputLevel = frame.level; session.lastAudio = performance.now(); }
          // No raw audio buffer is retained or logged. Later consumers belong behind this boundary.
        } else this.control(session, JSON.parse(data.toString()) as Record<string, unknown>);
      } catch (error) {
        if (error instanceof Error && error.message === 'audio_sequence_error') session.metrics.sequenceErrors++;
        this.fail(session, 'invalid_media');
      }
    });
  }
  private control(session: MediaSession, message: Record<string, unknown>) {
    const now = performance.now(); if (now - session.controlWindow > 1000) { session.controlWindow = now; session.controls = 0; }
    if (++session.controls > 10 || !message || typeof message !== 'object' || Array.isArray(message) || message.version !== 1) throw new Error('invalid_media_control');
    const fields = message.type === 'media.hello' ? ['version','type','callId','format'] : message.type === 'media.mute' ? ['version','type','muted'] : ['version','type'];
    if (Object.keys(message).length !== fields.length || fields.some(k => !Object.hasOwn(message, k))) throw new Error('invalid_media_control');
    if (message.type === 'media.hello') {
      if (session.ready || message.callId !== session.call.callId || !message.format || typeof message.format !== 'object' || Array.isArray(message.format)) throw new Error('invalid_media_format');
      const format = message.format as Record<string, unknown>;
      if (Object.keys(format).length !== Object.keys(PCM).length || Object.entries(PCM).some(([key, value]) => format[key] !== value)) throw new Error('invalid_media_format');
      this.calls.activate(session.call.deviceId, session.call.callId); clearTimeout(session.timeout); session.ready = true;
      this.send(session, { type: 'media.ready', callId: session.call.callId, format: PCM }); return;
    }
    if (!session.ready) throw new Error('media_not_active');
    switch (message.type) {
      case 'media.mute': if (typeof message.muted !== 'boolean') throw new Error('invalid_media_control'); this.calls.mute(session.call.deviceId, session.call.callId, message.muted); break;
      case 'media.end': this.calls.end(session.call.deviceId, session.call.callId); break;
      case 'media.tone': try { this.tone(session.call.callId); } catch (error) { this.send(session, { type: 'media.error', code: error instanceof CallError ? error.code : 'tone_unavailable' }); } break;
      default: throw new Error('unknown_media_control');
    }
  }
  private send(session: MediaSession, message: Record<string, unknown>) {
    if (session.socket.readyState !== WebSocket.OPEN) return;
    if (session.socket.bufferedAmount > 4096) { this.fail(session, 'media_backpressure'); return; }
    session.socket.send(JSON.stringify({ version: 1, ...message }));
  }
  tone(callId: string) {
    const session = this.sessions.get(callId);
    if (!session?.ready || session.call.state !== 'active') throw new CallError('call_not_active');
    if (session.tone || performance.now() - session.toneAt < 2000) throw new CallError('tone_busy');
    session.toneAt = performance.now(); let index = 0;
    // A finite, low-amplitude 600 ms PC-generated diagnostic; never AI speech or local phone audio.
    session.tone = setInterval(() => {
      if (session.socket.readyState !== WebSocket.OPEN || session.call.state !== 'active' || index >= 30) { clearInterval(session.tone); delete session.tone; return; }
      const pcm = Buffer.alloc(640);
      for (let i = 0; i < 320; i++) { const sample = index * 320 + i; const ramp = Math.min(1, sample / 320, (9600 - sample) / 320); pcm.writeInt16LE(Math.round(Math.sin(2 * Math.PI * 440 * sample / 16000) * 2621 * ramp), i * 2); }
      index++;
      if (session.socket.bufferedAmount > 2048) { session.metrics.droppedFrames++; return; }
      session.socket.send(audioFrame(session.outputSequence++, Math.floor(performance.now()), pcm, 1)); session.metrics.downlinkFrames++;
    }, 20);
  }
  private fail(session: MediaSession, reason: string) { this.remove(session); this.calls.fail(session.call.deviceId, session.call.callId, reason); session.socket.close(1008, reason); }
  private remove(session: MediaSession) {
    if (this.sessions.get(session.call.callId) !== session) return;
    clearTimeout(session.timeout); clearInterval(session.timer); if (session.tone) clearInterval(session.tone);
    this.sessions.delete(session.call.callId); this.ended.push({ callId: session.call.callId, metrics: { ...session.metrics } }); if (this.ended.length > 10) this.ended.shift();
  }
  diagnostics() { return [...this.ended, ...[...this.sessions.values()].map(s => ({ callId: s.call.callId, metrics: { ...s.metrics } }))]; }
  get connected() { return this.sessions.size; }
  async close() { if (this.closed) return; this.closed = true; this.unsubscribe(); for (const session of this.sessions.values()) { this.calls.fail(session.call.deviceId, session.call.callId, 'bridge_stopped'); session.socket.terminate(); this.remove(session); } await new Promise<void>(resolve => this.server.close(() => resolve())); }
}
