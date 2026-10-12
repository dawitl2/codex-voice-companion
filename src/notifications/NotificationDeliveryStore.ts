import type { RegistryStorage } from '../remote/DeviceRegistry.js';
import type { NotificationEvent } from './NotificationPolicy.js';
export type Receipt = 'received' | 'posted' | 'opened' | 'dismissed';
export interface Delivery extends NotificationEvent { deviceId: string; attempts: number; status: 'created' | 'sending' | 'accepted' | 'failed' | 'suppressed' | 'uncertain'; code: string; receipts: Partial<Record<Receipt, string>> }
export class NotificationDeliveryStore {
  private records: Delivery[];
  constructor(private readonly storage: RegistryStorage) {
    const data = storage.load();
    if (!Array.isArray(data) || data.length > 1000 || data.some(r => !r || typeof r !== 'object' || !/^[a-f0-9]{64}$/.test(r.eventId) || !/^[a-f0-9-]{36}$/.test(r.deviceId) || !['completed','failed','interrupted','approval'].includes(r.kind) || !['mock','real'].includes(r.mode) || typeof r.threadId !== 'string' || typeof r.turnId !== 'string' || !Number.isFinite(Date.parse(r.expiresAt)) || !Number.isFinite(Date.parse(r.occurredAt)) || !Number.isInteger(r.attempts) || r.attempts < 0 || r.attempts > 3 || !['created','sending','accepted','failed','suppressed','uncertain'].includes(r.status) || typeof r.code !== 'string' || !r.receipts || typeof r.receipts !== 'object')) throw new Error('Invalid protected delivery store');
    this.records = data as Delivery[];
    // An interrupted send is ambiguous. Never replay it just because the PC restarted.
    for (const r of this.records) if (r.status === 'sending' || r.status === 'created') { r.status = 'uncertain'; r.code = 'bridge_restarted'; }
    this.save();
  }
  find(deviceId: string, eventId: string): Delivery | undefined { return this.records.find(r => r.deviceId === deviceId && r.eventId === eventId); }
  create(deviceId: string, event: NotificationEvent): Delivery | null {
    if (this.find(deviceId, event.eventId)) return null;
    // Fail closed at the bound, rather than dropping keys and re-alerting old turns.
    if (this.records.length >= 1000) return null;
    const row: Delivery = { ...event, deviceId, attempts: 0, status: 'created', code: 'event_created', receipts: {} };
    this.records.push(row);
    try { this.save(); } catch (error) { this.records.pop(); throw error; }
    return row;
  }
  save(): void { this.storage.save(this.records); }
  receipt(deviceId: string, eventId: string, stage: Receipt, now: number): void {
    const row = this.find(deviceId, eventId);
    if (!row) throw new Error('unknown_event');
    row.receipts[stage] ??= new Date(now).toISOString(); this.save();
  }
  diagnostics(deviceId: string): { eventId: string; status: string; attempts: number; code: string; receipts: Partial<Record<Receipt, string>> }[] {
    return this.records.filter(r => r.deviceId === deviceId).slice(-20).map(({ eventId, status, attempts, code, receipts }) => ({ eventId, status, attempts, code, receipts: { ...receipts } }));
  }
}
