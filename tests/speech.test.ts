import test from 'node:test';
import assert from 'node:assert/strict';
import { UtteranceSegmenter } from '../src/speech/UtteranceSegmenter.js';
import { pcmWav, WhisperCppEngine } from '../src/speech/SpeechRecognitionEngine.js';
import { spokenSummary } from '../src/speech/SpokenSummaryFormatter.js';
import { validateSpeechRequest, validateSpeechResponse } from '../src/speech/SpeechProtocol.js';
import { randomUUID } from 'node:crypto';
const pcm = Buffer.alloc(640); for (let i=0; i<320; i++) pcm.writeInt16LE(1000, i*2);
test('VAD buffers real frames, retains pre-roll, finishes after silence and clears consumed samples', () => {
  const vad = new UtteranceSegmenter(); for (let i=0;i<8;i++) assert.equal(vad.accept(Buffer.alloc(640),0,i*20),null);
  for (let i=0;i<40;i++) assert.equal(vad.accept(pcm,.04,160+i*20),null);
  let segment; for(let i=0;i<60;i++) segment=vad.accept(Buffer.alloc(640),0,960+i*20);
  assert.ok(segment); assert.ok(segment.pcm.includes(pcm)); assert.equal(vad.detecting,false); assert.equal(vad.finish(),null); segment.pcm.fill(0);
});
test('silent and too-short speech produces no utterance; maximum duration is bounded', () => {
  const vad = new UtteranceSegmenter(); for(let i=0;i<2000;i++) assert.equal(vad.accept(Buffer.alloc(640),0,i*20),null);
  for(let i=0;i<10;i++) vad.accept(pcm,.04,40000+i*20); assert.equal(vad.finish(),null);
  let segment=null; for(let i=0;i<1000;i++) {const result=vad.accept(pcm,.04,41000+i*20); if(result) segment=result;}
  assert.ok(segment); assert.ok(segment.pcm.length<=1000*640); segment.pcm.fill(0); vad.clear();
});
test('WAV encoder preserves mono16k PCM and rejects unbounded or malformed input', () => {
  const wav=pcmWav(pcm); assert.equal(wav.toString('ascii',0,4),'RIFF'); assert.equal(wav.readUInt32LE(24),16000); assert.equal(wav.readUInt16LE(22),1); assert.deepEqual(wav.subarray(44),pcm);
  assert.throws(()=>pcmWav(Buffer.alloc(641))); assert.throws(()=>pcmWav(Buffer.alloc(640*1001))); wav.fill(0);
});
test('missing ASR installation reports a fixed error instead of a cloud or subprocess fallback', async()=> {
  const engine=new WhisperCppEngine('C:/definitely-absent-codex-voice-speech'); await assert.rejects(engine.prepare(),/asr_engine_missing/); engine.close();
});
test('speech protocol rejects cross-call selectors, provisional guesses and unbounded transcript text', ()=> {
  const callId=randomUUID(); validateSpeechRequest('speech.listening',{callId,enabled:true});
  assert.throws(()=>validateSpeechRequest('speech.listening',{callId,enabled:true,deviceId:randomUUID()}));
  assert.throws(()=>validateSpeechRequest('speech.status.request',{callId,afterSequence:-1}));
  const status={callId,state:'listening',engine:'ready',language:'en',error:'',utterances:[]}; validateSpeechResponse('speech.status.response',status);
  assert.throws(()=>validateSpeechResponse('speech.status.response',{...status,utterances:[{utteranceId:randomUUID(),sequence:1,startedAt:new Date().toISOString(),endedAt:new Date().toISOString(),text:'x'.repeat(2001),language:'en',final:true,processingMs:1}]}));
});
test('summary uses actual structured turn state and omits private paths and invented test claims', ()=> {
  const thread={id:'t',cwd:'C:/private',label:'private',createdAt:new Date().toISOString(),model:'local',status:'ready' as const,mode:'real' as const};
  const turn={id:'u',threadId:'t',startedAt:new Date().toISOString(),status:'completed' as const,finalResponse:'Updated C:\\Users\\private\\secret.txt. api_key=sk-secretvalue123',commandResults:[],completionEmitted:true};
  const summary=spokenSummary(thread,turn); assert.match(summary,/does not independently verify/); assert.doesNotMatch(summary,/C:\\Users|sk-secret/); assert.doesNotMatch(summary,/tests passed/i);
  assert.match(spokenSummary({...thread,mode:'mock'},turn),/^Simulated task/);
});

