import test from 'node:test';
import assert from 'node:assert/strict';
import {generateKeyPairSync,randomUUID,sign} from 'node:crypto';
import {resolve} from 'node:path';
import {Bridge} from '../src/core/Bridge.js';
import {MockTransport} from '../src/core/MockTransport.js';
import {TaskControl} from '../src/control/TaskControl.js';
import {SubmissionLedger} from '../src/conversation/SubmissionLedger.js';
import type {RegistryStorage} from '../src/remote/DeviceRegistry.js';
import {parseClient,envelope,encode} from '../src/remote/Protocol.js';
import {voiceIntent} from '../src/conversation/VoiceIntent.js';
import {CallSessionManager} from '../src/calls/CallSessionManager.js';
import {VoiceConversationController} from '../src/conversation/VoiceConversationController.js';
function memory():RegistryStorage{let raw:unknown=[];return {load:()=>structuredClone(raw),save:v=>{raw=structuredClone(v);}};}
const wait=()=>new Promise(r=>setTimeout(r,45));
async function fixture(t:test.TestContext,approvalTimeout=60000){const transport=new MockTransport(undefined,10),bridge=new Bridge('mock',transport,resolve('.runtime','control-tests',randomUUID()),'read-only',30000,approvalTimeout);
  await bridge.connect();const threadId=await bridge.create(resolve('.'),'Step 9 simulated controls'),device=randomUUID();let paired=true,time=Date.now();const ledger=new SubmissionLedger(memory()),store=memory();
  const c=new TaskControl(bridge,ledger,store,id=>paired&&id===device,()=>time);c.connection(device,1);t.after(async()=>{c.close();await bridge.close();});
  return {bridge,transport,c,threadId,device,ledger,store,advance:(n:number)=>time+=n,revoke:()=>{paired=false;c.revoke(device);},start:async(text='[mock:wait]')=>{const id=await bridge.send(threadId,text);await wait();return id;},finish:(id:string,status='completed')=>transport.emit({method:'turn/completed',params:{threadId,turn:{id,status,items:[],error:null}}})};}
