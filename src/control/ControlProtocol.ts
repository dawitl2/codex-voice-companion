import {object} from '../utils/safe.js';
import {ProtocolError,textField} from '../remote/Protocol.js';
export const CONTROL_REQUESTS=['control.status.request','control.interrupt.propose','control.queue.propose','control.deny.propose','control.confirm','control.cancel','control.reviewer.register','control.approval.challenge','control.approval.decide'] as const;
export type ControlRequest=typeof CONTROL_REQUESTS[number];
function exact(p:Record<string,unknown>,fields:string[]){if(Object.keys(p).length!==fields.length||fields.some(k=>!Object.hasOwn(p,k)))throw new ProtocolError('invalid_message');}
function id(v:unknown){if(!/^[A-Za-z0-9_-]{1,128}$/.test(textField(v,128)))throw new ProtocolError('invalid_message');}
function uuid(v:unknown){if(!/^[a-f0-9-]{36}$/.test(textField(v,36)))throw new ProtocolError('invalid_message');}
export function validateControlRequest(type:string,p:Record<string,unknown>){
  if(!(CONTROL_REQUESTS as readonly string[]).includes(type))throw new ProtocolError('unknown_message');
  const fields=['threadId'];
  if(['control.interrupt.propose','control.queue.propose','control.deny.propose'].includes(type))fields.push('turnId');
  if(type==='control.queue.propose')fields.push('text');
  if(type==='control.deny.propose')fields.push('approvalId');
  if(['control.confirm','control.cancel'].includes(type))fields.push('operationId');
  if(type==='control.reviewer.register')fields.push('publicKey');
  if(['control.approval.challenge','control.approval.decide'].includes(type))fields.push('approvalId','transactionId');
  if(type==='control.approval.decide')fields.push('decision','challengeId','signature');
  exact(p,fields);id(p.threadId);if(p.turnId!==undefined)id(p.turnId);
  for(const k of ['operationId','approvalId','transactionId'])if(p[k]!==undefined)uuid(p[k]);
  if(p.text!==undefined)textField(p.text,2000);
  if(p.publicKey!==undefined&&!/^[A-Za-z0-9+/]+={0,2}$/.test(textField(p.publicKey,512)))throw new ProtocolError('invalid_message');
  if(type==='control.approval.decide'){
    if(!['accept','decline'].includes(String(p.decision)))throw new ProtocolError('invalid_message');
    if(p.challengeId!==null)uuid(p.challengeId);
    if(p.signature!==null&&!/^[A-Za-z0-9+/]+={0,2}$/.test(textField(p.signature,256)))throw new ProtocolError('invalid_message');
  }
}
function date(v:unknown){if(typeof v!=='string'||!Number.isFinite(Date.parse(v)))throw new ProtocolError('invalid_message');}
function multiline(v:unknown,max:number,empty=false){if(typeof v!=='string'||(!empty&&!v)||v.length>max||/[\u0000-\u0008\u000b-\u001f\u007f]/.test(v))throw new ProtocolError('invalid_message');}
function rows(v:unknown){if(!Array.isArray(v)||v.length>8)throw new ProtocolError('invalid_message');return v.map(object);}
export function validateControlResponse(type:string,p:Record<string,unknown>){
  if(type==='control.error'){exact(p,['code']);textField(p.code,64);return;}
  if(type==='control.approval.challenge.response'){exact(p,['id','transactionId','message','expiresAt']);uuid(p.id);uuid(p.transactionId);multiline(p.message,8192);date(p.expiresAt);return;}
  if(type!=='control.status.response')throw new ProtocolError('unknown_message');
  exact(p,['threadId','project','mode','task','progress','operations','approvals','reviewer']);id(p.threadId);textField(p.project,80);if(!['mock','real'].includes(String(p.mode)))throw new ProtocolError('invalid_message');multiline(p.progress,240);
  if(p.task!==null){const t=object(p.task);exact(t,['id','status','result']);id(t.id);if(!['starting','running','awaitingApproval','completed','failed','interrupted','unknown'].includes(String(t.status)))throw new ProtocolError('invalid_message');multiline(t.result,16000,true);}
  for(const o of rows(p.operations)){exact(o,['id','kind','state','turnId','expiresAt','text']);uuid(o.id);id(o.turnId);date(o.expiresAt);textField(o.state,32);if(!['queue','interrupt','deny'].includes(String(o.kind)))throw new ProtocolError('invalid_message');multiline(o.text,2000,true);}
  for(const a of rows(p.approvals)){exact(a,['id','requestId','turnId','itemId','category','preview','reason','scope','risk','status','serverConfirmed','createdAt','expiresAt','allowedDecisions']);uuid(a.id);textField(a.requestId,128);id(a.turnId);if(a.itemId!==null)id(a.itemId);textField(a.category,64);multiline(a.preview,4000);multiline(a.reason,1000);multiline(a.scope,1000);textField(a.risk,240);textField(a.status,32);date(a.createdAt);date(a.expiresAt);if(typeof a.serverConfirmed!=='boolean'||!Array.isArray(a.allowedDecisions)||a.allowedDecisions.length>2||a.allowedDecisions.some(d=>!['accept','decline'].includes(d)))throw new ProtocolError('invalid_message');}
  if(p.reviewer!==null){const r=object(p.reviewer);exact(r,['fingerprint','trusted']);if(!/^[a-f0-9]{64}$/.test(String(r.fingerprint))||typeof r.trusted!=='boolean')throw new ProtocolError('invalid_message');}
}
