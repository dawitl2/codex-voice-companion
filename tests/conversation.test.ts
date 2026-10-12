import test from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {resolve} from 'node:path';
import {Bridge} from '../src/core/Bridge.js';
import {MockTransport} from '../src/core/MockTransport.js';
import {CallSessionManager} from '../src/calls/CallSessionManager.js';
import {SubmissionLedger} from '../src/conversation/SubmissionLedger.js';
import {VoiceConversationController} from '../src/conversation/VoiceConversationController.js';
import {voiceIntent} from '../src/conversation/VoiceIntent.js';
import type {RegistryStorage} from '../src/remote/DeviceRegistry.js';
import {validateConversationRequest,validateConversationResponse} from '../src/conversation/ConversationProtocol.js';
function memory():RegistryStorage {let data:unknown=[];return {load:()=>structuredClone(data),save:value=>{data=structuredClone(value);}};}
async function fixture(t:test.TestContext) {
  const bridge=new Bridge('mock',new MockTransport(undefined,20),resolve('.runtime','conversation-tests',randomUUID()));t.after(()=>bridge.close());await bridge.connect();
  const threadId=await bridge.create(resolve('.'),'Controlled voice fixture'); const device=randomUUID(); let paired=true;
  const calls=new CallSessionManager(memory(),{hasDevice:id=>paired && id===device});t.after(()=>calls.close());calls.setPreferences(device,true);
  const view=calls.review(device,threadId,'existing','mock');const grant=calls.accept(device,view.callId,'owner');calls.consume(grant.token,'owner');calls.activate(device,view.callId);
  let time=Date.now();const storage=memory(),ledger=new SubmissionLedger(storage);const voice=new VoiceConversationController(bridge,calls,ledger,()=>time);t.after(()=>voice.close());voice.connection(device,1);
  const call={...view,deviceId:device,state:'active' as const};
  const say=(text:string,id=randomUUID())=>voice.transcription(call,{utteranceId:id,sequence:1,startedAt:new Date().toISOString(),endedAt:new Date().toISOString(),text,language:'en',final:true,processingMs:1});
  return {bridge,threadId,device,calls,call,voice,ledger,storage,say,status:()=>voice.status(device,call.callId),advance:(ms:number)=>{time+=ms;},revoke:()=>{paired=false;calls.revoke(device);}};
}
test('voice grammar is whole-phrase conservative and never catches embedded command words',()=>{
  assert.equal(voiceIntent('Codex, what happened with the last task?'),'status');assert.equal(voiceIntent('Yes, send it.'),'confirm');
  for(const text of ['Fix the hang up button','Update the yes label','Delete the cancel animation','Yes but change the project first']) assert.equal(voiceIntent(text),'instruction');
});
test('speech proposes exact instructions; local questions, ambiguity and duplicate transcripts never submit',async t=>{
  const f=await fixture(t);await f.say('Which project am I connected to?');assert.equal(f.bridge.store.state.turns.length,0);
  const id=randomUUID();await f.say('Review the settings screen',id);const proposal=f.status().proposal!;assert.equal(proposal.text,'Review the settings screen');
  await f.say('Review the settings screen',id);assert.equal(f.status().proposal?.id,proposal.id);assert.equal(f.ledger.list().length,0);
  await f.say('Yes but change the project first');assert.equal(f.ledger.list().length,0);assert.notEqual(f.status().proposal?.id,proposal.id);
});
test('exact voice confirmation dispatches one actual mock turn and duplicate confirmation is idempotent',async t=>{
  const f=await fixture(t);await f.say('Reply with a harmless verification phrase');const id=f.status().proposal!.id;
  await Promise.all([f.voice.confirm(f.device,f.call.callId,id),f.voice.confirm(f.device,f.call.callId,id)]);
  assert.equal(f.ledger.list().length,1);assert.ok(f.ledger.get(id)?.turnId);assert.equal(f.bridge.store.state.turns.length,1);
  assert.equal(f.bridge.store.state.turns[0]?.threadId,f.threadId);await f.voice.confirm(f.device,f.call.callId,id);assert.equal(f.bridge.store.state.turns.length,1);
});
test('cancel, expiration, disconnect and switching away and back invalidate pending authorization',async t=>{
  const f=await fixture(t);await f.say('Review this project');let id=f.status().proposal!.id;await f.say('No');await assert.rejects(f.voice.confirm(f.device,f.call.callId,id));
  await f.say('Review this project');id=f.status().proposal!.id;f.advance(45001);await assert.rejects(f.voice.confirm(f.device,f.call.callId,id));
  await f.say('Review this project');id=f.status().proposal!.id;f.voice.connection(f.device,-1);f.voice.connection(f.device,1);await assert.rejects(f.voice.confirm(f.device,f.call.callId,id));
  await f.say('Review this project');id=f.status().proposal!.id;f.bridge.threads.activeId=undefined;f.bridge.threads.activeId=f.threadId;await assert.rejects(f.voice.confirm(f.device,f.call.callId,id));assert.equal(f.bridge.store.state.turns.length,0);
});
test('wrong call/device, revoked pairing, empty speech and obvious privileged instructions fail closed',async t=>{
  const f=await fixture(t);await f.say('');assert.equal(f.status().proposal,null);await f.say('force push the repository');assert.equal(f.status().proposal,null);
  await f.say('Review this project');const id=f.status().proposal!.id;
  await assert.rejects(f.voice.confirm(randomUUID(),f.call.callId,id));await assert.rejects(f.voice.confirm(f.device,randomUUID(),id));
  await f.voice.transcription({...f.call,deviceId:randomUUID()},{utteranceId:randomUUID(),sequence:1,startedAt:new Date().toISOString(),endedAt:new Date().toISOString(),text:'Yes',language:'en',final:true,processingMs:1});
  assert.equal(f.ledger.list().length,0);f.revoke();await assert.rejects(f.voice.confirm(f.device,f.call.callId,id));
});
test('accepted work continues after hangup; a busy thread never gets an overlapping instruction',async t=>{
  const f=await fixture(t);await f.say('[mock:wait]');await f.say('Yes, send it');const row=f.ledger.list()[0]!;assert.ok(row.turnId);
  await f.say('Run another task');assert.equal(f.status().phase,'busy');assert.equal(f.ledger.list().length,1);
  f.calls.end(f.device,f.call.callId);assert.ok(f.bridge.turns.active(f.threadId));assert.equal(f.bridge.turns.get(f.threadId,row.turnId!)?.status,'running');
});
test('unknown dispatch outcome is durable, blocks resubmission and never stores instruction text',async t=>{
  const f=await fixture(t);f.bridge.send=async(_id,_text,guard)=>{guard?.();throw new Error('transport timeout');};await f.say('A private request that must remain transient');const id=f.status().proposal!.id;
  await f.voice.confirm(f.device,f.call.callId,id);assert.equal(f.ledger.get(id)?.state,'unknown');assert.doesNotMatch(JSON.stringify(f.storage.load()),/private request/);
  await f.say('Another request');assert.equal(f.status().phase,'busy');await f.voice.confirm(f.device,f.call.callId,id);assert.equal(f.ledger.list().length,1);
  const reloaded=new SubmissionLedger(f.storage);assert.equal(reloaded.uncertain(f.threadId),true);
});
test('ending a call before the final dispatch guard prevents submission',async t=>{
  const f=await fixture(t);let entered!:()=>void,release!:()=>void;const ready=new Promise<void>(r=>entered=r),gate=new Promise<void>(r=>release=r);
  f.bridge.send=async(_id,_text,guard)=>{entered();await gate;guard?.();throw new Error('unreachable');};await f.say('Review this project');const id=f.status().proposal!.id;
  const pending=f.voice.confirm(f.device,f.call.callId,id);await ready;f.calls.end(f.device,f.call.callId);release();await pending;assert.equal(f.ledger.get(id)?.state,'rejected');assert.equal(f.bridge.store.state.turns.length,0);
});
test('conversation protocol rejects caller-supplied targets, arbitrary operations and unbounded replies',()=>{
  const callId=randomUUID(),confirmationId=randomUUID();validateConversationRequest('conversation.confirm',{callId,confirmationId});
  assert.throws(()=>validateConversationRequest('conversation.confirm',{callId,confirmationId,threadId:'other'}));
  assert.throws(()=>validateConversationRequest('conversation.exec',{callId,command:'arbitrary'}));
  assert.throws(()=>validateConversationRequest('conversation.correct',{callId,confirmationId,text:''}));
  const status={callId,phase:'ready',error:'',selection:null,proposal:null,submission:null,entries:[]};validateConversationResponse('conversation.status.response',status);
  assert.throws(()=>validateConversationResponse('conversation.status.response',{...status,entries:[{id:randomUUID(),sequence:1,kind:'codex',text:'x'.repeat(16001),spoken:'Brief reply',timestamp:new Date().toISOString()}]}));
});
test('a corrected instruction gets fresh authorization; the old confirmation cannot execute',async t=>{
  const f=await fixture(t);await f.say('Review the first screen');const original=f.status().proposal!;
  f.voice.correct(f.device,f.call.callId,original.id,'Review the second screen');const corrected=f.status().proposal!;
  assert.notEqual(corrected.id,original.id);assert.equal(corrected.text,'Review the second screen');await assert.rejects(f.voice.confirm(f.device,f.call.callId,original.id));assert.equal(f.ledger.list().length,0);
});
test('terminal response is genuine turn output, separated from deterministic status and missing output is honest',async t=>{
  const f=await fixture(t);await f.say('A harmless reply');await f.say('Yes');
  await new Promise(resolve=>setTimeout(resolve,100));const status=f.status();assert.equal(status.submission?.state,'completed');
  const entry=status.entries.find(e=>e.kind==='codex')!;assert.match(entry.text,/MOCK/);assert.match(entry.spoken,/Simulated task/);assert.doesNotMatch(entry.spoken,/tests passed/i);
});
