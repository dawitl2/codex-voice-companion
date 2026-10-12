import {randomUUID} from 'node:crypto';
import {basename} from 'node:path';
import type {Bridge} from '../core/Bridge.js';
import {TERMINAL,type CallSession, type CallSessionManager} from '../calls/CallSessionManager.js';
import type {FinalTranscription} from '../speech/SpeechSessionCoordinator.js';
import {spokenSummary} from '../speech/SpokenSummaryFormatter.js';
import {sanitize,terminal} from '../utils/safe.js';
import {projectDirectory,samePath} from '../config/settings.js';
import {voiceIntent} from './VoiceIntent.js';
import {SubmissionLedger,type SubmissionState} from './SubmissionLedger.js';

export class ConversationError extends Error { constructor(readonly code:string){super(code);} }
interface Proposal { id:string; text:string; expiresAt:string; threadId:string; project:string }
interface BoundProposal extends Proposal { cwd:string; model:string; selected:string|null; selectionVersion:number; generation:number }
interface Entry { id:string; sequence:number; kind:'system'|'codex'; text:string; spoken:string; timestamp:string }
interface Session { callId:string; deviceId:string; threadId:string; generation:number; phase:string; proposal:BoundProposal|null; submissionId:string|null; entries:Entry[]; sequence:number; seen:Set<string>; terminalSpoken:Set<string>; error:string }