test('confirmed interruption is bound, deduplicated and waits for authoritative terminal event',async t=>{const f=await fixture(t),turn=await f.start(),op=f.c.propose(f.device,f.threadId,turn,'interrupt');
  assert.equal(f.transport.sent.filter(m=>m.method==='turn/interrupt').length,0);
  await assert.rejects(f.c.confirm(randomUUID(),f.threadId,op.id));await assert.rejects(f.c.confirm(f.device,'wrong',op.id));
  await Promise.all([f.c.confirm(f.device,f.threadId,op.id),f.c.confirm(f.device,f.threadId,op.id)]);
  assert.equal(f.transport.sent.filter(m=>m.method==='turn/interrupt').length,1);assert.equal(f.c.status(f.device,f.threadId).operations[0]?.state,'requested');
  await wait();assert.equal(f.c.status(f.device,f.threadId).operations[0]?.state,'interrupted');
});
test('expired, terminal, switched and disconnected interruptions cannot target a newer turn',async t=>{const f=await fixture(t),turn=await f.start();let op=f.c.propose(f.device,f.threadId,turn,'interrupt');f.advance(45001);await assert.rejects(f.c.confirm(f.device,f.threadId,op.id));
  op=f.c.propose(f.device,f.threadId,turn,'interrupt');f.bridge.threads.activeId=undefined;f.bridge.threads.activeId=f.threadId;await assert.rejects(f.c.confirm(f.device,f.threadId,op.id));
  op=f.c.propose(f.device,f.threadId,turn,'interrupt');f.c.connection(f.device,-1);f.c.connection(f.device,1);await f.c.confirm(f.device,f.threadId,op.id);assert.equal(f.transport.sent.filter(m=>m.method==='turn/interrupt').length,0);
});
test('one confirmed queue dispatches once only after successful original completion and clears stored input',async t=>{const f=await fixture(t),turn=await f.start(),op=f.c.propose(f.device,f.threadId,turn,'queue','Controlled queued text');
  await f.c.confirm(f.device,f.threadId,op.id);assert.match(JSON.stringify(f.store.load()),/Controlled queued text/);
  assert.throws(()=>f.c.propose(f.device,f.threadId,turn,'queue','Another task'));f.finish(turn);await wait();await wait();
  assert.equal(f.transport.sent.filter(m=>m.method==='turn/start').length,2);assert.ok(f.ledger.get(op.id)?.turnId);assert.doesNotMatch(JSON.stringify(f.store.load()),/Controlled queued text/);
  f.finish(turn);await f.c.confirm(f.device,f.threadId,op.id);await wait();assert.equal(f.transport.sent.filter(m=>m.method==='turn/start').length,2);
});
test('queue cancellation, failure, expiry, approval, disconnect and restart never submit',async t=>{
  for(const reason of ['cancel','failed','expire','approval','disconnect','restart']){const f=await fixture(t),turn=await f.start(),op=f.c.propose(f.device,f.threadId,turn,'queue','Private confirmed follow-up');await f.c.confirm(f.device,f.threadId,op.id);
    if(reason==='cancel')f.c.cancel(f.device,f.threadId,op.id);else if(reason==='failed')f.finish(turn,'failed');else if(reason==='expire')f.advance(300001);else if(reason==='approval')f.bridge.turns.approvalState(f.threadId,turn,true);else if(reason==='disconnect')f.c.connection(f.device,-1);else {f.c.close();const reloaded=new TaskControl(f.bridge,f.ledger,f.store,()=>true);reloaded.close();}
    if(reason!=='restart')try{f.c.status(f.device,f.threadId);}catch{}await wait();f.finish(turn);await wait();assert.equal(f.transport.sent.filter(m=>m.method==='turn/start').length,1);assert.doesNotMatch(JSON.stringify(f.store.load()),/Private confirmed/);
  }
});
test('unknown queued acknowledgement blocks replay through the protected Step 8 ledger',async t=>{const f=await fixture(t),turn=await f.start(),op=f.c.propose(f.device,f.threadId,turn,'queue','Controlled follow-up');await f.c.confirm(f.device,f.threadId,op.id);
  let sends=0;f.bridge.send=async(_id,_text,guard)=>{guard?.();sends++;throw new Error('lost acknowledgement');};f.finish(turn);await wait();await wait();assert.equal(sends,1);assert.equal(f.ledger.get(op.id)?.state,'unknown');assert.equal(f.ledger.uncertain(f.threadId),true);assert.doesNotMatch(JSON.stringify(f.store.load()),/Controlled follow-up/);
});
test('desktop and phone negative decisions use one arbiter; timeout and server resolution remain distinct',async t=>{const f=await fixture(t),turn=await f.start('[mock:approval]'),a=f.bridge.approvals.list()[0]!;
  const status=f.c.decide(f.device,f.threadId,a.id,randomUUID(),'decline',null,null);assert.equal(status.approvals[0]?.status,'declined');assert.equal(status.approvals[0]?.serverConfirmed,false);
  assert.throws(()=>f.bridge.approvals.resolve(a.id,true));f.transport.emit({method:'serverRequest/resolved',params:{threadId:f.threadId,requestId:a.requestId}});assert.equal(f.c.status(f.device,f.threadId).approvals[0]?.serverConfirmed,true);assert.equal(turn,a.turnId);
});
test('positive approval requires desktop nomination, trusted exact reviewer key and a fresh bound signature',async t=>{const f=await fixture(t);await f.start('[mock:approval]');const a=f.bridge.approvals.list()[0]!,pair=generateKeyPairSync('ec',{namedCurve:'prime256v1'}),key=pair.publicKey.export({format:'der',type:'spki'}).toString('base64');
  f.c.register(f.device,key);const transactionId=randomUUID();assert.throws(()=>f.c.challenge(f.device,f.threadId,a.id,transactionId));f.bridge.approvals.nominate(a.id,resolve('.'));assert.throws(()=>f.c.challenge(f.device,f.threadId,a.id,transactionId));
  f.c.trust(f.device,f.c.reviewerList()[0]!.fingerprint);const challenge=f.c.challenge(f.device,f.threadId,a.id,transactionId),signature=sign('sha256',Buffer.from(challenge.message),pair.privateKey).toString('base64');
  assert.throws(()=>f.c.decide(f.device,f.threadId,a.id,randomUUID(),'accept',challenge.id,signature));assert.throws(()=>f.c.decide(f.device,f.threadId,a.id,transactionId,'accept',challenge.id,'AAAA'));
  f.c.decide(f.device,f.threadId,a.id,transactionId,'accept',challenge.id,signature);f.c.decide(f.device,f.threadId,a.id,transactionId,'accept',challenge.id,signature);
  assert.equal(f.transport.sent.filter(m=>m.id===a.requestId&&m.result).length,1);assert.deepEqual(f.transport.sent.find(m=>m.id===a.requestId&&m.result)?.result,{decision:'accept'});
  assert.throws(()=>f.c.decide(f.device,f.threadId,a.id,transactionId,'decline',null,null));
});
test('permission expansion, file-change without diff and unknown command kinds remain desktop-only',async t=>{const f=await fixture(t),turn=await f.start();for(const extra of [{networkApprovalContext:{host:'example.com',protocol:'https'}},{kind:'writeStdin'},{additionalPermissions:{network:{enabled:true}}},{unexpected:'unsafe'}]){
  f.transport.emit({id:randomUUID(),method:'item/commandExecution/requestApproval',params:{threadId:f.threadId,turnId:turn,itemId:randomUUID(),command:'echo MOCK',cwd:resolve('.'),...extra}});const a=f.bridge.approvals.list().at(-1)!;assert.throws(()=>f.bridge.approvals.nominate(a.id,resolve('.')));
  }
});
test('existing approval deadline cannot be extended by phone review or late decisions',async t=>{const f=await fixture(t,65);await f.start('[mock:approval]');const a=f.bridge.approvals.list()[0]!;await new Promise(r=>setTimeout(r,80));assert.throws(()=>f.c.decide(f.device,f.threadId,a.id,randomUUID(),'decline',null,null));assert.equal(f.bridge.store.state.approvals[0]?.status,'expired');});
test('pending approval remains visible ahead of retained desktop history',async t=>{const f=await fixture(t);await f.start('[mock:approval]');const a=f.bridge.approvals.list()[0]!;for(let i=0;i<12;i++)f.bridge.store.state.approvals.push({...a,id:randomUUID(),requestId:'old-'+i,status:'declined',requestedAt:new Date(Date.now()-100000-i).toISOString()});f.bridge.store.save();const status=f.c.status(f.device,f.threadId);assert.equal(status.approvals.length,8);assert.equal(status.approvals.at(-1)?.id,a.id);assert.equal(status.approvals.at(-1)?.preview,'echo MOCK');});
test('server resolution binds to the current transport request when retained RPC IDs repeat',async t=>{const f=await fixture(t),turn=await f.start(),oldId=randomUUID();f.bridge.store.state.approvals.push({id:oldId,requestId:7,method:'item/commandExecution/requestApproval',threadId:f.threadId,turnId:turn,summary:'Old fixture history',requestedAt:new Date(Date.now()-100000).toISOString(),expiresAt:new Date(Date.now()-40000).toISOString(),status:'declined'});
  f.transport.emit({id:7,method:'item/commandExecution/requestApproval',params:{threadId:f.threadId,turnId:turn,itemId:randomUUID(),command:'echo MOCK',cwd:resolve('.'),startedAtMs:Date.now()}});const current=f.bridge.approvals.list()[0]!;f.transport.emit({method:'serverRequest/resolved',params:{threadId:f.threadId,requestId:7}});assert.equal(f.bridge.approvals.review(current.id).status,'resolved');assert.equal(f.bridge.approvals.review(current.id).serverConfirmed,true);assert.equal(f.bridge.approvals.review(oldId).serverConfirmed,false);
});
test('strict protocol and whole-phrase grammar reject arbitrary grants and ambiguous interruption',()=>{
  const threadId='managed',turnId='current';parseClient(JSON.stringify(envelope('control.interrupt.propose',{threadId,turnId})));
  for(const payload of [{threadId,turnId,command:'arbitrary'},{threadId,turnId,sandbox:'danger-full-access'}])assert.throws(()=>parseClient(JSON.stringify(envelope('control.interrupt.propose',payload))));
  assert.throws(()=>parseClient(JSON.stringify(envelope('control.approval.decide',{threadId,approvalId:randomUUID(),transactionId:randomUUID(),decision:'acceptForSession',challengeId:null,signature:null}))));
  assert.equal(voiceIntent('Stop'),'ambiguous');assert.equal(voiceIntent('Stop speaking'),'stop');assert.equal(voiceIntent('Stop the current task'),'interrupt');assert.equal(voiceIntent('Fix the stop the current task label'),'instruction');
  encode('control.error',{code:'authorization_failed'});
});
test('voice controls confirm interruption and negative denial; vague stop and approval phrases grant nothing',async t=>{
  const f=await fixture(t),turn=await f.start(),calls=new CallSessionManager(memory(),{hasDevice:id=>id===f.device});calls.setPreferences(f.device,true);
  const offer=calls.review(f.device,f.threadId,turn,'mock'),grant=calls.accept(f.device,offer.callId,'owner');calls.consume(grant.token,'owner');calls.activate(f.device,offer.callId);
  const voice=new VoiceConversationController(f.bridge,calls,f.ledger,Date.now,f.c);voice.connection(f.device,1);t.after(()=>{voice.close();calls.close();});
  const say=(text:string)=>voice.transcription({...calls.status(f.device,offer.callId),deviceId:f.device},{utteranceId:randomUUID(),sequence:1,startedAt:new Date().toISOString(),endedAt:new Date().toISOString(),text,language:'en',final:true,processingMs:1});
  await say('Stop');await say('Approve it');await say('Yes');assert.equal(f.transport.sent.filter(m=>m.method==='turn/interrupt').length,0);assert.equal(f.c.status(f.device,f.threadId).operations.length,0);
  await say('Stop the current task');assert.equal(f.c.status(f.device,f.threadId).operations.at(-1)?.state,'proposed');await say('Yes');await wait();assert.equal(f.bridge.turns.get(f.threadId,turn)?.status,'interrupted');assert.equal(calls.status(f.device,offer.callId).state,'active');
  await f.start('[mock:approval]');await say('Deny the pending approval');assert.equal(f.bridge.approvals.list().length,1);await say('Yes');await wait();assert.equal(f.bridge.approvals.list().length,0);
  assert.equal(f.transport.sent.filter(m=>m.result&&(m.result as {decision?:string}).decision==='accept').length,0);
});
