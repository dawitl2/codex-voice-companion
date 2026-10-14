const object = (value: unknown): Record<string, unknown> => { if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('invalid_speech_message'); return value as Record<string, unknown>; };
function exact(p: Record<string, unknown>, fields: string[]) { if (Object.keys(p).length !== fields.length || fields.some(k => !Object.hasOwn(p, k))) throw new Error('invalid_speech_message'); }
const id = (value: unknown) => typeof value === 'string' && /^[a-f0-9-]{36}$/.test(value);
const integer = (value: unknown, max = Number.MAX_SAFE_INTEGER) => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 && value <= max;
const text = (value: unknown, max: number, min = 1) => typeof value === 'string' && value.length >= min && value.length <= max && !/[\u0000-\u001f\u007f]/.test(value);
const date = (value: unknown) => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value) && Number.isFinite(Date.parse(value));
export type SpeechRequest = 'speech.status.request' | 'speech.listening' | 'speech.finish' | 'speech.summary.request' | 'speech.playback';
export function validateSpeechRequest(type: string, p: Record<string, unknown>) {
  if (!['speech.status.request','speech.listening','speech.finish','speech.summary.request','speech.playback'].includes(type)) throw new Error('unknown_speech_message');
  exact(p, type === 'speech.status.request' ? ['callId','afterSequence'] : type === 'speech.listening' ? ['callId','enabled'] : type === 'speech.playback' ? ['callId','state'] : ['callId']);
  if (!id(p.callId) || (type === 'speech.status.request' && !integer(p.afterSequence)) || (type === 'speech.listening' && typeof p.enabled !== 'boolean') || (type === 'speech.playback' && !['started','completed','interrupted','error'].includes(String(p.state)))) throw new Error('invalid_speech_message');
}
export function validateSpeechResponse(type: string, p: Record<string, unknown>) {
  if (type === 'speech.error') { exact(p, ['code']); if (!text(p.code, 64) || !/^[a-z_]+$/.test(String(p.code))) throw new Error('invalid_speech_message'); return; }
  if (type === 'speech.summary.response') { exact(p, ['callId','text','mode','taskState']); if (!id(p.callId) || !text(p.text, 1000) || !['mock','real'].includes(String(p.mode)) || !['starting','running','awaitingApproval','completed','failed','interrupted','unknown','unavailable'].includes(String(p.taskState))) throw new Error('invalid_speech_message'); return; }
  if (type !== 'speech.status.response') throw new Error('unknown_speech_message');
  exact(p, ['callId','state','engine','language','error','utterances',...(Object.hasOwn(p,'provisional')?['provisional']:[])]);
  if(p.provisional!==undefined&&p.provisional!==null){const u=object(p.provisional);exact(u,['utteranceId','text','revision','processingMs','final']);if(!id(u.utteranceId)||!text(u.text,2000)||!integer(u.revision)||!integer(u.processingMs,60000)||u.final!==false)throw Error('invalid_speech_message');}
  if (!id(p.callId) || !['listening','speech_detected','processing','transcript_ready','speaking','paused','error'].includes(String(p.state)) || !['loading','ready','unavailable'].includes(String(p.engine)) || p.language !== 'en' || !text(p.error, 64, 0) || !/^[a-z_]{0,64}$/.test(String(p.error)) || !Array.isArray(p.utterances) || p.utterances.length > 8) throw new Error('invalid_speech_message');
  let last = 0;
  for (const value of p.utterances) { const u = object(value); exact(u, ['utteranceId','sequence','startedAt','endedAt','text','language','final','processingMs']); if (!id(u.utteranceId) || !integer(u.sequence) || Number(u.sequence) <= last || !text(u.text, 2000) || u.language !== 'en' || u.final !== true || !integer(u.processingMs,60000) || !date(u.startedAt) || !date(u.endedAt) || Date.parse(String(u.endedAt)) < Date.parse(String(u.startedAt))) throw new Error('invalid_speech_message'); last = Number(u.sequence); }
}
