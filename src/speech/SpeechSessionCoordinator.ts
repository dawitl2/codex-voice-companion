import { randomUUID } from 'node:crypto';
import {basename} from 'node:path';
import { CallError, CallSessionManager, TERMINAL, type CallSession } from '../calls/CallSessionManager.js';
import type { Bridge } from '../core/Bridge.js';
import { SpeechError, type SpeechRecognitionEngine } from './SpeechRecognitionEngine.js';
import { UtteranceSegmenter, type SpeechSegment } from './UtteranceSegmenter.js';
import {sanitize} from '../utils/safe.js';
import {spokenResponse} from './SpokenSummaryFormatter.js';
export interface FinalTranscription { utteranceId:string;sequence:number;startedAt:string;endedAt:string;text:string;language:'en';final:true;processingMs:number }
interface Pending extends SpeechSegment {id:string}
interface Provisional {utteranceId:string;text:string;revision:number;processingMs:number;final:false}
interface SpeechSession {callId:string;deviceId:string;enabled:boolean;speaking:boolean;segmenter:UtteranceSegmenter;results:FinalTranscription[];sequence:number;error:string;abort:AbortController|null;job:'partial'|'final'|null;pending:Pending|null;lastFrame:number;utteranceId:string;provisional:Provisional|null;revision:number;partialAt:number;echoUntil:number}
/** Full-prefix rolling recognition; one worker, final jobs have priority. PCM never touches disk. */
export class SpeechSessionCoordinator {
 private readonly sessions=new Map<string,SpeechSession>();
 private readonly listeners=new Set<(call:CallSession,utterance:FinalTranscription)=>void>();
 private readonly activityListeners=new Set<(call:CallSession,busy:boolean)=>void>();
 private engineState='loading';private engineError='';private closed=false;private readonly unsubscribe:()=>void;
 readonly initialization:Promise<void>;
 onTranscription(f:(call:CallSession,u:FinalTranscription)=>void){this.listeners.add(f);return()=>this.listeners.delete(f);}
 onActivity(f:(call:CallSession,busy:boolean)=>void){this.activityListeners.add(f);return()=>this.activityListeners.delete(f);}
 diagnostics(){return{engine:this.engineState,error:this.engineError,sessions:this.sessions.size,recognizing:[...this.sessions.values()].filter(s=>s.abort).length,pending:[...this.sessions.values()].filter(s=>s.pending).length,listening:[...this.sessions.values()].filter(s=>s.enabled&&!s.speaking).length};}
 constructor(private readonly calls:CallSessionManager,private readonly bridge:Bridge,private readonly engine:SpeechRecognitionEngine){
  this.initialization=engine.prepare().then(()=>{this.engineState='ready';},e=>{this.engineState='unavailable';this.engineError=e instanceof SpeechError?e.code:'asr_unavailable';});
  this.unsubscribe=calls.onChange(call=>{const s=this.sessions.get(call.callId);if(!s)return;if(TERMINAL.has(call.state)||call.state!=='active')this.remove(s);else if(call.muted)this.reset(s);});
 }
 private owner(device:string,id:string){const call=this.calls.status(device,id);if(this.closed||call.state!=='active')throw new CallError('call_not_active');let s=this.sessions.get(id);if(!s){s={callId:id,deviceId:device,enabled:false,speaking:false,segmenter:new UtteranceSegmenter(),results:[],sequence:0,error:'',abort:null,job:null,pending:null,lastFrame:0,utteranceId:randomUUID(),provisional:null,revision:0,partialAt:0,echoUntil:0};this.sessions.set(id,s);}return s;}
 private activity(s:SpeechSession){try{const call=this.calls.status(s.deviceId,s.callId);const busy=!s.enabled||s.speaking||!!s.error||s.segmenter.detecting||s.job==='final'||s.pending!==null;for(const f of this.activityListeners)f({...call,deviceId:s.deviceId},busy);}catch{/* Closed/revoked sessions cannot restart countdowns. */}}
 status(device:string,id:string,after=0){const s=this.owner(device,id),call=this.calls.status(device,id);const state=s.speaking?'speaking':!s.enabled||call.muted?'paused':s.error||this.engineState==='unavailable'?'error':s.segmenter.detecting?'speech_detected':s.job==='final'?'processing':s.sequence>after?'transcript_ready':'listening';return{callId:id,state,engine:this.engineState,language:'en',error:s.error||this.engineError,utterances:s.results.filter(r=>r.sequence>after).map(r=>({...r})),provisional:s.provisional?{...s.provisional}:null};}
 listening(device:string,id:string,enabled:boolean){const s=this.owner(device,id);s.enabled=enabled;s.error='';if(!enabled)this.reset(s);this.activity(s);return this.status(device,id);}
 playback(device:string,id:string,state:string){const s=this.owner(device,id);s.speaking=state==='started';s.echoUntil=s.speaking?0:Date.now()+350;this.reset(s);this.activity(s);return this.status(device,id);}
 summary(device:string,id:string){
  this.owner(device,id);const call=this.calls.status(device,id),thread=this.bridge.store.state.threads.find(t=>t.id===call.threadId);
  const turn=call.kind==='conversation'?this.bridge.store.state.turns.filter(t=>t.threadId===call.threadId).at(-1):this.bridge.store.state.turns.find(t=>t.threadId===call.threadId&&t.id===call.turnId);
  const project=thread?sanitize(basename(thread.cwd),80):'your selected project';
  const greeting=turn&&['starting','running'].includes(turn.status)?'Connected. Codex is still working in '+project+'.':turn?.status==='awaitingApproval'?'Connected. Approval is waiting on your PC.':'Connected to Codex in '+project+'. What would you like to do?';
  return{callId:id,text:call.kind!=='conversation'&&thread&&turn?spokenResponse(thread,turn):(call.mode==='mock'?'Simulated. ':'')+greeting,mode:call.mode,taskState:turn?.status??'unavailable'};
 }

