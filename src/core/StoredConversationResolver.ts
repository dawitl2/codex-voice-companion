import {basename,isAbsolute} from 'node:path';
import type {Bridge} from './Bridge.js';
import {SessionOwnership} from './SessionOwnership.js';
import {projectDirectory,samePath,disabledIntegrations,type Sandbox} from '../config/settings.js';
import {identifier,object,sanitize,requiredString,terminal} from '../utils/safe.js';
export interface StoredConversation {id:string;label:string;project:string;cwd:string;model:string;source:string;updatedAt:string;eligible:boolean;reason:string;path:string}
export const developmentConversation=(name:string,cwd:string)=>/[\\/]\.(?:runtime|cache)(?:[\\/]|$)/i.test(cwd)||/\b(?:final delivery|acceptance|controlled (?:test|fixture)|(?:step|stage)[-_ ]*\d+|simulated|mock fixture|captures)\b/i.test(name+' '+basename(cwd));
/** Official stored thread IDs only. Discovery never creates inference or rewrites session files. */
export class StoredConversationResolver {
  private rows:StoredConversation[]=[];diagnostic='Discover stored Codex conversations';private resolving=false;
  private readonly ownership:SessionOwnership;
  private monitor:NodeJS.Timeout|undefined;private polling=false;private closed=false;
  private readonly observed=new Map<string,Map<string,string>>();
  private readonly fingerprints=new Map<string,string>();
  constructor(private readonly bridge:Bridge,assets:string,pid:()=>number|undefined,private readonly locked:()=>boolean){
    this.ownership=new SessionOwnership(assets,pid);bridge.ownershipGuard=async t=>{if(!t.rolloutPath)throw Error('Original session path is unavailable');await this.ownership.check(t.rolloutPath);};
  }
  list(){return this.rows.map(({path:_path,...row})=>row);}
  async discover(){
    const rows=new Map<string,StoredConversation>();let cursor:string|null=null;
    for(let page=0;page<3;page++){
      const response=object(await this.bridge.client.request('thread/list',{limit:50,sortKey:'updated_at',sortDirection:'desc',sourceKinds:['cli','vscode','appServer'],archived:false,cursor}));
      if(!Array.isArray(response.data))throw Error('Stored conversation list unavailable');
      for(const raw of response.data){try{const t=object(raw),id=identifier(t.id);if(rows.has(id))continue;
        const cwd=requiredString(t.cwd);if(!isAbsolute(cwd))continue;const source=typeof t.source==='string'?t.source:'internal';
        const label=sanitize(typeof t.name==='string'&&t.name.trim()?t.name:typeof t.preview==='string'&&t.preview.trim()?t.preview.split('\n')[0]:basename(cwd)+' · '+id.slice(0,8),80);
        const model=typeof t.model==='string'?t.model:'';let reason='';
        if(!['cli','vscode','appServer'].includes(source)||t.parentThreadId||t.ephemeral===true)reason='Internal or ephemeral session';
        else if(developmentConversation(label,cwd))reason='Development conversation excluded from automatic selection';
        else if(!model||t.modelProvider!=='openai')reason='Original supported model metadata unavailable';
        else if(object(t.status).type==='active'||object(t.status).type==='systemError')reason='Original session active or unavailable';
        else {try{projectDirectory(cwd);}catch{reason='Original project is unavailable on this PC';}}
        if(typeof t.path!=='string')reason='Original stored resource path unavailable';
        rows.set(id,{id,label,project:sanitize(basename(cwd),80),cwd,model,source,updatedAt:new Date(Number(t.updatedAt)*1000).toISOString(),eligible:!reason,reason,path:typeof t.path==='string'?t.path:''});
      }catch{/* Invalid records cannot become targets. */}}
      cursor=typeof response.nextCursor==='string'?response.nextCursor:null;if(!cursor)break;
    }
    this.rows=[...rows.values()].sort((a,b)=>Date.parse(b.updatedAt)-Date.parse(a.updatedAt));return this.list();
  }
  async select(id:string){
    if(this.locked()||this.resolving||this.bridge.store.state.turns.some(t=>!terminal(t.status)&&this.bridge.threads.loaded.has(t.threadId)))throw Error('Finish the current call, instruction or task before changing conversation');
    const row=this.rows.find(r=>r.id===id);if(!row?.eligible)throw Error(row?.reason??'Refresh stored conversations first');
    if(this.bridge.threads.list().some(t=>t.id===id)){await this.bridge.select(id);return;}
    this.resolving=true;const previous=this.bridge.threads.activeId;let resumed=false,imported=false;
    try{
      const original=object(object(await this.bridge.client.request('thread/read',{threadId:id,includeTurns:true})).thread);
      if(original.id!==id||!samePath(requiredString(original.cwd),row.cwd)||original.model!==row.model||!Array.isArray(original.turns)||original.turns.some(raw=>object(raw).status==='inProgress'))throw Error('Stored session changed or is still working. Release it in the original client.');
      await this.ownership.check(row.path);await this.bridge.verifyConfiguration(row.cwd);
      // Omit model/sandbox overrides on first handoff: restore actual original settings.
      const safe={notify:[],...disabledIntegrations(row.cwd),features:{apps:false,hooks:false,remote_plugin:false},service_tier:'default'};
      const response=object(await this.bridge.client.request('thread/resume',{threadId:id,modelProvider:'openai',serviceTier:'default',approvalPolicy:'on-request',approvalsReviewer:'user',config:safe}));resumed=true;
      const wire=object(response.thread),policy=object(response.sandbox);
      const sandbox:Sandbox=policy.type==='readOnly'?'read-only':policy.type==='workspaceWrite'?'workspace-write':(()=>{throw Error('Original sandbox is not supported safely; unrestricted sessions cannot be adopted');})();
      if(policy.networkAccess===true||sandbox==='workspace-write'&&(!Array.isArray(policy.writableRoots)||policy.writableRoots.some(r=>typeof r!=='string'||!samePath(r,row.cwd))))throw Error('Original permissions exceed this companion’s project-only, no-network boundary');
      if(wire.id!==id||response.model!==row.model||response.modelProvider!=='openai'||response.approvalPolicy!=='on-request'||response.approvalsReviewer!=='user'||!samePath(requiredString(response.cwd),row.cwd)||!samePath(requiredString(wire.cwd),row.cwd))throw Error('Original conversation configuration changed');
      if(!Array.isArray(wire.turns))throw Error('Original conversation history unavailable');
      this.bridge.store.state.threads.push({id,cwd:row.cwd,label:row.label,model:row.model,status:'ready',mode:'real',createdAt:new Date(Number(original.createdAt)*1000).toISOString(),updatedAt:row.updatedAt,original:true,source:row.source,rolloutPath:row.path,sandbox});imported=true;
      this.bridge.threads.loaded.add(id);this.bridge.threads.activeId=id;this.bridge.turns.reconcile(id,wire);this.bridge.threads.remember(id);this.diagnostic='Continuing the original stored thread; live client attachment is not supported';
    }catch(e){this.bridge.threads.activeId=previous;if(imported){this.bridge.threads.loaded.delete(id);this.bridge.store.state.threads=this.bridge.store.state.threads.filter(t=>t.id!==id);this.bridge.store.state.turns=this.bridge.store.state.turns.filter(t=>t.threadId!==id);this.bridge.store.save();}if(resumed)await this.bridge.client.request('thread/unsubscribe',{threadId:id}).catch(()=>{});row.eligible=false;row.reason=e instanceof Error?e.message:'Stored handoff unavailable';throw e;}
    finally{this.resolving=false;}
  }
  /** The actual newest chat is the target, even when its original client owns it.
   * Observation is read-only. A failed live handoff never falls back to an older chat. */
  async latest(_preferred?:string){await this.poll();}
  async startMonitoring(){await this.poll();if(!this.closed&&!this.monitor){this.monitor=setInterval(()=>{void this.poll().catch(()=>{this.diagnostic='Latest Codex history is temporarily unavailable; no conversation was substituted';});},3000);this.monitor.unref();}}
  private async poll(){
    if(this.closed||this.polling||this.resolving||!this.bridge.available)return;
    this.polling=true;
    try{
      await this.discover();if(this.closed)return;
      const newest=this.rows.find(r=>r.model&&!/[\\/]\.(?:runtime|cache)(?:[\\/]|$)/i.test(r.cwd)&&!['Internal or ephemeral session','Original project is unavailable on this PC','Original stored resource path unavailable','Original supported model metadata unavailable'].includes(r.reason));
      if(!newest){this.diagnostic='No local Codex chat metadata is available. ChatGPT cloud chats are not exposed by the local Codex server.';return;}
      const pinned=this.locked()||this.bridge.store.state.turns.some(t=>!terminal(t.status)&&this.bridge.threads.loaded.has(t.threadId));
      const id=pinned?this.bridge.threads.activeId:newest.id;
      const row=this.rows.find(r=>r.id===id);if(!row)return;
      if(this.bridge.threads.loaded.has(row.id)){this.diagnostic='Using this exact original conversation';return;}
      const version=this.bridge.threads.selectionVersion;
      const wire=object(object(await this.bridge.client.request('thread/read',{threadId:row.id,includeTurns:true})).thread);
      if(this.closed||wire.id!==row.id||!samePath(requiredString(wire.cwd),row.cwd)||!Array.isArray(wire.turns))return;
      if(this.bridge.threads.loaded.has(row.id)||this.bridge.threads.selectionVersion!==version||(this.locked()&&this.bridge.threads.activeId!==row.id))return;
      let local=this.bridge.store.state.threads.find(t=>t.id===row.id);
      if(!local){local={id:row.id,cwd:row.cwd,label:row.label,model:row.model,status:'unavailable',mode:'real',createdAt:new Date(Number(wire.createdAt)*1000).toISOString(),updatedAt:row.updatedAt,original:true,observed:true,source:row.source,rolloutPath:row.path,sandbox:'read-only'};this.bridge.store.state.threads.push(local);}
      else {local.label=row.label;local.updatedAt=row.updatedAt;local.observed=true;local.status='unavailable';}
      if(!pinned)this.bridge.threads.activeId=row.id;
      this.bridge.turns.observe(row.id,wire);
      const last=this.bridge.store.state.turns.filter(t=>t.threadId===row.id).at(-1);
      const fingerprint=JSON.stringify([row.label,row.updatedAt,last?.id,last?.status,last?.instruction?.length,last?.finalResponse.length]);
      if(this.fingerprints.get(row.id)!==fingerprint){this.fingerprints.set(row.id,fingerprint);this.bridge.observedUpdate(row.id,last?.id);}
      const prior=this.observed.get(row.id),states=new Map<string,string>();
      for(const raw of wire.turns.slice(-12)){const turn=object(raw),turnId=identifier(turn.id),state=String(turn.status);states.set(turnId,state);
        // First observation establishes a baseline. Only subsequently observed real completions ring.
        if(prior&&['completed','failed','interrupted'].includes(state)&&prior.get(turnId)!==state)this.bridge.observedCompletion(row.id,turnId);
      }
      this.observed.set(row.id,states);while(this.observed.size>8)this.observed.delete(this.observed.keys().next().value!);
      this.diagnostic='Watching the actual latest Codex chat. Live-client sending requires a supported handoff; no older chat will be substituted.';
    }finally{this.polling=false;}
  }
  close(){this.closed=true;if(this.monitor)clearInterval(this.monitor);this.ownership.close();}
}