test('call speech authorization rejects wrong owners and cancels in-flight recognition at termination', async t=> {
  const {CallSessionManager}=await import('../src/calls/CallSessionManager.js'); const {SpeechSessionCoordinator}=await import('../src/speech/SpeechSessionCoordinator.js');
  const device=randomUUID(); let stored:unknown=[]; const manager=new CallSessionManager({load:()=>stored,save:v=>{stored=structuredClone(v)}},{hasDevice:id=>id===device}); t.after(()=>manager.close());
  manager.setPreferences(device,true); const offer=manager.offer(device,{eventId:'a'.repeat(64),threadId:'t',turnId:'u',kind:'completed',mode:'mock',occurredAt:new Date().toISOString(),expiresAt:new Date(Date.now()+120000).toISOString()})!;
  const grant=manager.accept(device,offer.callId,'owner'); manager.consume(grant.token,'owner'); manager.activate(device,offer.callId);
  let aborted=false;
  const engine={prepare:async()=>{},recognize:(_pcm:Buffer,signal:AbortSignal)=>new Promise<never>((_resolve,reject)=>signal.addEventListener('abort',()=>{aborted=true;reject(new Error('cancelled'))})),close:()=>{}};
  const speech=new SpeechSessionCoordinator(manager,{store:{state:{threads:[],turns:[]}}} as never,engine); t.after(()=>speech.close()); await speech.initialization;
  assert.throws(()=>speech.status(randomUUID(),offer.callId)); speech.listening(device,offer.callId,true);
  const call={...offer,deviceId:device,state:"active" as const}; for(let i=0;i<40;i++) speech.frame(call,pcm,.04); speech.finish(device,offer.callId);
  assert.equal(speech.status(device,offer.callId).state,'processing'); manager.end(device,offer.callId); assert.equal(aborted,true); assert.throws(()=>speech.status(device,offer.callId),/call_not_active/);
});

test('half-duplex playback cancels active and queued utterances and prevents echo recognition', async t=> {
  const {CallSessionManager}=await import('../src/calls/CallSessionManager.js'); const {SpeechSessionCoordinator}=await import('../src/speech/SpeechSessionCoordinator.js');
  const device=randomUUID(); let stored:unknown=[];
  const manager=new CallSessionManager({load:()=>stored,save:v=>{stored=structuredClone(v)}},{hasDevice:id=>id===device}); t.after(()=>manager.close());
  manager.setPreferences(device,true); const view=manager.review(device,'t','u','real'); const grant=manager.accept(device,view.callId,'owner');
  manager.consume(grant.token,'owner'); manager.activate(device,view.callId);
  let jobs=0, aborted=false; const buffers:Buffer[]=[];
  const engine={prepare:async()=>{},recognize:(audio:Buffer,signal:AbortSignal)=>{ jobs++; buffers.push(audio); return new Promise<never>((_resolve,reject)=>signal.addEventListener('abort',()=>{aborted=true;reject(new Error('cancelled'))})); },close:()=>{}};
  const speech=new SpeechSessionCoordinator(manager,{store:{state:{threads:[],turns:[]}}} as never,engine); t.after(()=>speech.close()); await speech.initialization;
  speech.listening(device,view.callId,true); const call={...view,deviceId:device,state:'active' as const};
  const submit=()=>{ for(let i=0;i<40;i++) speech.frame(call,pcm,.04); speech.finish(device,view.callId); };
  for(let i=0;i<40;i++) speech.frame({...call,deviceId:randomUUID()},pcm,.04); speech.finish(device,view.callId); assert.equal(jobs,0);
  submit(); submit(); submit(); assert.equal(jobs,1); assert.equal(speech.status(device,view.callId).error,'recognition_queue_full');
  speech.playback(device,view.callId,'started'); assert.equal(aborted,true); submit(); assert.equal(jobs,1);
  await new Promise(resolve=>setImmediate(resolve)); assert.ok(buffers[0]?.every(sample=>sample===0)); assert.equal(speech.status(device,view.callId).utterances.length,0);
  assert.equal(speech.status(device,view.callId).state,'speaking'); speech.playback(device,view.callId,'interrupted');
  manager.mute(device,view.callId,true); assert.equal(speech.status(device,view.callId).state,'paused');
});

