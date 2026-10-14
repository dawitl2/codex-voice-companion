import { randomUUID } from 'node:crypto';
import { object } from '../utils/safe.js';
import { preferences, validFid, type NotificationPreferences } from '../notifications/NotificationPolicy.js';
import { validateCallRequest, validateCallResponse, type CallRequest } from '../calls/CallProtocol.js';
import { validateSpeechRequest, validateSpeechResponse, type SpeechRequest } from '../speech/SpeechProtocol.js';
import { validateConversationRequest, validateConversationResponse, type ConversationRequest } from '../conversation/ConversationProtocol.js';
import {validateControlRequest,validateControlResponse,type ControlRequest} from '../control/ControlProtocol.js';
import {validateChatRequest,validateChatResponse} from '../conversation/ChatProtocol.js';

export const VERSION = 2;
export const CLIENT_LIMIT = 8192;
export const SERVER_LIMIT = 262144;
export const REQUESTS = ['state.snapshot.request', 'thread.list.request', 'thread.latest.request', 'task.history.request', 'connection.status.request', 'heartbeat', 'disconnect'] as const;
export type ReadRequest = typeof REQUESTS[number];
export type ClientMessage = Envelope<'client.hello', { name: string }> |
  Envelope<'pair.request', { code: string }> | Envelope<'auth.request', { deviceId: string; token: string }> |
  Envelope<'notification.register', { fid: string; projectId: string; preferences: NotificationPreferences }> |
  Envelope<'notification.preferences.update', { preferences: NotificationPreferences }> |
  Envelope<'notification.status.request', Record<string, never>> |
  Envelope<'notification.event.request', { eventId: string }> |
  Envelope<'notification.receipt', { eventId: string; stage: 'received' | 'posted' | 'opened' | 'dismissed' }> |
  Envelope<CallRequest, Record<string, unknown>> |
  Envelope<SpeechRequest, Record<string, unknown>> |
  Envelope<ConversationRequest, Record<string, unknown>> |
  Envelope<ControlRequest, Record<string, unknown>> |
  Envelope<'chat.status.request' | 'chat.select' | 'chat.send', Record<string, unknown>> |
  Envelope<ReadRequest, Record<string, never>>;
