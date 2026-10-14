import test from 'node:test';
import assert from 'node:assert/strict';
import { StoredConversationResolver } from '../src/core/StoredConversationResolver.js';
import { SessionOwnership } from '../src/core/SessionOwnership.js';

test('stored discovery filters internal and test sessions and resumes the original verified ID', async t => {
  const originalCheck = SessionOwnership.prototype.check;
  SessionOwnership.prototype.check = async () => {};
  t.after(() => { SessionOwnership.prototype.check = originalCheck; });
  const rows = [
    {id:'original',name:'Fix Bluetooth reconnect',cwd:process.cwd(),model:'same-model',modelProvider:'openai',source:'vscode',status:{type:'notLoaded'},path:'original.jsonl',updatedAt:100,createdAt:50,turns:[]},
    {id:'test',name:'Acceptance test',cwd:process.cwd(),model:'same-model',modelProvider:'openai',source:'cli',status:{type:'notLoaded'},path:'test.jsonl',updatedAt:200,createdAt:50},
    {id:'agent',name:'Internal worker',cwd:process.cwd(),model:'same-model',modelProvider:'openai',source:'appServer',parentThreadId:'parent',status:{type:'notLoaded'},path:'agent.jsonl',updatedAt:300,createdAt:50}
  ];
  // Use an existing directory with a non-development basename for this focused fixture.
  for(const row of rows) row.cwd=process.env.SystemRoot || '/tmp';
  const calls:{method:string;params:Record<string,unknown>}[]=[];
  const state:{threads:Record<string,unknown>[];turns:Record<string,unknown>[]}={threads:[],turns:[]};
  const bridge={
    client:{request:async(method:string,params:Record<string,unknown>)=>{
      calls.push({method,params});
      if(method==='thread/list')return {data:rows,nextCursor:null};
      if(method==='thread/read')return {thread:rows[0]};
      if(method==='thread/resume')return {thread:rows[0],model:'same-model',modelProvider:'openai',cwd:rows[0]!.cwd,approvalPolicy:'on-request',approvalsReviewer:'user',sandbox:{type:'readOnly'}};
      return {};
    }},
    store:{state,save:()=>{}},verifyConfiguration:async()=>{},
    threads:{activeId:undefined as string|undefined,loaded:new Set<string>(),list:()=>state.threads,remember:()=>{}},
    turns:{reconcile:(id:string)=>{assert.equal(id,'original');}}
  };
  const resolver=new StoredConversationResolver(bridge as never,process.cwd(),()=>undefined,()=>false);
  t.after(()=>resolver.close());
  const discovered=await resolver.discover();
  assert.equal(discovered.find(r=>r.id==='original')?.eligible,true);
  assert.equal(discovered.find(r=>r.id==='test')?.eligible,false);
  assert.equal(discovered.find(r=>r.id==='agent')?.eligible,false);
  assert.ok(discovered.every(r=>!('path' in r)));
  await resolver.select('original');
  assert.equal(bridge.threads.activeId,'original');
  assert.equal(state.threads[0]?.id,'original');
  assert.equal(state.threads[0]?.model,'same-model');
  assert.equal(state.threads[0]?.sandbox,'read-only');
  assert.ok(calls.some(c=>c.method==='thread/resume'&&c.params.threadId==='original'));
  assert.ok(calls.every(c=>!['thread/start','turn/start'].includes(c.method)));
});
