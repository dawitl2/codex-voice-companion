import type { Bridge } from '../core/Bridge.js';
import type { DeviceRegistry } from '../remote/DeviceRegistry.js';
import type { BridgeEvent } from '../types/index.js';
import { eligible, pushPayload, type NotificationEvent, type PushPayload } from './NotificationPolicy.js';
import { NotificationDeliveryStore, type Delivery, type Receipt } from './NotificationDeliveryStore.js';
import { sanitize } from '../utils/safe.js';

export interface PushProvider { send(fid: string, payload: PushPayload, ttlMs: number): Promise<void>; close?(): Promise<void> }
export class PushFailure extends Error { constructor(readonly code: 'unregistered' | 'temporary' | 'configuration' | 'invalid_payload') { super(code); } }
export class NotificationDispatcher {
  private readonly unsubscribe: () => void;
  private readonly pending = new Set<Promise<void>>();
  private readonly rate = new Map<string, number[]>();
  private closed = false;
  diagnostic = 'ready';
  constructor(private readonly bridge: Bridge, private readonly registry: DeviceRegistry, readonly store: NotificationDeliveryStore,
    private readonly provider: PushProvider | undefined, private readonly now: () => number = Date.now,
    private readonly delay: (ms: number) => Promise<void> = ms => new Promise(resolve => setTimeout(resolve, ms))) {
    if (!provider) this.diagnostic = 'provider_disabled';
    this.unsubscribe = bridge.events.subscribe(event => this.accept(event));
  }
  get configured(): boolean { return !!this.provider; }
  private accept(raw: BridgeEvent): void {
    if (this.closed || !this.provider) return;
    const event = eligible(raw, this.bridge.mode, this.now(), this.bridge.store.state.approvals);
    if (!event) return;
    try {
      for (const target of this.registry.pushTargets()) {
        if (!target.preferences.enabled || !target.preferences[event.kind] || Date.parse(event.occurredAt) < Date.parse(target.registeredAt)) continue;
        const row = this.store.create(target.id, event); if (!row) { if (!this.store.find(target.id, event.eventId)) this.diagnostic = 'dedup_capacity_reached'; continue; }
        const recent = (this.rate.get(target.id) ?? []).filter(t => t > this.now() - 60000);
        if (recent.length >= 6 || this.pending.size >= 32) { row.status = 'suppressed'; row.code = 'rate_limited'; this.store.save(); continue; }
        recent.push(this.now()); this.rate.set(target.id, recent);
        const job = this.deliver(row).catch(() => { this.diagnostic = 'delivery_storage_unavailable'; });
        this.pending.add(job); void job.finally(() => this.pending.delete(job));
      }
    } catch { this.diagnostic = 'delivery_storage_unavailable'; }
  }
  private active(row: Delivery): boolean {
    return !this.closed && Date.parse(row.expiresAt) > this.now() && (row.kind !== 'approval' || this.bridge.store.state.approvals.some(a => a.id === row.approvalId && a.status === 'pending' && Date.parse(a.expiresAt) > this.now()));
  }
  private async deliver(row: Delivery): Promise<void> {
    for (let attempt = 0; attempt < 3; attempt++) {
      const target = this.registry.pushTargets().find(t => t.id === row.deviceId);
      if (!target || !target.preferences.enabled || !target.preferences[row.kind] || !this.active(row)) { row.status = 'suppressed'; row.code = 'disabled_revoked_or_expired'; this.store.save(); return; }
      row.attempts++; row.status = 'sending'; row.code = 'send_attempt'; this.store.save();
      try {
        await this.provider!.send(target.fid, pushPayload(row, target.id, target.digest), Date.parse(row.expiresAt) - this.now());
        row.status = 'accepted'; row.code = 'fcm_accepted'; this.store.save(); return;
      } catch (error) {
        const code = error instanceof PushFailure ? error.code : 'temporary';
        row.status = 'failed'; row.code = code; this.store.save();
        if (code === 'unregistered') this.registry.removePush(target.id, target.fid);
        if (code !== 'temporary' || attempt === 2) return;
        await this.delay(1000 * 2 ** attempt + Math.floor(Math.random() * 250));
      }
    }
  }
  event(deviceId: string, eventId: string): NotificationEvent & { approvalStatus: string | null; expired: boolean; result: string | null } {
    const row = this.store.find(deviceId, eventId); if (!row) throw new Error('unknown_event');
    const approval = row.approvalId ? this.bridge.store.state.approvals.find(a => a.id === row.approvalId) : undefined;
    return { eventId: row.eventId, threadId: row.threadId, turnId: row.turnId, kind: row.kind, mode: row.mode, occurredAt: row.occurredAt, expiresAt: row.expiresAt,
      approvalStatus: approval ? (Date.parse(approval.expiresAt) <= this.now() && approval.status === 'pending' ? 'expired' : approval.status) : null, expired: Date.parse(row.expiresAt) <= this.now(),
      result: this.bridge.available ? sanitize(this.bridge.store.state.turns.find(t => t.id === row.turnId && t.threadId === row.threadId)?.finalResponse ?? '', 16000) || null : null };
  }
  receipt(deviceId: string, eventId: string, stage: Receipt): void { this.store.receipt(deviceId, eventId, stage, this.now()); }
  async drain(): Promise<void> { await Promise.all([...this.pending]); }
  async close(): Promise<void> { this.closed = true; this.unsubscribe(); await this.drain(); await this.provider?.close?.(); }
}
