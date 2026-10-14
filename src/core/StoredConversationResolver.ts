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
        else if(/codex-voice$/i.test(cwd))reason='Current companion development workspace';
        else if(!model||t.modelProvider!=='openai')reason='Original supported model metadata unavailable';
        else if(object(t.status).type==='active'||object(t.status).type==='systemError')reason='Original session active or unavailable';
        else {try{projectDirectory(cwd);}catch{reason='Original project is unavailable on this PC';}}
        if(typeof t.path!=='string')reason='Original stored resource path unavailable';
        rows.set(id,{id,label,project:sanitize(basename(cwd),80),cwd,model,source,updatedAt:new Date(Number(t.updatedAt)*1000).toISOString(),eligible:!reason,reason,path:typeof t.path==='string'?t.path:''});
      }catch{/* Invalid records cannot become targets. */}}
      cursor=typeof response.nextCursor==='string'?response.nextCursor:null;if(!cursor)break;
    }
    this.rows=[...rows.values()].sort((a,b)=>Date.parse(b.updatedAt)-Date.parse(a.updatedAt));this.diagnostic='Stored sessions found. Active original clients must release their conversation.';return this.list();
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
  async latest(preferred?:string){
    if(this.locked()||this.resolving||this.bridge.store.state.turns.some(t=>!terminal(t.status)&&this.bridge.threads.loaded.has(t.threadId)))return;
    await this.discover();
    const known=this.bridge.threads.list().filter(t=>!developmentConversation(t.label,t.cwd)).sort((a,b)=>Date.parse(b.lastSelectedAt??b.updatedAt??b.createdAt)-Date.parse(a.lastSelectedAt??a.updatedAt??a.createdAt));
    const remembered=known.find(t=>t.id===preferred)??known[0];
    const newest=this.rows.find(r=>r.eligible);
    if(remembered&&(!newest||Date.parse(remembered.lastSelectedAt??remembered.updatedAt??remembered.createdAt)>=Date.parse(newest.updatedAt))){try{await this.bridge.select(remembered.id);return;}catch{/* Display discovery alternatives. */}}
    for(const row of this.rows.filter(r=>r.eligible).slice(0,5)){try{await this.select(row.id);return;}catch{/* Try the next genuinely eligible stored session. */}}
    this.diagnostic='No safely released compatible recent session. Choose one on the desktop after closing its original Codex client.';
  }
  close(){this.ownership.close();}
}