/** Consumes only finalized, internally authenticated ASR. No direct RPC or shell forwarding. */
export class VoiceConversationController {
  private readonly sessions=new Map<string,Session>(); private readonly connected=new Map<string,number>();
  private readonly unsubscribe:()=>void; private readonly events:()=>void; private readonly timer:NodeJS.Timeout;
  private closed=false;
  constructor(private readonly bridge:Bridge,private readonly calls:CallSessionManager,readonly ledger:SubmissionLedger,private readonly now:()=>number=Date.now) {
    this.unsubscribe=calls.onChange(call=>{ if(TERMINAL.has(call.state) || call.state!=='active') this.end(call.callId); });
    this.events=bridge.events.subscribe(event=>{
      if(event.type==='bridge.disconnected') for(const s of this.sessions.values()) this.invalidate(s,'codex_unavailable');
      // Turn events can be emitted before the originating store mutation finishes.
      setImmediate(()=>{ if(!this.closed) for(const s of this.sessions.values()) this.safeRefresh(s); });
    });
    this.timer=setInterval(()=>{ for(const s of this.sessions.values()) { this.checkProposal(s); this.safeRefresh(s); } },500); this.timer.unref();
  }
  connection(deviceId:string,delta:1|-1) {
    const count=Math.max(0,(this.connected.get(deviceId)??0)+delta); if(count) this.connected.set(deviceId,count); else this.connected.delete(deviceId);
    if(!count) for(const s of this.sessions.values()) if(s.deviceId===deviceId) { s.generation++; this.invalidate(s,'connection_lost'); }
  }
  private owner(deviceId:string,callId:string):Session {
    const call=this.calls.status(deviceId,callId);
    if(this.closed || call.state!=='active' || !this.connected.has(deviceId)) throw new ConversationError('call_not_active');
    let s=this.sessions.get(callId);
    if(!s) { s={callId,deviceId,threadId:call.threadId,generation:0,phase:'ready',proposal:null,submissionId:null,entries:[],sequence:0,seen:new Set(),terminalSpoken:new Set(),error:''}; this.sessions.set(callId,s); }
    return s;
  }
  private add(s:Session,text:string,kind:'system'|'codex'='system',spoken=text) {
    s.entries.push({id:randomUUID(),sequence:++s.sequence,kind,text:sanitize(text,16000),spoken:sanitize(spoken,1000),timestamp:new Date(this.now()).toISOString()});
    if(s.entries.length>16) s.entries.shift();
  }
  private invalidate(s:Session,code:string) {
    if(!s.proposal) return; s.proposal=null; s.phase=code==='confirmation_expired'?'expired':'cancelled'; s.error=code;
    this.add(s,code==='confirmation_expired'?'That confirmation expired. Please say the instruction again.':'The pending instruction was cancelled. Please review the selected conversation and try again.');
  }
  private checkProposal(s:Session) {
    const p=s.proposal; if(!p) return;
    if(this.now()>=Date.parse(p.expiresAt)) {this.invalidate(s,'confirmation_expired');return;}
    try {
      const thread=this.bridge.threads.get(p.threadId);
      if(p.generation!==s.generation || p.threadId!==s.threadId || thread.cwd!==p.cwd || thread.model!==p.model || this.bridge.threads.selectionVersion!==p.selectionVersion || (this.bridge.threads.activeId??null)!==p.selected || !samePath(projectDirectory(thread.cwd),p.cwd)) this.invalidate(s,'thread_changed');
    } catch { this.invalidate(s,'thread_unavailable'); }
  }
  private selected(s:Session) {
    const t=this.bridge.threads.get(s.threadId);
    if(!samePath(projectDirectory(t.cwd),t.cwd)) throw new ConversationError('project_changed'); return t;
  }
  status(deviceId:string,callId:string,afterSequence=0) {
    const s=this.owner(deviceId,callId); this.checkProposal(s); this.safeRefresh(s);
    let selection=null;
    try { const t=this.selected(s); selection={threadId:t.id,label:sanitize(t.label,80),project:sanitize(basename(t.cwd),80),mode:t.mode}; } catch { s.error='thread_unavailable'; }
    const p=s.proposal;
    const row=s.submissionId?this.ledger.get(s.submissionId):null;
    const submission=row?{id:row.id,threadId:row.threadId,turnId:row.turnId,state:row.state,submittedAt:row.submittedAt,acceptedAt:row.acceptedAt,firstOutputAt:row.firstOutputAt,finishedAt:row.finishedAt}:null;
    return {callId,phase:s.phase,error:s.error,selection,proposal:p?{id:p.id,text:p.text,expiresAt:p.expiresAt,threadId:p.threadId,project:p.project}:null,submission,entries:s.entries.filter(e=>e.sequence>afterSequence).slice(-4).map(e=>({...e}))};
  }
  async select(deviceId:string,callId:string,threadId:string) {
    const s=this.owner(deviceId,callId); if(s.phase==='submitting') throw new ConversationError('submission_in_progress');
    s.generation++; this.invalidate(s,'thread_changed'); const generation=s.generation;
    this.bridge.threads.get(threadId); await this.bridge.select(threadId);
    if(this.owner(deviceId,callId)!==s || s.generation!==generation) throw new ConversationError('thread_changed');
    s.threadId=threadId; s.submissionId=null; s.phase='ready';s.error=''; const t=this.selected(s);
    this.add(s,'Selected '+sanitize(t.label,80)+' in '+sanitize(basename(t.cwd),80)+'.');
    return this.status(deviceId,callId);
  }
  cancel(deviceId:string,callId:string,confirmationId:string) {
    const s=this.owner(deviceId,callId); if(s.proposal?.id!==confirmationId) throw new ConversationError('confirmation_unavailable');
    this.invalidate(s,'user_cancelled'); return this.status(deviceId,callId);
  }
  correct(deviceId:string,callId:string,confirmationId:string,text:string) {
    const s=this.owner(deviceId,callId); this.checkProposal(s);
    if(s.proposal?.id!==confirmationId) throw new ConversationError('confirmation_unavailable');
    s.proposal=null; this.propose(s,text); return this.status(deviceId,callId);
  }
  private propose(s:Session,text:string) {
    if(!text.trim() || text.length>2000 || /[\u0000-\u001f\u007f\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(text)) throw new ConversationError('invalid_instruction');
    // Guard obvious secret/privileged requests; the existing Codex sandbox remains authoritative.
    if(sanitize(text,2000)!==text || /\b(?:force[- ]?push|git\s+remote|rm\s+-rf|delete\s+(?:the\s+)?project|disable\s+(?:the\s+)?(?:sandbox|approvals?)|change\s+(?:authentication|billing)|install\s+system[- ]wide)\b/i.test(text)) {
      s.phase='rejected';s.error='desktop_action_required';this.add(s,'That request needs review on the computer. Voice confirmation cannot approve privileged actions.');return;
    }
    if(!this.bridge.available) throw new ConversationError('codex_unavailable');
    const thread=this.selected(s);
    if(this.bridge.turns.active(thread.id) || this.ledger.uncertain(thread.id)) {s.phase='busy';this.add(s,'Codex has active or uncertain work in this conversation. Ask for status or review it on the computer.');return;}
    const p:BoundProposal={id:randomUUID(),text,threadId:thread.id,cwd:thread.cwd,model:thread.model,selected:this.bridge.threads.activeId??null,selectionVersion:this.bridge.threads.selectionVersion,generation:s.generation,project:sanitize(basename(thread.cwd),80),expiresAt:new Date(this.now()+45000).toISOString()};
    s.proposal=p;s.phase='awaiting_confirmation';s.error='';
    const excerpt=text.length>350?text.slice(0,350)+'… Review the full instruction on the screen.':text;
    this.add(s,'Proposed for '+p.project+': '+text,'system','I heard: '+excerpt+' In '+p.project+'. Should I send it? Say yes, send it, or cancel.');
  }
  async confirm(deviceId:string,callId:string,confirmationId:string) {
    const s=this.owner(deviceId,callId); const duplicate=this.ledger.get(confirmationId);
    if(duplicate) { if(duplicate.deviceId!==deviceId || duplicate.callId!==callId) throw new ConversationError('wrong_confirmation'); return this.status(deviceId,callId); }
    this.checkProposal(s); const p=s.proposal;
    if(!p || p.id!==confirmationId || s.phase!=='awaiting_confirmation') throw new ConversationError('confirmation_unavailable');
    if(!this.bridge.available || this.bridge.turns.active(p.threadId) || this.ledger.uncertain(p.threadId)) {this.invalidate(s,'thread_busy');throw new ConversationError('thread_busy');}
    s.phase='submitting';s.proposal=null;
    try {
      this.ledger.add({id:p.id,deviceId,callId,threadId:p.threadId,turnId:null,state:'submitting',submittedAt:new Date(this.now()).toISOString(),acceptedAt:null,firstOutputAt:null,finishedAt:null});
    } catch {s.phase='rejected';s.error='submission_storage_unavailable';throw new ConversationError('submission_storage_unavailable');}
    s.submissionId=p.id;
    let dispatched=false;
    try {
      let expectedSelected=p.selected,expectedVersion=p.selectionVersion;
      if(!this.bridge.threads.loaded.has(p.threadId)) {
        await this.bridge.select(p.threadId);
        expectedSelected=p.threadId; expectedVersion=p.selectionVersion+(p.selected===p.threadId?0:1);
      }
      const guard=()=>{
        if(this.owner(deviceId,callId)!==s || s.generation!==p.generation || s.threadId!==p.threadId || this.now()>=Date.parse(p.expiresAt)) throw new ConversationError('confirmation_unavailable');
        const thread=this.selected(s);
        if(thread.cwd!==p.cwd || thread.model!==p.model || this.bridge.threads.selectionVersion!==expectedVersion || (this.bridge.threads.activeId??null)!==expectedSelected) throw new ConversationError('thread_changed');
        dispatched=true;
      };
      const turnId=await this.bridge.send(p.threadId,p.text,guard);
      this.ledger.update(p.id,{turnId,state:'accepted',acceptedAt:new Date(this.now()).toISOString()});
      if(this.sessions.get(callId)===s) {s.phase='running';s.error='';this.add(s,'Instruction accepted by Codex.');this.safeRefresh(s);}
    } catch {
      try {this.ledger.update(p.id,{state:dispatched?'unknown':'rejected',finishedAt:dispatched?null:new Date(this.now()).toISOString()});} catch { /* Existing durable submitting record blocks replay. */ }
      if(this.sessions.get(callId)===s) {s.phase=dispatched?'unknown':'rejected';s.error=dispatched?'submission_unknown':'submission_rejected';this.add(s,dispatched?"Submission status uncertain. I won't send it again automatically. Review it on the computer.":'The instruction was not sent. Check the call, selected thread and Codex connection.');}
    }
    if(this.sessions.get(callId)!==s) return null;
    return this.status(deviceId,callId);
  }
  async transcription(call:CallSession,u:FinalTranscription) {
    let s:Session;
    try {
      s=this.owner(call.deviceId,call.callId);
      if(!u.final || u.language!=='en' || call.state!=='active' || call.muted || s.seen.has(u.utteranceId)) return;
      s.seen.add(u.utteranceId); if(s.seen.size>128) s.seen.delete(s.seen.values().next().value!);
      this.checkProposal(s);
      const intent=voiceIntent(u.text);
      if(intent==='confirm') { if(s.proposal) await this.confirm(s.deviceId,s.callId,s.proposal.id); else this.add(s,'There is no pending instruction to confirm.'); }
      else if(intent==='cancel' || intent==='reject') { if(s.proposal) this.invalidate(s,'user_cancelled'); else this.add(s,'There is no pending instruction. An accepted Codex task continues until it finishes or is managed on the computer.'); }
      else if(intent==='hangup') this.calls.end(s.deviceId,s.callId,'user_ended');
      else if(intent==='stop') this.add(s,'Speech stopped.');
      else if(intent==='repeat') { const last=s.entries.at(-1); if(last) this.add(s,last.text,last.kind,last.spoken); else this.result(s); }
      else if(intent==='project') { const t=this.selected(s);this.add(s,'You are connected to '+sanitize(t.label,80)+' in '+sanitize(basename(t.cwd),80)+'.'); }
      else if(intent==='result') this.result(s);
      else if(intent==='status') {
        const turn=this.bridge.store.state.turns.filter(t=>t.threadId===s.threadId).at(-1);
        this.add(s,turn?turn.status==='awaitingApproval'?'Codex is waiting for approval on the computer.':turn.status==='running' || turn.status==='starting'?'Codex is still working.':turn.status==='unknown'?'The task outcome is uncertain. Review the conversation on the computer.':spokenSummary(this.selected(s),turn):'No stored turn is available in this conversation.');
      } else if(intent==='help') this.add(s,'Ask for task status, the last result or the selected project. Say a programming request, review it, then say yes, send it. Say cancel to discard a proposal. Use Stop speaking during playback.');
      else if(s.phase==='submitting') this.add(s,'Submission is already in progress. I will not send it twice.');
      else { if(s.proposal) this.invalidate(s,'proposal_replaced');this.propose(s,u.text); }
    } catch(error) {
      const existing=this.sessions.get(call.callId);
      if(existing) {existing.error=error instanceof ConversationError?error.code:'conversation_unavailable';this.add(existing,'The request could not be completed. Review the selected conversation and connection, then try again.');}
    }
  }
  private result(s:Session) {
    const turn=this.bridge.store.state.turns.filter(t=>t.threadId===s.threadId).at(-1);
    if(!turn) {this.add(s,'No result is available.');return;}
    this.add(s,sanitize(turn.finalResponse || 'No final response is available.',16000),'codex',spokenSummary(this.selected(s),turn));
  }
  private refresh(s:Session) {
    if(!s.submissionId) return;
    const row=this.ledger.get(s.submissionId); if(!row?.turnId) return;
    const turn=this.bridge.turns.get(row.threadId,row.turnId); if(!turn) return;
    const state=turn.status==='starting'?'running':turn.status as SubmissionState;
    const firstOutputAt=row.firstOutputAt ?? (this.bridge.store.state.events.some(e=>e.threadId===row.threadId && e.turnId===row.turnId && e.type==='turn.progress')?new Date(this.now()).toISOString():null);
    if(row.state!==state || firstOutputAt!==row.firstOutputAt) this.ledger.update(row.id,{state,firstOutputAt,finishedAt:turn.finishedAt??null});
    if(!s.proposal && (row.state!==state || ['accepted','running','awaitingApproval','unknown'].includes(s.phase))) s.phase=state;
    if(terminal(turn.status) && !s.terminalSpoken.has(turn.id)) {
      s.terminalSpoken.add(turn.id); const thread=this.bridge.threads.get(turn.threadId);
      this.add(s,sanitize(turn.finalResponse || 'No final response was provided.',16000),'codex',spokenSummary(thread,turn));
    }
  }
  private safeRefresh(s:Session) {try {this.refresh(s);} catch {s.error='submission_storage_unavailable';}}
  private end(callId:string) {const s=this.sessions.get(callId);if(s){s.generation++;s.proposal=null;s.entries=[];s.seen.clear();this.sessions.delete(callId);}}
  close(){this.closed=true;clearInterval(this.timer);this.unsubscribe();this.events();for(const id of this.sessions.keys())this.end(id);this.connected.clear();}
}
