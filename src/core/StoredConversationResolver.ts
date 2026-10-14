import {basename,isAbsolute} from 'node:path';
import type {Bridge} from './Bridge.js';
import {SessionOwnership} from './SessionOwnership.js';
import {projectDirectory,samePath} from '../config/settings.js';
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
  private explicitSelection:string|undefined;
  constructor(private readonly bridge:Bridge,assets:string,pid:()=>number|undefined,private readonly locked:()=>boolean){
    bridge.observedSelectionChanged=id=>{this.explicitSelection=id;};
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
        else if(object(t.status).type==='systemError')reason='Original session unavailable';
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
    if(this.bridge.threads.loaded.has(id)){await this.bridge.select(id);this.explicitSelection=id;return;}
    this.resolving=true;
    try{
      await this.synchronize(row,true);if(this.bridge.threads.activeId!==id)throw Error('Conversation changed before selection');this.explicitSelection=id;this.bridge.threads.remember(id);
    }
    finally{this.resolving=false;}
  }
  /** The actual newest chat is the target, even when its original client owns it.
   * Observation is read-only. A failed live handoff never falls back to an older chat. */
  async latest(_preferred?:string){this.explicitSelection=undefined;await this.poll();}
  async startMonitoring(){await this.poll();if(!this.closed&&!this.monitor){this.monitor=setInterval(()=>{void this.poll().catch(()=>{this.diagnostic='Latest Codex history is temporarily unavailable; no conversation was substituted';});},3000);this.monitor.unref();}}
  private async poll(){
    if(this.closed||this.polling||this.resolving||!this.bridge.available)return;
    this.polling=true;
    try{
      await this.discover();if(this.closed)return;
      const newest=this.rows.find(r=>r.model&&!/[\\/]\.(?:runtime|cache)(?:[\\/]|$)/i.test(r.cwd)&&!['Internal or ephemeral session','Original project is unavailable on this PC','Original stored resource path unavailable','Original supported model metadata unavailable'].includes(r.reason));
      if(!newest){this.diagnostic='No local Codex chat metadata is available. ChatGPT cloud chats are not exposed by the local Codex server.';return;}
      const pinned=this.locked()||!!this.explicitSelection||this.bridge.store.state.turns.some(t=>!terminal(t.status)&&this.bridge.threads.loaded.has(t.threadId));
      const id=pinned?this.bridge.threads.activeId:newest.id;
      const row=this.rows.find(r=>r.id===id);if(!row)return;
      if(this.bridge.threads.loaded.has(row.id)){this.diagnostic='Using this exact original conversation';return;}
      await this.synchronize(row,!pinned);
      // Continue watching previously active chats when Recents changes. Their
      // completion still belongs to the phone instruction that started there.
      const activeIds=new Set(this.bridge.store.state.turns.filter(t=>!terminal(t.status)).map(t=>t.threadId));
      for(const other of this.rows.filter(r=>r.id!==row.id&&activeIds.has(r.id)).slice(0,7)){
        if(!this.bridge.threads.loaded.has(other.id))await this.synchronize(other,false);
      }
      this.diagnostic='Using the original Codex chat. Phone instructions enter its native user-message queue; completion monitoring is active.';
    }finally{this.polling=false;}
  }
  private async synchronize(row:StoredConversation,select:boolean){
      const version=this.bridge.threads.selectionVersion;
      const wire=object(object(await this.bridge.client.request('thread/read',{threadId:row.id,includeTurns:true})).thread);
      if(this.closed||wire.id!==row.id||!samePath(requiredString(wire.cwd),row.cwd)||!Array.isArray(wire.turns))return;
      if(this.bridge.threads.loaded.has(row.id)||this.bridge.threads.selectionVersion!==version||(select&&this.locked()&&this.bridge.threads.activeId!==row.id))return;
      let local=this.bridge.store.state.threads.find(t=>t.id===row.id);
      if(!local){local={id:row.id,cwd:row.cwd,label:row.label,model:row.model,status:'unavailable',mode:'real',createdAt:new Date(Number(wire.createdAt)*1000).toISOString(),updatedAt:row.updatedAt,original:true,observed:true,source:row.source,rolloutPath:row.path,sandbox:'read-only'};this.bridge.store.state.threads.push(local);}
      else {local.label=row.label;local.updatedAt=row.updatedAt;local.observed=true;local.status='unavailable';}
      if(select)this.bridge.threads.activeId=row.id;
      this.bridge.turns.observe(row.id,wire);
      const last=this.bridge.store.state.turns.filter(t=>t.threadId===row.id).at(-1);
      const fingerprint=JSON.stringify([row.label,row.updatedAt,last?.id,last?.status,last?.instruction?.length,last?.finalResponse.length]);
      if(this.fingerprints.get(row.id)!==fingerprint){this.fingerprints.set(row.id,fingerprint);this.bridge.observedUpdate(row.id,last?.id);}
      const prior=this.observed.get(row.id),states=new Map<string,string>();
      for(const raw of wire.turns.slice(-12)){const turn=object(raw),turnId=identifier(turn.id),state=String(turn.status),signature=state+'|'+String(turn.completedAt??'');states.set(turnId,signature);
        // First observation establishes a baseline. Only subsequently observed real completions ring.
        if(prior&&typeof turn.completedAt==='number'&&['completed','failed','interrupted'].includes(state)&&prior.get(turnId)!==signature)this.bridge.observedCompletion(row.id,turnId);
      }
      this.observed.set(row.id,states);while(this.observed.size>8)this.observed.delete(this.observed.keys().next().value!);
  }
  close(){this.closed=true;if(this.monitor)clearInterval(this.monitor);this.bridge.observedSelectionChanged=undefined;this.ownership.close();}
}
