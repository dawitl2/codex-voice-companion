import { randomUUID } from 'node:crypto';
import { CallError, CallSessionManager, TERMINAL, type CallSession } from '../calls/CallSessionManager.js';
import type { Bridge } from '../core/Bridge.js';
import { SpeechError, type SpeechRecognitionEngine } from './SpeechRecognitionEngine.js';
import { UtteranceSegmenter, type SpeechSegment } from './UtteranceSegmenter.js';
import { spokenSummary } from './SpokenSummaryFormatter.js';
export interface FinalTranscription { utteranceId: string; sequence: number; startedAt: string; endedAt: string; text: string; language: 'en'; final: true; processingMs: number }
interface SpeechSession { callId: string; deviceId: string; enabled: boolean; speaking: boolean; segmenter: UtteranceSegmenter; results: FinalTranscription[]; sequence: number; error: string; abort: AbortController | null; pending: SpeechSegment | null; lastFrame: number }
export class SpeechSessionCoordinator {
  private readonly sessions = new Map<string, SpeechSession>();
  private readonly listeners = new Set<(call: CallSession, utterance: FinalTranscription) => void>();
  private engineState = 'loading'; private engineError = ''; private closed = false;
  private readonly unsubscribe: () => void;
  readonly initialization: Promise<void>;
  onTranscription(listener: (call: CallSession, utterance: FinalTranscription) => void) { this.listeners.add(listener); return () => this.listeners.delete(listener); }
  diagnostics() {
    return { engine: this.engineState, error: this.engineError, sessions: this.sessions.size,
      recognizing: [...this.sessions.values()].filter(s => s.abort !== null).length,
      pending: [...this.sessions.values()].filter(s => s.pending !== null).length,
      listening: [...this.sessions.values()].filter(s => s.enabled && !s.speaking).length };
  }
  constructor(private readonly calls: CallSessionManager, private readonly bridge: Bridge, private readonly engine: SpeechRecognitionEngine) {
    this.initialization = engine.prepare().then(() => { this.engineState = 'ready'; }, error => { this.engineState = 'unavailable'; this.engineError = error instanceof SpeechError ? error.code : 'asr_unavailable'; });
    this.unsubscribe = calls.onChange(call => { const session = this.sessions.get(call.callId); if (!session) return; if (TERMINAL.has(call.state) || call.state !== 'active') this.remove(session); else if (call.muted) this.reset(session); });
  }
  private owner(deviceId: string, callId: string): SpeechSession {
    const call = this.calls.status(deviceId, callId); if (this.closed || call.state !== 'active') throw new CallError('call_not_active');
    let session = this.sessions.get(callId);
    if (!session) { session = { callId, deviceId, enabled: false, speaking: false, segmenter: new UtteranceSegmenter(), results: [], sequence: 0, error: '', abort: null, pending: null, lastFrame: 0 }; this.sessions.set(callId, session); }
    return session;
  }
  status(deviceId: string, callId: string, afterSequence = 0) {
    const s = this.owner(deviceId, callId); const call = this.calls.status(deviceId, callId);
    const state = s.speaking ? 'speaking' : !s.enabled || call.muted ? 'paused' : s.error || this.engineState === 'unavailable' ? 'error' : s.abort ? 'processing' : s.segmenter.detecting ? 'speech_detected' : s.results.at(-1)?.sequence && s.sequence > afterSequence ? 'transcript_ready' : 'listening';
    return { callId, state, engine: this.engineState, language: 'en', error: s.error || this.engineError, utterances: s.results.filter(r => r.sequence > afterSequence).map(r => ({ ...r })) };
  }
  listening(deviceId: string, callId: string, enabled: boolean) { const s = this.owner(deviceId, callId); s.enabled = enabled; s.error = ''; if (!enabled) this.reset(s); return this.status(deviceId, callId); }
  playback(deviceId: string, callId: string, state: string) { const s = this.owner(deviceId, callId); s.speaking = state === 'started'; this.reset(s); return this.status(deviceId, callId); }
  summary(deviceId: string, callId: string) {
    this.owner(deviceId, callId); const call = this.calls.status(deviceId, callId);
    const thread = this.bridge.store.state.threads.find(t => t.id === call.threadId);
    const turn = this.bridge.store.state.turns.find(t => t.id === call.turnId && t.threadId === call.threadId);
    return { callId, text: spokenSummary(thread, turn), mode: call.mode, taskState: turn?.status ?? 'unavailable' };
  }
  frame(call: CallSession, pcm: Buffer, level: number) {
    const s = this.sessions.get(call.callId); if (!s || s.deviceId !== call.deviceId || !s.enabled || s.speaking || call.muted || call.state !== 'active' || this.engineState !== 'ready') return;
    const now = Date.now(); if (s.lastFrame && now - s.lastFrame > 500) s.segmenter.clear(); s.lastFrame = now;
    const segment = s.segmenter.accept(pcm, level, now); if (segment) this.enqueue(s, segment);
  }
  finish(deviceId: string, callId: string) { const s = this.owner(deviceId, callId); if (s.enabled && !s.speaking) { const segment = s.segmenter.finish(); if (segment) this.enqueue(s, segment); } return this.status(deviceId, callId); }
  private enqueue(s: SpeechSession, segment: SpeechSegment) {
    if (s.abort) { if (s.pending) { segment.pcm.fill(0); s.error = 'recognition_queue_full'; } else s.pending = segment; return; }
    const abort = new AbortController(); s.abort = abort; s.error = '';
    void this.engine.recognize(segment.pcm, abort.signal).then(result => {
      if (abort.signal.aborted || this.sessions.get(s.callId) !== s || this.closed) return;
      const call = this.calls.status(s.deviceId, s.callId); if (call.state !== 'active' || s.speaking || !s.enabled || call.muted) return;
      const utterance: FinalTranscription = { utteranceId: randomUUID(), sequence: ++s.sequence, startedAt: new Date(segment.startedAt).toISOString(), endedAt: new Date(segment.endedAt).toISOString(), text: result.text, language: 'en', final: true, processingMs: result.processingMs };
      s.results.push(utterance);
      if (s.results.length > 8) s.results.shift();
      for (const listener of this.listeners) { try { listener({ ...call, deviceId: s.deviceId }, { ...utterance }); } catch { /* Consumer errors cannot crash ASR/media. */ } }
    }).catch(error => { if (!abort.signal.aborted && this.sessions.get(s.callId) === s) s.error = error instanceof SpeechError ? error.code : 'recognition_failed'; })
      .finally(() => { segment.pcm.fill(0); if (s.abort !== abort) return; s.abort = null; const pending = s.pending; s.pending = null; if (pending && this.sessions.get(s.callId) === s && s.enabled && !s.speaking) this.enqueue(s, pending); else pending?.pcm.fill(0); });
  }
  private reset(s: SpeechSession) { s.segmenter.clear(); s.abort?.abort(); s.pending?.pcm.fill(0); s.pending = null; s.lastFrame = 0; }
  private remove(s: SpeechSession) { this.reset(s); s.results = []; this.sessions.delete(s.callId); }
  close() { this.closed = true; this.unsubscribe(); this.listeners.clear(); for (const s of this.sessions.values()) this.remove(s); this.engine.close(); }
}