export interface Envelope<T extends string = string, P = unknown> {
  version: 2; type: T; id: string; timestamp: string; payload: P; correlationId?: string;
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
function exact(row: Record<string, unknown>, allowed: string[]): void {
  keys(row, allowed);
  if (allowed.some(k => !Object.hasOwn(row, k))) throw new ProtocolError('invalid_message');
}
function date(value: unknown): void {
  const text = textField(value, 40);
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(text) || !Number.isFinite(Date.parse(text))) throw new ProtocolError('invalid_message');
}
function choice(value: unknown, choices: string[]): void {
  if (typeof value !== 'string' || !choices.includes(value)) throw new ProtocolError('invalid_message');
}
function optionalId(value: unknown): void { if (value !== null) textField(value, 128); }
function rows(value: unknown, max: number): Record<string, unknown>[] {
  if (!Array.isArray(value) || value.length > max) throw new ProtocolError('invalid_message');
  return value.map(object);
}
function unique(values: string[]): void { if (new Set(values).size !== values.length) throw new ProtocolError('invalid_message'); }
function validateSnapshot(p: Record<string, unknown>): void {
  exact(p, ['epoch','sequence','bridgeName','available','mode','selectedThreadId','threads','turns','history','historyComplete','syncedAt']);
  textField(p.epoch, 128); textField(p.bridgeName, 80); date(p.syncedAt);
  if (!Number.isSafeInteger(p.sequence) || Number(p.sequence) < 0 || typeof p.available !== 'boolean' || typeof p.historyComplete !== 'boolean') throw new ProtocolError('invalid_message');
  choice(p.mode, ['mock','real']); optionalId(p.selectedThreadId);
  const threads = rows(p.threads, 50), turns = rows(p.turns, 100), history = rows(p.history, 100);
  for (const r of threads) {
    exact(r, ['id','label','project','status']); textField(r.id, 128); textField(r.label, 80); textField(r.project, 80); choice(r.status, ['ready','unavailable']);
  }
  const threadIds = threads.map(r => String(r.id)); unique(threadIds);
  if (p.selectedThreadId !== null && !threadIds.includes(String(p.selectedThreadId))) throw new ProtocolError('invalid_message');
  for (const r of turns) {
    exact(r, ['id','threadId','status','startedAt','finishedAt']); textField(r.id, 128); textField(r.threadId, 128);
    choice(r.status, ['starting','running','awaitingApproval','completed','failed','interrupted','unknown']); date(r.startedAt); if (r.finishedAt !== null) date(r.finishedAt);
    if (!threadIds.includes(String(r.threadId))) throw new ProtocolError('invalid_message');
  }
  unique(turns.map(r => `${r.threadId}:${r.id}`));
  for (const r of history) {
    exact(r, ['id','type','timestamp','summary','threadId','turnId']); textField(r.id, 128); date(r.timestamp); textField(r.summary, 240); optionalId(r.threadId); optionalId(r.turnId);
    choice(r.type, ['bridge.connected','bridge.disconnected','thread.created','thread.resumed','turn.started','turn.progress','turn.completed','turn.failed','turn.interrupted','approval.requested','approval.resolved','codex.error']);
  }
  unique(history.map(r => String(r.id)));
}
function validateServer(type: string, payload: unknown): void {
  const p = object(payload);
  if(type.startsWith('control.')){validateControlResponse(type,p);return;}
  if(type.startsWith('chat.')){validateChatResponse(type,p);return;}
  if (type.startsWith('call.')) { validateCallResponse(type, p); return; }
  if (type.startsWith('speech.')) { validateSpeechResponse(type, p); return; }
  if (type.startsWith('conversation.')) { validateConversationResponse(type, p); return; }
  if (['state.snapshot','thread.list.response','task.history.response','connection.status','turn.progress','turn.completed','turn.failed','turn.interrupted','approval.pending'].includes(type)) { validateSnapshot(p); return; }
  switch (type) {
    case 'server.hello': exact(p, ['bridgeName']); textField(p.bridgeName, 80); break;
    case 'pair.success': case 'auth.success':
      exact(p, type === 'pair.success' ? ['deviceId','token','bridgeName'] : ['deviceId','bridgeName']);
      if (!/^[a-f0-9-]{36}$/.test(textField(p.deviceId, 36))) throw new ProtocolError('invalid_message');
      if (type === 'pair.success' && !/^[A-Za-z0-9_-]{43}$/.test(textField(p.token, 43))) throw new ProtocolError('invalid_message');
      textField(p.bridgeName, 80); break;
    case 'auth.failed': case 'pair.failed': case 'protocol.error': case 'request.error': exact(p, ['code']); textField(p.code, 64); break;
    case 'heartbeat': exact(p, []); break;
    case 'notification.status.response':
      exact(p, ['registered','configured','preferences','diagnostic']);
      if (typeof p.registered !== 'boolean' || typeof p.configured !== 'boolean') throw new ProtocolError('invalid_message');
      preferences(p.preferences); textField(p.diagnostic, 64); break;
    case 'notification.event.response':
      exact(p, ['eventId','threadId','turnId','kind','mode','occurredAt','expiresAt','approvalStatus','expired','result']);
      if (!/^[a-f0-9]{64}$/.test(textField(p.eventId, 64)) || typeof p.expired !== 'boolean') throw new ProtocolError('invalid_message');
      textField(p.threadId, 128); textField(p.turnId, 128); date(p.occurredAt); date(p.expiresAt); choice(p.mode, ['mock','real']); choice(p.kind, ['completed','failed','interrupted','approval']);
      if (p.result !== null && (typeof p.result !== 'string' || p.result.length > 16000)) throw new ProtocolError('invalid_message');
      if (p.approvalStatus !== null) choice(p.approvalStatus, ['pending','accepted','declined','expired','unavailable','resolved']); break;
    case 'notification.receipt.response':
      exact(p, ['eventId','stage']); if (!/^[a-f0-9]{64}$/.test(textField(p.eventId, 64))) throw new ProtocolError('invalid_message'); choice(p.stage, ['received','posted','opened','dismissed']); break;
    case 'notification.error': exact(p, ['code']); textField(p.code, 64); break;
    default: throw new ProtocolError('unknown_message');
  }
}
export function parseClient(raw: string): ClientMessage {
  if (Buffer.byteLength(raw) > CLIENT_LIMIT) throw new ProtocolError('message_too_large');
  try {
    const row = object(JSON.parse(raw));
    if (row.version !== VERSION) throw new ProtocolError('incompatible_version');
    keys(row, ['version', 'type', 'id', 'timestamp', 'payload', 'correlationId']);
    textField(row.id, 128); textField(row.type, 64); date(row.timestamp);
    if (row.correlationId !== undefined) textField(row.correlationId, 128);
    const p = object(row.payload);
    if(String(row.type).startsWith('control.')) validateControlRequest(String(row.type),p);
    else if (String(row.type).startsWith('call.')) validateCallRequest(String(row.type), p);
    else if (String(row.type).startsWith('speech.')) validateSpeechRequest(String(row.type), p);
    else if (String(row.type).startsWith('conversation.')) validateConversationRequest(String(row.type), p);
    else if (String(row.type).startsWith('chat.')) validateChatRequest(String(row.type), p);
    else switch (row.type) {
      case 'client.hello': keys(p, ['name']); textField(p.name, 80); break;
      case 'pair.request': keys(p, ['code']); if (!/^[A-F0-9]{16}$/.test(textField(p.code, 16))) throw new ProtocolError('invalid_message'); break;
      case 'auth.request': keys(p, ['deviceId', 'token']); if (!/^[a-f0-9-]{36}$/.test(textField(p.deviceId, 36)) || !/^[A-Za-z0-9_-]{43}$/.test(textField(p.token, 43))) throw new ProtocolError('invalid_message'); break;
      case 'notification.register':
        exact(p, ['fid','projectId','preferences']); if (!validFid(p.fid) || !/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(textField(p.projectId, 30))) throw new ProtocolError('invalid_message'); preferences(p.preferences); break;
      case 'notification.preferences.update': exact(p, ['preferences']); preferences(p.preferences); break;
      case 'notification.status.request': exact(p, []); break;
      case 'notification.event.request': case 'notification.receipt':
        exact(p, row.type === 'notification.receipt' ? ['eventId','stage'] : ['eventId']); if (!/^[a-f0-9]{64}$/.test(textField(p.eventId, 64))) throw new ProtocolError('invalid_message');
        if (row.type === 'notification.receipt') choice(p.stage, ['received','posted','opened','dismissed']); break;
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
  try { textField(type, 64); if (correlationId !== undefined) textField(correlationId, 128); validateServer(type, payload); }
  catch (error) { if (error instanceof ProtocolError) throw error; throw new ProtocolError('invalid_message'); }
  return result;
}
