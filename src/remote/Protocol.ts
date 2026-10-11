import { randomUUID } from 'node:crypto';
import { object } from '../utils/safe.js';

export const VERSION = 1;
export const CLIENT_LIMIT = 8192;
export const SERVER_LIMIT = 262144;
export const REQUESTS = ['state.snapshot.request', 'thread.list.request', 'task.history.request', 'connection.status.request', 'heartbeat', 'disconnect'] as const;
export type ReadRequest = typeof REQUESTS[number];
export type ClientMessage = Envelope<'client.hello', { name: string }> |
  Envelope<'pair.request', { code: string }> | Envelope<'auth.request', { deviceId: string; token: string }> |
  Envelope<ReadRequest, Record<string, never>>;
export interface Envelope<T extends string = string, P = unknown> {
  version: 1; type: T; id: string; timestamp: string; payload: P; correlationId?: string;
}
export interface RemoteThread { id: string; label: string; project: string; status: string }
export interface RemoteTurn { id: string; threadId: string; status: string; startedAt: string; finishedAt: string | null }
export interface RemoteEvent { id: string; type: string; timestamp: string; summary: string; threadId: string | null; turnId: string | null }
export interface Snapshot {
  epoch: string; sequence: number; bridgeName: string; available: boolean; mode: 'mock' | 'real';
  selectedThreadId: string | null; threads: RemoteThread[]; turns: RemoteTurn[]; history: RemoteEvent[];
  historyComplete: boolean; syncedAt: string;
}
export class ProtocolError extends Error {
  constructor(readonly code: string) { super(code); }
}
export function textField(value: unknown, max: number, min = 1): string {
  if (typeof value !== 'string' || value.length < min || value.length > max || /[\u0000-\u001f]/.test(value)) throw new ProtocolError('invalid_message');
  return value;
}
function keys(row: Record<string, unknown>, allowed: string[]): void {
  if (Object.keys(row).some(k => !allowed.includes(k))) throw new ProtocolError('invalid_message');
}
export function parseClient(raw: string): ClientMessage {
  if (Buffer.byteLength(raw) > CLIENT_LIMIT) throw new ProtocolError('message_too_large');
  try {
    const row = object(JSON.parse(raw));
    if (row.version !== VERSION) throw new ProtocolError('incompatible_version');
    keys(row, ['version', 'type', 'id', 'timestamp', 'payload', 'correlationId']);
    textField(row.id, 128); textField(row.type, 64); textField(row.timestamp, 40);
    if (!Number.isFinite(Date.parse(String(row.timestamp)))) throw new ProtocolError('invalid_message');
    if (row.correlationId !== undefined) textField(row.correlationId, 128);
    const p = object(row.payload);
    switch (row.type) {
      case 'client.hello': keys(p, ['name']); textField(p.name, 80); break;
      case 'pair.request': keys(p, ['code']); if (!/^[A-F0-9]{16}$/.test(textField(p.code, 16))) throw new ProtocolError('invalid_message'); break;
      case 'auth.request': keys(p, ['deviceId', 'token']); if (!/^[a-f0-9-]{36}$/.test(textField(p.deviceId, 36)) || !/^[A-Za-z0-9_-]{43}$/.test(textField(p.token, 43))) throw new ProtocolError('invalid_message'); break;
      default: if (!(REQUESTS as readonly unknown[]).includes(row.type)) throw new ProtocolError('unknown_message'); keys(p, []);
    }
    return row as unknown as ClientMessage;
  } catch (error) { if (error instanceof ProtocolError) throw error; throw new ProtocolError('invalid_message'); }
}
export function envelope<T extends string, P>(type: T, payload: P, correlationId?: string): Envelope<T, P> {
  return { version: VERSION, type, id: randomUUID(), timestamp: new Date().toISOString(), payload, ...(correlationId ? { correlationId } : {}) };
}
export function encode(type: string, payload: unknown, correlationId?: string): string {
  const result = JSON.stringify(envelope(type, payload, correlationId));
  if (Buffer.byteLength(result) > SERVER_LIMIT) throw new ProtocolError('snapshot_too_large');
  return result;
}
