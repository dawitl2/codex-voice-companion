const id=(v:unknown)=>typeof v==='string'&&/^[A-Za-z0-9_-]{1,128}$/.test(v)&&!['__proto__','constructor','prototype'].includes(v);
const exact=(p:Record<string,unknown>,k:string[])=>{if(Object.keys(p).length!==k.length||k.some(k=>!Object.hasOwn(p,k)))throw Error('invalid_chat_message');};
export function validateChatRequest(type:string,p:Record<string,unknown>){
  if(type==='chat.status.request'||type==='chat.select')exact(p,['threadId']);
  else if(type==='chat.send'){exact(p,['threadId','requestId','text']);if(typeof p.requestId!=='string'||!/^[a-f0-9-]{36}$/.test(p.requestId)||typeof p.text!=='string'||!p.text.trim()||p.text.length>2000)throw Error('invalid_chat_message');}
  else throw Error('unknown_chat_message');if(!id(p.threadId))throw Error('invalid_chat_message');
}
export function validateChatResponse(type:string,p:Record<string,unknown>){
  if(type==='chat.error'){exact(p,['code']);if(typeof p.code!=='string'||!/^[a-z_]{1,64}$/.test(p.code))throw Error('invalid_chat_message');return;}
  if(type!=='chat.status.response')throw Error('unknown_chat_message');exact(p,['threadId','label','project','mode','policy','selected','turns','progress']);
  if(!id(p.threadId)||typeof p.selected!=='boolean'||!['mock','real'].includes(String(p.mode))||!['read-only','workspace-write'].includes(String(p.policy)))throw Error('invalid_chat_message');
  for(const k of ['label','project','progress'])if(typeof p[k]!=='string'||String(p[k]).length>240)throw Error('invalid_chat_message');
  if(!Array.isArray(p.turns)||p.turns.length>12)throw Error('invalid_chat_message');
  for(const t of p.turns){exact(t,['id','status','instruction','response','startedAt','finishedAt']);if(!id(t.id)||!['starting','running','awaitingApproval','completed','failed','interrupted','unknown'].includes(t.status)||typeof t.instruction!=='string'||t.instruction.length>2000||typeof t.response!=='string'||t.response.length>16000||!Number.isFinite(Date.parse(t.startedAt))||(t.finishedAt!==null&&!Number.isFinite(Date.parse(t.finishedAt))))throw Error('invalid_chat_message');}
}