test('rolling recognition exposes genuine provisional output, never final-dispatches it, and clears PCM',async t=>{
 const {CallSessionManager}=await import('../src/calls/CallSessionManager.js');const {SpeechSessionCoordinator}=await import('../src/speech/SpeechSessionCoordinator.js');
 const device=randomUUID();let stored:unknown=[];const manager=new CallSessionManager({load:()=>stored,save:v=>stored=structuredClone(v)},{hasDevice:id=>id===device});t.after(()=>manager.close());manager.setPreferences(device,true);
 const view=manager.review(device,'t','u','real'),grant=manager.accept(device,view.callId,'owner');manager.consume(grant.token,'owner');manager.activate(device,view.callId);
 const buffers:Buffer[]=[],kinds:boolean[]=[];const engine={prepare:async()=>{},recognize:async(audio:Buffer,_signal:AbortSignal,partial=false)=>{buffers.push(audio);kinds.push(partial);return{text:partial?'Actual provisional words':'Actual finalized words',processingMs:12,language:'en' as const,loadMs:1};},close:()=>{}};
 const speech=new SpeechSessionCoordinator(manager,{store:{state:{threads:[],turns:[]}}}as never,engine);t.after(()=>speech.close());await speech.initialization;speech.listening(device,view.callId,true);let finals=0;speech.onTranscription(()=>finals++);
 const now=Date.now;let time=now();Date.now=()=>time;
 try{for(let i=0;i<90;i++){time+=20;speech.frame({...view,deviceId:device,state:'active'},pcm,.04);}await new Promise(r=>setImmediate(r));
 const partial=speech.status(device,view.callId).provisional;assert.equal(partial?.text,'Actual provisional words');assert.equal(partial?.final,false);assert.equal(finals,0);assert.equal(kinds[0],true);assert.ok(buffers[0]?.every(x=>x===0));
 speech.finish(device,view.callId);await new Promise(r=>setImmediate(r));assert.equal(finals,1);assert.equal(kinds.at(-1),false);assert.equal(speech.status(device,view.callId).provisional,null);assert.equal(speech.status(device,view.callId).utterances[0]?.utteranceId,partial?.utteranceId);assert.ok(buffers.every(b=>b.every(x=>x===0)));
 }finally{Date.now=now;}
});

test('outgoing greeting is brief while an incoming callback reads only its bound actual result',async t=>{
 const {CallSessionManager}=await import('../src/calls/CallSessionManager.js');const {SpeechSessionCoordinator}=await import('../src/speech/SpeechSessionCoordinator.js');
 const device=randomUUID();let stored:unknown=[];
 const calls=new CallSessionManager({load:()=>stored,save:v=>{stored=structuredClone(v)}},{hasDevice:id=>id===device});t.after(()=>calls.close());calls.setPreferences(device,true);
 const thread={id:'t',cwd:process.cwd(),mode:'real'};const turn={id:'u',threadId:'t',status:'completed',finalResponse:'The reconnect change is ready.'};
 const speech=new SpeechSessionCoordinator(calls,{store:{state:{threads:[thread],turns:[turn,{...turn,id:'newer',finalResponse:'Unrelated newer result'}]}}} as never,{prepare:async()=>{},recognize:async()=>{throw Error('unused')},close:()=>{}});t.after(()=>speech.close());
 const activate=(v:{callId:string})=>{const grant=calls.accept(device,v.callId,'owner');calls.consume(grant.token,'owner');calls.activate(device,v.callId);};
 const outgoing=calls.outgoing(device,'t','u','real',randomUUID());activate(outgoing);
 assert.match(speech.summary(device,outgoing.callId).text,/Connected to Codex/);
 assert.doesNotMatch(speech.summary(device,outgoing.callId).text,/Unrelated|reconnect change/);calls.end(device,outgoing.callId);
 const incoming=calls.review(device,'t','u','real');activate(incoming);
 assert.equal(speech.summary(device,incoming.callId).text,'The reconnect change is ready.');
});