 frame(call:CallSession,pcm:Buffer,level:number){const s=this.sessions.get(call.callId);if(!s||call.deviceId!==s.deviceId||!s.enabled||s.speaking||call.muted||call.state!=='active'||this.engineState!=='ready')return;const now=Date.now();if(now<s.echoUntil)return;if(s.lastFrame&&now-s.lastFrame>500)s.segmenter.clear();s.lastFrame=now;const detecting=s.segmenter.detecting;const segment=s.segmenter.accept(pcm,level,now);if(!detecting&&s.segmenter.detecting){s.utteranceId=randomUUID();s.provisional=null;s.error='';s.partialAt=now;}
  if(segment)this.enqueue(s,{...segment,id:s.utteranceId});
  else if(s.segmenter.detecting&&!s.abort&&!s.pending&&now-s.partialAt>=1500){const snapshot=s.segmenter.snapshot();if(snapshot){s.partialAt=now;this.run(s,{...snapshot,id:s.utteranceId},'partial');}}
  if(!detecting&&s.segmenter.detecting||segment)this.activity(s);
 }
 finish(device:string,id:string){const s=this.owner(device,id);if(s.enabled&&!s.speaking){const segment=s.segmenter.finish();if(segment)this.enqueue(s,{...segment,id:s.utteranceId});}this.activity(s);return this.status(device,id);}
 private enqueue(s:SpeechSession,segment:Pending){if(s.abort){if(s.pending){segment.pcm.fill(0);s.error='recognition_queue_full';}else s.pending=segment;if(s.job==='partial')s.abort.abort();this.activity(s);return;}this.run(s,segment,'final');}
 private run(s:SpeechSession,segment:Pending,kind:'partial'|'final'){
  const abort=new AbortController();s.abort=abort;s.job=kind;if(kind==='final')s.error='';
  this.activity(s);
  void this.engine.recognize(segment.pcm,abort.signal,kind==='partial').then(result=>{
   if(abort.signal.aborted||this.sessions.get(s.callId)!==s||this.closed||!s.enabled||s.speaking)return;
   const call=this.calls.status(s.deviceId,s.callId);if(call.state!=='active'||call.muted)return;
   if(kind==='partial'){if(s.utteranceId===segment.id&&s.segmenter.detecting)s.provisional={utteranceId:segment.id,text:result.text,revision:++s.revision,processingMs:result.processingMs,final:false};return;}
   const u:FinalTranscription={utteranceId:segment.id,sequence:++s.sequence,startedAt:new Date(segment.startedAt).toISOString(),endedAt:new Date(segment.endedAt).toISOString(),text:result.text,language:'en',final:true,processingMs:result.processingMs};s.results.push(u);if(s.results.length>8)s.results.shift();if(s.provisional?.utteranceId===segment.id)s.provisional=null;for(const f of this.listeners){try{f({...call,deviceId:s.deviceId},{...u});}catch{/* Consumer failures never crash media. */}}
  }).catch(e=>{if(kind==='final'&&!abort.signal.aborted&&this.sessions.get(s.callId)===s)s.error=e instanceof SpeechError?e.code:'recognition_failed';})
  .finally(()=>{segment.pcm.fill(0);if(s.abort!==abort)return;s.abort=null;s.job=null;const pending=s.pending;s.pending=null;if(pending&&this.sessions.get(s.callId)===s&&s.enabled&&!s.speaking)this.run(s,pending,'final');else{pending?.pcm.fill(0);this.activity(s);}});
 }
 private reset(s:SpeechSession){s.segmenter.clear();s.abort?.abort();s.pending?.pcm.fill(0);s.pending=null;s.provisional=null;s.lastFrame=0;this.activity(s);}
 private remove(s:SpeechSession){this.reset(s);s.results=[];this.sessions.delete(s.callId);}
 close(){this.closed=true;this.unsubscribe();this.listeners.clear();this.activityListeners.clear();for(const s of this.sessions.values())this.remove(s);this.engine.close();}
}
