import { createHash, randomBytes, randomUUID } from 'node:crypto';
import type { RegistryStorage } from '../remote/DeviceRegistry.js';
import type { NotificationEvent } from '../notifications/NotificationPolicy.js';

export const CALL_STATES = ['offered','connecting','active','reconnecting','ended','declined','expired','failed'] as const;
export type CallState = typeof CALL_STATES[number];
export const TERMINAL = new Set<CallState>(['ended','declined','expired','failed']);
export interface CallSession {
  callId: string; deviceId: string; eventId: string; threadId: string; turnId: string;
  kind: NotificationEvent['kind'] | 'review' | 'conversation'; mode: 'mock' | 'real'; createdAt: string; expiresAt: string;
  acceptedAt: string | null; endedAt: string | null; state: CallState; muted: boolean; reason: string;
  approvalId?: string;
  presentedAt?: string;
}
export type CallView = Omit<CallSession, 'deviceId' | 'approvalId' | 'presentedAt'>;
export interface CallRegistry { hasDevice(id: string, identity?: string): boolean }
export class CallError extends Error { constructor(readonly code: string) { super(code); } }
interface Grant { callId: string; deviceId: string; identity: string; token: string; expires: number }
const digest = (value: string) => createHash('sha256').update(value).digest('hex');

/** Persist lifecycle metadata only. Media grants and audio never enter this store. */
export class CallSessionManager {
  private sessions: CallSession[] = [];
  private enabled: Record<string, boolean> = {};
  private readonly grants = new Map<string, Grant>();
  private readonly deadlines = new Map<string, number>();
  private readonly recoveries = new Map<string, number>();
  private readonly claimed = new Set<string>();
  private readonly listeners = new Set<(call: CallSession) => void>();
  private readonly timer: NodeJS.Timeout;
  constructor(private readonly storage: RegistryStorage, private readonly registry: CallRegistry,
    private readonly now: () => number = Date.now, private readonly approvalPending: (call: CallSession) => boolean = () => true) {
    const raw = storage.load();
    if (Array.isArray(raw) && raw.length === 0) { /* New protected store. */ }
    else {
      if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new CallError('invalid_call_store');
      const data = raw as { sessions: CallSession[]; enabled: Record<string, boolean> };
      if (!Array.isArray(data.sessions) || data.sessions.length > 200 || !data.enabled || Object.keys(data.enabled).length > 8 ||
          Object.values(data.enabled).some(v => typeof v !== 'boolean')) throw new CallError('invalid_call_store');
      for (const call of data.sessions) {
        if (!/^[a-f0-9-]{36}$/.test(call.callId) || !/^[a-f0-9-]{36}$/.test(call.deviceId) ||
            !/^[a-f0-9]{64}$/.test(call.eventId) || !CALL_STATES.includes(call.state) ||
            !Number.isFinite(Date.parse(call.createdAt)) || !Number.isFinite(Date.parse(call.expiresAt)) || typeof call.muted !== 'boolean') throw new CallError('invalid_call_store');
        // A process restart cannot resume a microphone session or reuse an authorization grant.
        if (!TERMINAL.has(call.state)) { call.state = 'failed'; call.reason = 'bridge_restarted'; call.endedAt = this.iso(); }
      }
      this.sessions = data.sessions; this.enabled = data.enabled; this.save();
    }
    this.timer = setInterval(() => { try { this.sweep(); } catch { for (const call of this.sessions) if (!TERMINAL.has(call.state)) this.finishMemory(call, 'failed', 'call_storage_unavailable'); } }, 1000);
    this.timer.unref();
  }
  private iso() { return new Date(this.now()).toISOString(); }
  private save() { this.storage.save({ sessions: this.sessions, enabled: this.enabled }); }
  private owner(deviceId: string, callId: string): CallSession {
    if (!this.registry.hasDevice(deviceId)) throw new CallError('revoked');
    const call = this.sessions.find(c => c.callId === callId && c.deviceId === deviceId);
    if (!call) throw new CallError('unknown_call');
    return call;
  }
  onChange(listener: (call: CallSession) => void) { this.listeners.add(listener); return () => this.listeners.delete(listener); }
  private notify(call: CallSession) { for (const listener of this.listeners) listener(structuredClone(call)); }
  private view(call: CallSession): CallView { const { deviceId: _device, approvalId: _approval, presentedAt: _presented, ...view } = call; return structuredClone(view); }
  preferences(deviceId: string): boolean { if (!this.registry.hasDevice(deviceId)) throw new CallError('revoked'); return this.enabled[deviceId] === true; }
  setPreferences(deviceId: string, enabled: boolean) {
    this.preferences(deviceId); this.enabled[deviceId] = enabled; this.save();
    if (!enabled) for (const call of this.sessions.filter(c => c.deviceId === deviceId && c.state === 'offered')) this.finish(call, 'declined', 'invitations_disabled');
  }
  offer(deviceId: string, event: NotificationEvent): CallView | null {
    this.sweep();
    if (!this.preferences(deviceId)) return null;
    const existing = this.sessions.find(c => c.deviceId === deviceId && c.eventId === event.eventId);
    if (existing) return this.view(existing);
    if (Date.parse(event.expiresAt) <= this.now() || this.now() - Date.parse(event.occurredAt) > 60000 ||
        this.sessions.some(c => c.deviceId === deviceId && !TERMINAL.has(c.state))) return null;
    const call: CallSession = { callId: randomUUID(), deviceId, eventId: event.eventId, threadId: event.threadId, turnId: event.turnId,
      // FCM may deliver after several minutes. The invitation must survive for
      // the same authenticated event TTL, then ring briefly when presented.
      kind: event.kind, mode: event.mode, createdAt: this.iso(), expiresAt: event.expiresAt,
      acceptedAt: null, endedAt: null, state: 'offered', muted: false, reason: 'task_event', ...(event.approvalId ? { approvalId: event.approvalId } : {}) };
    this.sessions.push(call); while (this.sessions.length > 200) this.sessions.splice(this.sessions.findIndex(c => TERMINAL.has(c.state)), 1);
    this.save(); this.notify(call); return this.view(call);
  }
  byEvent(deviceId: string, eventId: string): CallView | null {
    this.sweep(); this.preferences(deviceId);
    const call = this.sessions.find(c => c.deviceId === deviceId && c.eventId === eventId); if(call)this.present(call);return call ? this.view(call) : null;
  }
  current(deviceId: string): CallView | null {
    this.sweep(); this.preferences(deviceId);
    const call = this.sessions.find(c => c.deviceId === deviceId && !TERMINAL.has(c.state)); if(call)this.present(call);return call ? this.view(call) : null;
  }
  private present(call:CallSession) {
    if(call.state!=='offered'||call.presentedAt)return;
    call.presentedAt=this.iso();call.expiresAt=new Date(Math.min(Date.parse(call.expiresAt),this.now()+45000)).toISOString();
    this.save();this.notify(call);
  }
  /** Explicit local-PC review of an existing stored turn; never a fabricated new completion event. */
  review(deviceId: string, threadId: string, turnId: string, mode: 'mock' | 'real'): CallView {
    this.sweep(); if (!this.preferences(deviceId)) throw new CallError('invitations_disabled');
    if (this.sessions.some(c => c.deviceId === deviceId && !TERMINAL.has(c.state))) throw new CallError('call_busy');
    const call: CallSession = { callId: randomUUID(), deviceId, eventId: digest('review|' + randomUUID()), threadId, turnId, kind: 'review', mode,
      createdAt: this.iso(), expiresAt: new Date(this.now() + 120000).toISOString(), acceptedAt: null, endedAt: null, state: 'offered', muted: false, reason: 'manual_task_review' };
    this.sessions.push(call); while (this.sessions.length > 200) this.sessions.splice(this.sessions.findIndex(c => TERMINAL.has(c.state)), 1);
    this.save(); this.notify(call); return this.view(call);
  }
  status(deviceId: string, callId: string): CallView { this.sweep(); return this.view(this.owner(deviceId, callId)); }
  outgoing(deviceId:string,threadId:string,turnId:string,mode:'mock'|'real',requestId:string):CallView {
    this.sweep();if(!this.registry.hasDevice(deviceId))throw new CallError('revoked');
    const eventId=digest('outgoing|'+deviceId+'|'+requestId),previous=this.sessions.find(c=>c.deviceId===deviceId&&c.eventId===eventId);
    if(previous)return this.view(previous);
    if(this.sessions.some(c=>c.deviceId===deviceId&&!TERMINAL.has(c.state)))throw new CallError('call_busy');
    const call:CallSession={callId:randomUUID(),deviceId,eventId,threadId,turnId,kind:'conversation',mode,createdAt:this.iso(),expiresAt:new Date(this.now()+30000).toISOString(),acceptedAt:null,endedAt:null,state:'offered',muted:false,reason:'phone_initiated'};
    this.sessions.push(call);while(this.sessions.length>200)this.sessions.splice(this.sessions.findIndex(c=>TERMINAL.has(c.state)),1);this.save();this.notify(call);return this.view(call);
  }
  accept(deviceId: string, callId: string, identity: string, resume = false): { call: CallView; token: string; tokenExpiresAt: string } {
    this.sweep(); const call = this.owner(deviceId, callId);
    if (!this.registry.hasDevice(deviceId, identity)) throw new CallError('wrong_identity');
    const existing = [...this.grants.values()].find(g => g.callId === callId);
    if (call.state === 'connecting' && existing && existing.expires > this.now()) return { call: this.view(call), token: existing.token, tokenExpiresAt: new Date(existing.expires).toISOString() };
    if (call.state !== (resume ? 'reconnecting' : 'offered')) throw new CallError('call_not_available');
    if (resume && (this.recoveries.get(callId) ?? 0) >= 2) { this.finish(call, 'failed', 'recovery_exhausted'); throw new CallError('recovery_exhausted'); }
    if (resume) this.recoveries.set(callId, (this.recoveries.get(callId) ?? 0) + 1);
    call.state = 'connecting'; call.acceptedAt ??= this.iso(); call.reason = resume ? 'media_recovery' : 'answered';
    this.deadlines.set(callId, this.now() + 15000);
    const token = randomBytes(32).toString('base64url'); const expires = this.now() + 15000;
    this.grants.set(digest(token), { callId, deviceId, identity, token, expires }); this.save(); this.notify(call);
    return { call: this.view(call), token, tokenExpiresAt: new Date(expires).toISOString() };
  }
  consume(token: string, identity: string): CallSession {
    this.sweep(); const key = digest(token), grant = this.grants.get(key);
    if (!grant || grant.expires <= this.now() || grant.identity !== identity || !this.registry.hasDevice(grant.deviceId, identity)) throw new CallError('media_unauthorized');
    const call = this.owner(grant.deviceId, grant.callId);
    if (call.state !== 'connecting') throw new CallError('media_unauthorized');
    this.grants.delete(key); this.claimed.add(call.callId); return structuredClone(call);
  }
  activate(deviceId: string, callId: string) {
    this.sweep(); const call = this.owner(deviceId, callId); if (call.state !== 'connecting' || !this.claimed.delete(callId)) throw new CallError('invalid_transition');
    call.state = 'active'; call.reason = 'media_connected'; this.deadlines.delete(callId); this.save(); this.notify(call);
  }
  mute(deviceId: string, callId: string, muted: boolean) {
    const call = this.owner(deviceId, callId); if (call.state !== 'active') throw new CallError('call_not_active');
    call.muted = muted; this.save(); this.notify(call);
  }
  decline(deviceId: string, callId: string): CallView {
    this.sweep(); const call = this.owner(deviceId, callId); if (call.state === 'declined') return this.view(call);
    if (call.state !== 'offered') throw new CallError('invalid_transition'); this.finish(call, 'declined', 'user_declined'); return this.view(call);
  }
  end(deviceId: string, callId: string, reason = 'user_ended'): CallView {
    const call = this.owner(deviceId, callId); if (!TERMINAL.has(call.state)) this.finish(call, 'ended', reason); return this.view(call);
  }
  disconnect(deviceId: string, callId: string) {
    const call = this.sessions.find(c => c.deviceId === deviceId && c.callId === callId); if (!call || TERMINAL.has(call.state)) return;
    if (call.state === 'active') { call.state = 'reconnecting'; call.reason = 'media_disconnected'; this.deadlines.set(callId, this.now() + 15000); this.save(); this.notify(call); }
    else this.finish(call, 'failed', 'media_setup_failed');
  }
  fail(deviceId: string, callId: string, reason: string) { const call = this.sessions.find(c => c.deviceId === deviceId && c.callId === callId); if (call && !TERMINAL.has(call.state)) this.finish(call, 'failed', reason); }
  revoke(deviceId: string) { delete this.enabled[deviceId]; for (const c of this.sessions.filter(c => c.deviceId === deviceId && !TERMINAL.has(c.state))) this.finish(c, 'failed', 'revoked'); this.save(); }
  private finishMemory(call: CallSession, state: CallState, reason: string) {
    call.state = state; call.reason = reason; call.endedAt = this.iso(); this.deadlines.delete(call.callId);
    this.claimed.delete(call.callId);
    for (const [key, grant] of this.grants) if (grant.callId === call.callId) this.grants.delete(key);
    this.notify(call);
  }
  private finish(call: CallSession, state: CallState, reason: string) { this.finishMemory(call, state, reason); this.save(); }
  sweep() {
    for (const [key, grant] of this.grants) if (grant.expires <= this.now()) this.grants.delete(key);
    for (const call of this.sessions) if (!TERMINAL.has(call.state)) {
      if (!this.registry.hasDevice(call.deviceId)) this.finish(call, 'failed', 'revoked');
      else if (call.state === 'offered' && (Date.parse(call.expiresAt) <= this.now() || (call.kind === 'approval' && !this.approvalPending(call)))) this.finish(call, 'expired', 'offer_expired');
      else if (this.deadlines.has(call.callId) && this.deadlines.get(call.callId)! <= this.now()) this.finish(call, 'failed', 'connection_timeout');
      else if (call.acceptedAt && this.now() - Date.parse(call.acceptedAt) >= 1800000) this.finish(call, 'ended', 'duration_limit');
    }
  }
  diagnostics() { return this.sessions.slice(-10).map(call => this.view(call)); }
  close() { clearInterval(this.timer); for (const call of this.sessions) if (!TERMINAL.has(call.state)) this.finish(call, 'failed', 'bridge_stopped'); this.grants.clear(); this.listeners.clear(); }
}
