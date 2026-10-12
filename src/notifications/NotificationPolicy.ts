import { createHash, createHmac } from 'node:crypto';
import type { ApprovalRecord, BridgeEvent, Mode } from '../types/index.js';

export type NotificationKind = 'completed' | 'failed' | 'interrupted' | 'approval';
export interface NotificationPreferences { enabled: boolean; completed: boolean; failed: boolean; interrupted: boolean; approval: boolean }
export const DEFAULT_PREFERENCES: NotificationPreferences = { enabled: false, completed: true, failed: true, interrupted: false, approval: true };
export interface NotificationEvent { eventId: string; threadId: string; turnId: string; kind: NotificationKind; occurredAt: string; expiresAt: string; mode: Mode; approvalId?: string }
export interface PushPayload { version: string; eventId: string; pairingId: string; kind: string; occurredAt: string; expiresAt: string; mode: string; signature: string }
export function preferences(value: unknown): NotificationPreferences {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('invalid_preferences');
  const row = value as Record<string, unknown>;
  if (Object.keys(row).length !== 5 || Object.keys(DEFAULT_PREFERENCES).some(k => typeof row[k] !== 'boolean')) throw new Error('invalid_preferences');
  return row as unknown as NotificationPreferences;
}
export function validFid(value: unknown): value is string { return typeof value === 'string' && /^[cdef][A-Za-z0-9_-]{21}$/.test(value); }
export function eligible(event: BridgeEvent, mode: Mode, now: number, approvals: ApprovalRecord[] = []): NotificationEvent | null {
  const kinds: Partial<Record<BridgeEvent['type'], NotificationKind>> = { 'turn.completed': 'completed', 'turn.failed': 'failed', 'turn.interrupted': 'interrupted', 'approval.requested': 'approval' };
  const kind = kinds[event.type], time = Date.parse(event.timestamp);
  if (!kind || !event.threadId || !event.turnId || event.metadata?.reconciled === true || !Number.isFinite(time) || time > now + 5000 || now - time > 60000) return null;
  const approval = kind === 'approval' ? approvals.find(a => a.id === event.metadata?.approvalId && a.status === 'pending' && a.threadId === event.threadId && a.turnId === event.turnId) : undefined;
  if (kind === 'approval' && !approval) return null;
  const expires = kind === 'approval' ? Math.min(time + 60000, Date.parse(approval!.expiresAt)) : time + (kind === 'completed' ? 1800000 : 900000);
  if (expires <= now) return null;
  const eventId = createHash('sha256').update(JSON.stringify([mode, event.threadId, event.turnId, kind, approval?.id ?? ''])).digest('hex');
  return { eventId, threadId: event.threadId, turnId: event.turnId, kind, occurredAt: event.timestamp, expiresAt: new Date(expires).toISOString(), mode, ...(approval ? { approvalId: approval.id } : {}) };
}
export function canonical(payload: Omit<PushPayload, 'signature'>): string {
  return [payload.version, payload.eventId, payload.pairingId, payload.kind, payload.occurredAt, payload.expiresAt, payload.mode].join('\n');
}
export function pushPayload(event: NotificationEvent, pairingId: string, pairingDigest: string): PushPayload {
  const minimal = { version: '1', eventId: event.eventId, pairingId, kind: event.kind, occurredAt: event.occurredAt, expiresAt: event.expiresAt, mode: event.mode };
  return { ...minimal, signature: createHmac('sha256', Buffer.from(pairingDigest, 'hex')).update(canonical(minimal)).digest('base64url') };
}
