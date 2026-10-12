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
  let segment; for(let i=0;i<35;i++) segment=vad.accept(Buffer.alloc(640),0,960+i*20);
  assert.ok(segment); assert.ok(segment.pcm.includes(pcm)); assert.equal(vad.detecting,false); assert.equal(vad.finish(),null); segment.pcm.fill(0);
});
test('silent and too-short speech produces no utterance; maximum duration is bounded', () => {
  const vad = new UtteranceSegmenter(); for(let i=0;i<2000;i++) assert.equal(vad.accept(Buffer.alloc(640),0,i*20),null);
  for(let i=0;i<10;i++) vad.accept(pcm,.04,40000+i*20); assert.equal(vad.finish(),null);
  let segment=null; for(let i=0;i<600;i++) {const result=vad.accept(pcm,.04,41000+i*20); if(result) segment=result;}
  assert.ok(segment); assert.ok(segment.pcm.length<=600*640); segment.pcm.fill(0); vad.clear();
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
