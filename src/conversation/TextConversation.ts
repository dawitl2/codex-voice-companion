import { basename } from 'node:path';
import type { Bridge } from '../core/Bridge.js';
import { AppServerRejection } from '../core/AppServerClient.js';
import { SubmissionLedger } from './SubmissionLedger.js';
import { sanitize, terminal } from '../utils/safe.js';
export class ChatError extends Error { constructor(readonly code: string) { super(code); } }
/** Explicit text Send uses the same authenticated Bridge.send and protected ledger as voice. */
export class TextConversation {
  private readonly connected = new Map<string, number>(); private readonly generation = new Map<string, number>();
  constructor(private readonly bridge: Bridge, readonly ledger: SubmissionLedger) {}
  connection(device: string, delta: 1 | -1) { const n = Math.max(0,(this.connected.get(device)??0)+delta); if(n)this.connected.set(device,n);else{this.connected.delete(device);this.generation.set(device,(this.generation.get(device)??0)+1);} }
  private owner(device:string){if(!this.connected.has(device)||!this.bridge.available)throw new ChatError('chat_disconnected');}
  async select(device:string,threadId:string){this.owner(device);const gen=this.generation.get(device)??0;await this.bridge.select(threadId);this.owner(device);if((this.generation.get(device)??0)!==gen)throw new ChatError('chat_disconnected');return this.status(device,threadId);}
  status(device:string,threadId:string){
    this.ledger.reconcile(this.bridge.store.state.turns);
    this.owner(device);const thread=this.bridge.threads.get(threadId),rows=this.bridge.store.state.turns.filter(t=>t.threadId===threadId).slice(-12);
    for(const row of this.ledger.list().filter(r=>r.threadId===threadId&&r.turnId)){const t=rows.find(t=>t.id===row.turnId);if(t&&terminal(t.status)&&row.state!==t.status)this.ledger.update(row.id,{state:t.status as 'completed'|'failed'|'interrupted',finishedAt:t.finishedAt??null});}
    return {threadId,label:thread.label,project:basename(thread.cwd),mode:thread.mode,policy:this.bridge.threads.policy(threadId),selected:this.bridge.threads.activeId===threadId,
      turns:rows.map(t=>({id:t.id,status:t.status,instruction:sanitize(t.instruction??'',2000),response:sanitize(t.finalResponse,16000),startedAt:t.startedAt,finishedAt:t.finishedAt??null})),
      progress:sanitize(this.bridge.store.state.events.filter(e=>e.threadId===threadId).at(-1)?.summary??'Ready',240)};
  }
  async send(device:string,threadId:string,requestId:string,text:string){
    this.owner(device);const old=this.ledger.get(requestId);if(old){if(old.deviceId!==device||old.threadId!==threadId||old.callId!==requestId)throw new ChatError('wrong_submission');return this.status(device,threadId);}
    if(!text.trim()||text.length>2000||sanitize(text,2000)!==text||/[\x00-\x1f\x7f]/.test(text))throw new ChatError('invalid_instruction');
    const external=this.bridge.threads.get(threadId).observed===true;
    if(this.bridge.threads.activeId!==threadId||(!external&&!this.bridge.threads.loaded.has(threadId)))throw new ChatError('select_conversation');
    if((!external&&this.bridge.turns.active(threadId))||this.ledger.uncertain(threadId))throw new ChatError('thread_busy');
    const thread=this.bridge.threads.get(threadId),version=this.bridge.threads.selectionVersion,gen=this.generation.get(device)??0,model=thread.model,cwd=thread.cwd;
    const before=new Set(this.bridge.store.state.turns.filter(t=>t.threadId===threadId).map(t=>t.id));
    this.ledger.add({id:requestId,deviceId:device,callId:requestId,threadId,turnId:null,state:'submitting',submittedAt:new Date().toISOString(),acceptedAt:null,firstOutputAt:null,finishedAt:null});let dispatched=false;
    try{const receipt=await this.bridge.submit(threadId,text,requestId,()=>{this.owner(device);if((this.generation.get(device)??0)!==gen)throw new ChatError('chat_disconnected');if(this.bridge.threads.selectionVersion!==version||this.bridge.threads.activeId!==threadId||thread.model!==model||thread.cwd!==cwd)throw new ChatError('thread_changed');dispatched=true;});this.ledger.update(requestId,{turnId:receipt.turnId,state:receipt.queued?'queued':'accepted',acceptedAt:receipt.queued?null:new Date().toISOString()});}
    catch(e){const observed=this.bridge.store.state.turns.some(t=>t.threadId===threadId&&!before.has(t.id));const unknown=dispatched&&(observed||!(e instanceof AppServerRejection));this.ledger.update(requestId,{state:unknown?'unknown':'rejected'});throw new ChatError(unknown?'submission_unknown':'submission_rejected');}
    return this.status(device,threadId);
  }
}
