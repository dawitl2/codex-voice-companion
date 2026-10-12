import { CALL_STATES } from './CallSessionManager.js';
import { PCM } from './AudioProtocol.js';

const uuid = (value: unknown) => typeof value === 'string' && /^[a-f0-9-]{36}$/.test(value);
const event = (value: unknown) => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
export type CallRequest = 'call.preferences.request' | 'call.preferences.update' | 'call.current.request' | 'call.offer.request' | 'call.accept' | 'call.resume' | 'call.decline' | 'call.end' | 'call.mute' | 'call.status.request' | 'call.test-tone';
function exact(p: Record<string, unknown>, fields: string[]) { if (Object.keys(p).length !== fields.length || fields.some(k => !Object.hasOwn(p, k))) throw new Error('invalid_call_message'); }
export function validateCallRequest(type: string, p: Record<string, unknown>) {
  if (type === 'call.preferences.request' || type === 'call.current.request') { exact(p, []); return; }
  if (type === 'call.preferences.update') { exact(p, ['enabled']); if (typeof p.enabled !== 'boolean') throw new Error('invalid_call_message'); return; }
  if (type === 'call.offer.request') { exact(p, ['eventId']); if (!event(p.eventId)) throw new Error('invalid_call_message'); return; }
  if (!['call.accept','call.resume','call.decline','call.end','call.mute','call.status.request','call.test-tone'].includes(type)) throw new Error('unknown_call_message');
  exact(p, type === 'call.mute' ? ['callId','muted'] : ['callId']);
  if (!uuid(p.callId) || (type === 'call.mute' && typeof p.muted !== 'boolean')) throw new Error('invalid_call_message');
}
export function validateCallView(value: unknown) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('invalid_call_message');
  const p = value as Record<string, unknown>;
  exact(p, ['callId','eventId','threadId','turnId','kind','mode','createdAt','expiresAt','acceptedAt','endedAt','state','muted','reason']);
  if (!uuid(p.callId) || !event(p.eventId) || !CALL_STATES.includes(p.state as never) || typeof p.muted !== 'boolean' ||
      !['completed','failed','interrupted','approval','review'].includes(String(p.kind)) || !['mock','real'].includes(String(p.mode))) throw new Error('invalid_call_message');
  for (const key of ['threadId','turnId','reason']) if (typeof p[key] !== 'string' || !p[key] || String(p[key]).length > 128 || /[\x00-\x1f]/.test(String(p[key]))) throw new Error('invalid_call_message');
  for (const key of ['createdAt','expiresAt','acceptedAt','endedAt']) if (!(p[key] === null && ['acceptedAt','endedAt'].includes(key)) &&
      (typeof p[key] !== 'string' || !/^\d{4}-\d{2}-\d{2}T[\d:.]+Z$/.test(String(p[key])) || !Number.isFinite(Date.parse(String(p[key]))))) throw new Error('invalid_call_message');
}
export function validateCallResponse(type: string, p: Record<string, unknown>) {
  switch (type) {
    case 'call.preferences.response': exact(p, ['enabled']); if (typeof p.enabled !== 'boolean') throw new Error('invalid_call_message'); return;
    case 'call.status.response': exact(p, ['enabled','call']); if (typeof p.enabled !== 'boolean') throw new Error('invalid_call_message'); if (p.call !== null) validateCallView(p.call); return;
    case 'call.media.response': {
      exact(p, ['call','token','tokenExpiresAt','mediaUrl','format']); validateCallView(p.call);
      if (typeof p.token !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(p.token) || !Number.isFinite(Date.parse(String(p.tokenExpiresAt)))) throw new Error('invalid_call_message');
      const url = new URL(String(p.mediaUrl));
      if (url.protocol !== 'wss:' || !/^[a-z0-9-]+\.[a-z0-9.-]+\.ts\.net$/.test(url.hostname) || url.pathname !== '/media' || url.search || url.hash || url.username || url.password || (url.port && url.port !== '443')) throw new Error('invalid_call_message');
      if (!p.format || typeof p.format !== 'object' || Array.isArray(p.format)) throw new Error('invalid_audio_format');
      const format = p.format as Record<string, unknown>; exact(format, Object.keys(PCM));
      if (Object.entries(PCM).some(([key, value]) => format[key] !== value)) throw new Error('invalid_audio_format'); return;
    }
    case 'call.error': exact(p, ['code']); if (typeof p.code !== 'string' || !/^[a-z_]{1,64}$/.test(p.code)) throw new Error('invalid_call_message'); return;
    default: throw new Error('unknown_call_message');
  }
}
