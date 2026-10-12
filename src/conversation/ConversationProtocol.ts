export type ConversationRequest='conversation.status.request'|'conversation.confirm'|'conversation.cancel'|'conversation.correct'|'conversation.select';
const uuid=(v:unknown)=>typeof v==='string' && /^[a-f0-9-]{36}$/.test(v);
const id=(v:unknown)=>typeof v==='string' && /^[A-Za-z0-9_-]{1,128}$/.test(v) && !['__proto__','constructor','prototype'].includes(v);
const text=(v:unknown,max:number,min=1)=>typeof v==='string' && v.length>=min && v.length<=max && !/[\u0000-\u001f\u007f]/.test(v);
const prose=(v:unknown,max:number)=>typeof v==='string' && v.length>0 && v.length<=max && !/[\u0000-\u0008\u000b-\u001f\u007f]/.test(v);
const integer=(v:unknown)=>typeof v==='number' && Number.isSafeInteger(v) && v>=0;
const date=(v:unknown)=>typeof v==='string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(v) && Number.isFinite(Date.parse(v));
const object=(v:unknown):Record<string,unknown>=>{if(!v || typeof v!=='object' || Array.isArray(v))throw new Error('invalid_conversation_message');return v as Record<string,unknown>;};
function exact(p:Record<string,unknown>,keys:string[]){if(Object.keys(p).length!==keys.length || keys.some(k=>!Object.hasOwn(p,k)))throw new Error('invalid_conversation_message');}
export function validateConversationRequest(type:string,p:Record<string,unknown>){
  const fields:Record<string,string[]>={'conversation.status.request':['callId','afterSequence'],'conversation.confirm':['callId','confirmationId'],'conversation.cancel':['callId','confirmationId'],'conversation.correct':['callId','confirmationId','text'],'conversation.select':['callId','threadId']};
  const keys=fields[type];if(!keys)throw new Error('unknown_conversation_message');exact(p,keys);
  if(!uuid(p.callId) || (keys.includes('confirmationId') && !uuid(p.confirmationId)) || (type==='conversation.correct' && !text(p.text,2000)) || (type==='conversation.select' && !id(p.threadId)) || (type==='conversation.status.request' && !integer(p.afterSequence)))throw new Error('invalid_conversation_message');
}
export function validateConversationResponse(type:string,p:Record<string,unknown>){
  if(type==='conversation.error'){exact(p,['code']);if(!text(p.code,64) || !/^[a-z_]+$/.test(String(p.code)))throw new Error('invalid_conversation_message');return;}
  if(type!=='conversation.status.response')throw new Error('unknown_conversation_message');
  exact(p,['callId','phase','error','selection','proposal','submission','entries']);
  if(!uuid(p.callId) || !['ready','awaiting_confirmation','submitting','accepted','running','awaitingApproval','completed','failed','interrupted','unknown','cancelled','expired','rejected','busy','error'].includes(String(p.phase)) || !text(p.error,64,0) || !/^[a-z_]*$/.test(String(p.error)))throw new Error('invalid_conversation_message');
  if(p.selection!==null){const s=object(p.selection);exact(s,['threadId','label','project','mode']);if(!id(s.threadId) || !text(s.label,80) || !text(s.project,80) || !['mock','real'].includes(String(s.mode)))throw new Error('invalid_conversation_message');}
  if(p.proposal!==null){const s=object(p.proposal);exact(s,['id','text','expiresAt','threadId','project']);if(!uuid(s.id) || !text(s.text,2000) || !date(s.expiresAt) || !id(s.threadId) || !text(s.project,80))throw new Error('invalid_conversation_message');}
  if(p.submission!==null){const s=object(p.submission);exact(s,['id','threadId','turnId','state','submittedAt','acceptedAt','firstOutputAt','finishedAt']);if(!uuid(s.id) || !id(s.threadId) || (s.turnId!==null && !id(s.turnId)) || !['submitting','accepted','running','awaitingApproval','completed','failed','interrupted','unknown','rejected'].includes(String(s.state)) || !date(s.submittedAt) || [s.acceptedAt,s.firstOutputAt,s.finishedAt].some(d=>d!==null && !date(d)))throw new Error('invalid_conversation_message');}
  if(!Array.isArray(p.entries) || p.entries.length>4)throw new Error('invalid_conversation_message');let last=0;
  for(const row of p.entries){const e=object(row);exact(e,['id','sequence','kind','text','spoken','timestamp']);if(!uuid(e.id) || !integer(e.sequence) || Number(e.sequence)<=last || !['system','codex'].includes(String(e.kind)) || !prose(e.text,16000) || !text(e.spoken,1000) || !date(e.timestamp))throw new Error('invalid_conversation_message');last=Number(e.sequence);}
}
