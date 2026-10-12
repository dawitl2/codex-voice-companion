import {createHash,createPublicKey,randomUUID,verify} from 'node:crypto';
import {basename} from 'node:path';
import type {Bridge} from '../core/Bridge.js';
import type {RegistryStorage} from '../remote/DeviceRegistry.js';
import {SubmissionLedger} from '../conversation/SubmissionLedger.js';
import {sanitize,terminal} from '../utils/safe.js';
import {projectDirectory,samePath} from '../config/settings.js';
export class ControlError extends Error {constructor(readonly code:string){super(code);}}
interface Binding {deviceId:string;threadId:string;turnId:string;cwd:string;model:string;version:number;expiresAt:string}
interface Operation extends Binding {id:string;kind:'interrupt'|'queue'|'deny';state:string;text:string;approvalId:string|null}
interface Reviewer {deviceId:string;key:string;fingerprint:string;trusted:boolean}
interface Challenge {id:string;deviceId:string;threadId:string;approvalId:string;transactionId:string;message:string;expiresAt:string;generation:number}
/** Fixed operations only. ApprovalManager remains the sole RPC decision arbiter. */
export class TaskControl {
  private readonly connected=new Map<string,number>();private readonly generations=new Map<string,number>();
  private readonly operations=new Map<string,Operation>();private readonly queues=new Map<string,Operation>();
  private readonly challenges=new Map<string,Challenge>();private readonly receipts=new Map<string,{deviceId:string;threadId:string;approvalId:string;decision:string}>();
  private reviewers:Reviewer[];private closed=false;private readonly timer:NodeJS.Timeout;private readonly unsubscribe:()=>void;
  constructor(private readonly bridge:Bridge,readonly ledger:SubmissionLedger,private readonly storage:RegistryStorage,private readonly paired:(id:string)=>boolean,private readonly now:()=>number=Date.now){
    const raw=storage.load();
    if(!Array.isArray(raw) || raw.length>9)throw new Error('invalid_control_store');
    this.reviewers=[];
    for(const r of raw) if(r.kind==='reviewer') {
      if(typeof r.deviceId!=='string'||typeof r.key!=='string'||typeof r.fingerprint!=='string'||typeof r.trusted!=='boolean'||this.keyFingerprint(r.key)!==r.fingerprint)throw new Error('invalid_control_store');
      this.reviewers.push({deviceId:r.deviceId,key:r.key,fingerprint:r.fingerprint,trusted:r.trusted});
    } else if(r.kind!=='queue')throw new Error('invalid_control_store');
    // A restarted bridge discards stored queued input. It never replays possibly dispatched work.
    this.persist();this.unsubscribe=bridge.events.subscribe(()=>setImmediate(()=>this.tick()));
    this.timer=setInterval(()=>this.tick(),500);this.timer.unref();
  }
  private keyFingerprint(key:string){
    const bytes=Buffer.from(key,'base64'),publicKey=createPublicKey({key:bytes,format:'der',type:'spki'});
    if(publicKey.asymmetricKeyType!=='ec'||publicKey.asymmetricKeyDetails?.namedCurve!=='prime256v1'||bytes.length>256)throw new ControlError('invalid_reviewer_key');
    return createHash('sha256').update(bytes).digest('hex');
  }
  register(deviceId:string,key:string){this.owner(deviceId);const fingerprint=this.keyFingerprint(key);const previous=this.reviewers.find(r=>r.deviceId===deviceId);
    if(previous?.key===key)return;
    if(this.reviewers.length && !previous)throw new ControlError('reviewer_limit');
    const candidate={deviceId,key,fingerprint,trusted:false};const next=this.reviewers.filter(r=>r.deviceId!==deviceId).concat(candidate);
    const previousRows=this.reviewers;this.reviewers=next;try{this.persist();}catch(e){this.reviewers=previousRows;throw e;}
    for(const [id,c]of this.challenges)if(c.deviceId===deviceId)this.challenges.delete(id);
  }
  reviewerList(){return this.reviewers.map(({deviceId,fingerprint,trusted})=>({deviceId,fingerprint,trusted}));}
  trust(deviceId:string,fingerprint:string){const r=this.reviewers.find(r=>r.deviceId===deviceId&&r.fingerprint===fingerprint);if(!r||!this.paired(deviceId))throw new ControlError('reviewer_unavailable');r.trusted=true;try{this.persist();}catch(e){r.trusted=false;throw e;}}
  connection(deviceId:string,delta:1|-1){const n=Math.max(0,(this.connected.get(deviceId)??0)+delta);if(n)this.connected.set(deviceId,n);else{this.connected.delete(deviceId);this.invalidate(deviceId);}}
  revoke(deviceId:string){this.connected.delete(deviceId);this.invalidate(deviceId);this.reviewers=this.reviewers.filter(r=>r.deviceId!==deviceId);this.persist();}
  private invalidate(deviceId:string){this.generations.set(deviceId,(this.generations.get(deviceId)??0)+1);for(const c of this.challenges.values())if(c.deviceId===deviceId)this.challenges.delete(c.id);
    for(const op of this.operations.values())if(op.deviceId===deviceId&&['proposed','queued'].includes(op.state))this.cancelOperation(op,'review_required');}
  private owner(deviceId:string){if(this.closed||!this.paired(deviceId)||!this.connected.has(deviceId))throw new ControlError('control_disconnected');}
  private binding(deviceId:string,threadId:string,turnId:string):Binding{
    this.owner(deviceId);const thread=this.bridge.threads.get(threadId),turn=this.bridge.turns.get(threadId,turnId);
    if(!this.bridge.available||!this.bridge.threads.loaded.has(threadId)||this.bridge.threads.activeId!==threadId||!turn||terminal(turn.status)||turn.status==='unknown')throw new ControlError('task_unavailable');
    if(!samePath(projectDirectory(thread.cwd),thread.cwd))throw new ControlError('project_changed');
    return {deviceId,threadId,turnId,cwd:thread.cwd,model:thread.model,version:this.bridge.threads.selectionVersion,expiresAt:new Date(this.now()+45000).toISOString()};
  }
  private valid(op:Binding,allowCompleted=false){
    this.owner(op.deviceId);const t=this.bridge.threads.get(op.threadId),turn=this.bridge.turns.get(op.threadId,op.turnId);
    if(this.now()>=Date.parse(op.expiresAt)||!this.bridge.available||!this.bridge.threads.loaded.has(t.id)||t.cwd!==op.cwd||t.model!==op.model||this.bridge.threads.activeId!==t.id||this.bridge.threads.selectionVersion!==op.version||!samePath(projectDirectory(t.cwd),t.cwd)||!turn||turn.status==='unknown'||this.bridge.store.state.turns.filter(r=>r.threadId===t.id).at(-1)?.id!==op.turnId||(!allowCompleted&&terminal(turn.status)))throw new ControlError('stale_control');
    return turn;
  }
  private add(op:Operation){
    if(this.operations.size>=200){const done=[...this.operations.values()].find(o=>!['proposed','queued','requesting','submitting','requested'].includes(o.state));if(!done)throw new ControlError('operation_limit');this.operations.delete(done.id);}
    this.operations.set(op.id,op);return op;
  }
  propose(deviceId:string,threadId:string,turnId:string,kind:Operation['kind'],text='',approvalId:string|null=null){
    const b=this.binding(deviceId,threadId,turnId);
    if(kind==='queue'){
      if(!text.trim()||text.length>2000||sanitize(text,2000)!==text||/[\u0000-\u001f]/.test(text))throw new ControlError('invalid_instruction');
      if(/\b(?:force[- ]?push|git\s+remote|rm\s+-rf|delete\s+(?:the\s+)?project|disable\s+(?:the\s+)?(?:sandbox|approvals?)|change\s+(?:authentication|billing)|install\s+system[- ]wide)\b/i.test(text))throw new ControlError('desktop_action_required');
      if(this.bridge.turns.get(threadId,turnId)?.status==='awaitingApproval'||this.ledger.uncertain(threadId))throw new ControlError('review_required');
      if(this.queues.has(threadId)||this.queues.size>=8)throw new ControlError('queue_exists');
    }
    if(kind==='deny'){const r=this.bridge.approvals.review(approvalId!);if(r.threadId!==threadId||r.turnId!==turnId||r.status!=='pending')throw new ControlError('approval_unavailable');}
    for(const op of this.operations.values())if(op.deviceId===deviceId&&op.threadId===threadId&&op.state==='proposed')this.cancelOperation(op,'cancelled');
    return this.add({...b,id:randomUUID(),kind,state:'proposed',text,approvalId});
  }
  async confirm(deviceId:string,threadId:string,id:string){
    this.owner(deviceId);const op=this.operations.get(id);if(!op||op.deviceId!==deviceId||op.threadId!==threadId)throw new ControlError('wrong_operation');
    if(op.state!=='proposed')return this.status(deviceId,threadId);
    this.valid(op);
    if(op.kind==='queue'){
      if(this.queues.has(threadId)||this.bridge.turns.get(threadId,op.turnId)?.status==='awaitingApproval')throw new ControlError('review_required');
      op.state='queued';op.expiresAt=new Date(this.now()+300000).toISOString();this.queues.set(threadId,op);
      try{this.persist();}catch(e){this.queues.delete(threadId);op.state='rejected';op.text='';throw e;}
    } else if(op.kind==='deny'){
      const r=this.bridge.approvals.review(op.approvalId!);if(r.threadId!==threadId||r.turnId!==op.turnId)throw new ControlError('wrong_approval');
      this.bridge.approvals.resolve(r.id,false);op.state='decision_sent';
    } else {
      op.state='requesting';try{await this.bridge.turns.interrupt(threadId,op.turnId);op.state='requested';}catch{op.state='unknown';}
    }
    return this.status(deviceId,threadId);
  }
  cancel(deviceId:string,threadId:string,id:string){this.owner(deviceId);const op=this.operations.get(id);if(!op||op.deviceId!==deviceId||op.threadId!==threadId)throw new ControlError('wrong_operation');if(['proposed','queued'].includes(op.state))this.cancelOperation(op,'cancelled');return this.status(deviceId,threadId);}
  private cancelOperation(op:Operation,state:string){op.state=state;op.text='';if(this.queues.get(op.threadId)===op){this.queues.delete(op.threadId);this.persist();}}
  challenge(deviceId:string,threadId:string,approvalId:string,transactionId:string){
    this.owner(deviceId);const r=this.bridge.approvals.review(approvalId),key=this.reviewers.find(k=>k.deviceId===deviceId&&k.trusted);
    if(!key||!r.eligible||r.threadId!==threadId)throw new ControlError('desktop_approval_required');
    this.binding(deviceId,threadId,r.turnId);
    const id=randomUUID(),expiresAt=new Date(Math.min(this.now()+30000,Date.parse(r.expiresAt))).toISOString();
    const message=JSON.stringify({purpose:'codex-voice-approve-once',id,transactionId,deviceId,threadId,turnId:r.turnId,requestId:r.requestId,itemId:r.itemId,category:r.method,createdAt:r.requestedAt,approvalExpiresAt:r.expiresAt,expiresAt,decisions:['accept'],preview:r.preview,scope:r.scope});
    // At most one live approval challenge per paired reviewer.
    for(const [cid,c]of this.challenges)if(c.deviceId===deviceId)this.challenges.delete(cid);
    const c={id,deviceId,threadId,approvalId,transactionId,message,expiresAt,generation:this.generations.get(deviceId)??0};this.challenges.set(id,c);
    return {id,transactionId,message,expiresAt};
  }
  decide(deviceId:string,threadId:string,approvalId:string,transactionId:string,decision:'accept'|'decline',challengeId:string|null,signature:string|null){
    this.owner(deviceId);const receipt=this.receipts.get(transactionId);
    if(receipt){if(receipt.deviceId!==deviceId||receipt.threadId!==threadId||receipt.approvalId!==approvalId||receipt.decision!==decision)throw new ControlError('wrong_transaction');return this.status(deviceId,threadId);}
    const r=this.bridge.approvals.review(approvalId);if(r.threadId!==threadId||r.status!=='pending')throw new ControlError('approval_unavailable');this.binding(deviceId,threadId,r.turnId);
    if(decision==='accept'){
      const c=this.challenges.get(challengeId!),key=this.reviewers.find(k=>k.deviceId===deviceId&&k.trusted);
      if(!c||!key||!r.eligible||c.deviceId!==deviceId||c.threadId!==threadId||c.approvalId!==approvalId||c.transactionId!==transactionId||c.generation!==(this.generations.get(deviceId)??0)||this.now()>=Date.parse(c.expiresAt)||!signature||!verify('sha256',Buffer.from(c.message),{key:Buffer.from(key.key,'base64'),format:'der',type:'spki'},Buffer.from(signature,'base64')))throw new ControlError('authorization_failed');
      this.challenges.delete(c.id);
    }else if(challengeId!==null||signature!==null)throw new ControlError('invalid_decision');
    if(this.receipts.size>=200)this.receipts.delete(this.receipts.keys().next().value!);
    // Same synchronous arbiter as desktop. No caller command or broader decision is sent.
    this.bridge.approvals.resolve(approvalId,decision==='accept');this.receipts.set(transactionId,{deviceId,threadId,approvalId,decision});
    return this.status(deviceId,threadId);
  }
  status(deviceId:string,threadId:string){
    this.owner(deviceId);const thread=this.bridge.threads.get(threadId);this.tick();const turn=this.bridge.store.state.turns.filter(t=>t.threadId===threadId).at(-1);
    const ops=[...this.operations.values()].filter(o=>o.deviceId===deviceId&&o.threadId===threadId).slice(-8).map(o=>({id:o.id,kind:o.kind,state:o.state,turnId:o.turnId,expiresAt:o.expiresAt,text:o.text}));
    // JsonStore groups pending requests ahead of retained history. Never infer recency from its array order.
    const records=this.bridge.store.state.approvals.filter(r=>r.threadId===threadId).sort((a,b)=>Date.parse(a.requestedAt)-Date.parse(b.requestedAt));
    const displayed=records.filter(r=>r.status!=='pending').concat(records.filter(r=>r.status==='pending')).slice(-8);
    const approvals=displayed.map(r=>{const p=this.bridge.approvals.review(r.id);return {id:r.id,requestId:String(r.requestId),turnId:r.turnId,itemId:p.itemId,category:r.method,preview:p.preview,reason:p.reason,scope:p.scope,risk:p.eligible?'Desktop reviewed local action; fresh phone authentication required':'Desktop review required; scope or effect not established',status:r.status,serverConfirmed:p.serverConfirmed,createdAt:r.requestedAt,expiresAt:r.expiresAt,allowedDecisions:r.status==='pending'?(p.eligible?['accept','decline']:['decline']):[]};});
    const reviewer=this.reviewers.find(r=>r.deviceId===deviceId);
    return {threadId,project:sanitize(basename(thread.cwd),80),mode:thread.mode,task:turn?{id:turn.id,status:turn.status,result:sanitize(turn.finalResponse,16000)}:null,progress:sanitize(this.bridge.store.state.events.filter(e=>e.threadId===threadId&&e.turnId===turn?.id).at(-1)?.summary??'No progress',240),operations:ops,approvals,reviewer:reviewer?{fingerprint:reviewer.fingerprint,trusted:reviewer.trusted}:null};
  }
  private persist(){this.storage.save([...this.reviewers.map(r=>({kind:'reviewer',...r})),...[...this.queues.values()].map(q=>({...q,kind:'queue'}))]);}
  private tick(){if(this.closed)return;
    for(const [id,c]of this.challenges)if(this.now()>=Date.parse(c.expiresAt))this.challenges.delete(id);
    for(const op of this.operations.values()){
      if(op.kind==='queue'&&op.state==='accepted'){const row=this.ledger.get(op.id),turn=row?.turnId?this.bridge.turns.get(op.threadId,row.turnId):null;if(turn&&terminal(turn.status)){op.state=turn.status;if(row!.state!==turn.status)try{this.ledger.update(op.id,{state:turn.status as 'completed'|'failed'|'interrupted',finishedAt:turn.finishedAt??null});}catch{op.state='unknown';}}}
      if(op.kind==='interrupt'&&op.state==='requested'){const t=this.bridge.turns.get(op.threadId,op.turnId);if(t&&terminal(t.status))op.state=t.status;}
      if(!['proposed','queued'].includes(op.state))continue;
      try{
        const turn=this.valid(op,op.state==='queued');
        if(op.state==='queued'){
          if(['awaitingApproval','failed','interrupted'].includes(turn.status))this.cancelOperation(op,'review_required');
          else if(turn.status==='completed'){
            if(this.bridge.turns.active(op.threadId)||this.ledger.uncertain(op.threadId))this.cancelOperation(op,'review_required');
            else {op.state='submitting';void this.dispatch(op);}
          }
        }
      }catch{try{this.cancelOperation(op,this.now()>=Date.parse(op.expiresAt)?'expired':'review_required');}catch{op.state='review_required';op.text='';}}
    }
  }
  private async dispatch(op:Operation){let dispatched=false;
    try{
      this.ledger.add({id:op.id,deviceId:op.deviceId,callId:op.id,threadId:op.threadId,turnId:null,state:'submitting',submittedAt:new Date(this.now()).toISOString(),acceptedAt:null,firstOutputAt:null,finishedAt:null});
      const text=op.text;this.queues.delete(op.threadId);op.text='';this.persist();
      const turnId=await this.bridge.send(op.threadId,text,()=>{const turn=this.valid(op,true);if(turn.status!=='completed'||this.bridge.turns.active(op.threadId))throw new ControlError('stale_control');dispatched=true;});
      this.ledger.update(op.id,{turnId,state:'accepted',acceptedAt:new Date(this.now()).toISOString()});op.state='accepted';
    }catch{op.state=dispatched?'unknown':'rejected';if(this.ledger.get(op.id))try{this.ledger.update(op.id,{state:dispatched?'unknown':'rejected'});}catch{}op.text='';this.queues.delete(op.threadId);try{this.persist();}catch{}}
  }
  close(){this.closed=true;clearInterval(this.timer);this.unsubscribe();for(const q of this.queues.values())q.text='';this.queues.clear();this.challenges.clear();this.operations.clear();try{this.persist();}catch{/* Restart always discards queued input, even if shutdown storage is unavailable. */}}
}
