import type { BridgeEvent, Mode, RpcTransport,ManagedThread } from '../types/index.js';
import { EventBus } from '../events/EventBus.js';
import { JsonStore } from '../storage/JsonStore.js';
import { AppServerClient } from './AppServerClient.js';
import { ThreadManager } from './ThreadManager.js';
import { TurnManager } from './TurnManager.js';
import { ApprovalManager } from './ApprovalManager.js';
import { EventNormalizer } from './EventNormalizer.js';
import { object, sanitize, terminal } from '../utils/safe.js';
import type { Sandbox } from '../config/settings.js';

export class Bridge {
  readonly events = new EventBus(); readonly store: JsonStore; readonly client: AppServerClient;
  readonly threads: ThreadManager; readonly turns: TurnManager; readonly approvals: ApprovalManager;
  authentication = 'Unverified'; plan: string | undefined; private closed = false;
  ownershipGuard:((thread:ManagedThread)=>Promise<void>)|undefined;
  private verifiedConnection = false;
  get available(): boolean { return this.client.ready && this.verifiedConnection && !this.closed; }
  private readonly transport: RpcTransport;
  private readonly normalizer = new EventNormalizer();
  constructor(readonly mode: Mode, transport: RpcTransport, directory: string, sandbox: Sandbox = 'read-only', timeoutMs = 30000, approvalTimeoutMs = 60000, writableProject?: string) {
    this.transport = transport;
    this.store = new JsonStore(directory, mode); this.client = new AppServerClient(transport, timeoutMs);
    const emit = (type: BridgeEvent['type'], summary: string, threadId?: string, turnId?: string, metadata?: BridgeEvent['metadata']) => {
      const scope: { threadId?: string; turnId?: string; metadata?: BridgeEvent['metadata'] } = {};
      if (threadId) scope.threadId = threadId; if (turnId) scope.turnId = turnId; if (metadata) scope.metadata = metadata;
      const event = this.normalizer.create(type, summary, scope); this.store.state.events.push(event); this.store.save(); this.events.emit(event);
    };
    this.threads = new ThreadManager(this.client, this.store, mode, sandbox, writableProject);
    this.turns = new TurnManager(this.client, this.threads, this.store, emit);
    this.approvals = new ApprovalManager(this.client, this.turns, this.store, emit, approvalTimeoutMs);
    this.client.onNotification = call => {
      try { if (call.method === 'serverRequest/resolved') this.approvals.serverResolved(call.params); else this.turns.notification(call); }
      catch (error) { emit('codex.error', sanitize(error instanceof Error ? error.message : error)); void this.client.close().catch(() => {}); }
    };
    this.client.onServerRequest = call => {
      try { this.approvals.request(call); }
      catch { this.client.rejectRequest(call.id, 'Unsafe or invalid approval association'); }
    };
    this.client.onDisconnected = reason => {
      this.verifiedConnection = false;
      this.authentication = 'Unverified'; this.approvals.disconnected(); this.threads.disconnected(); this.turns.disconnected(); emit('bridge.disconnected', reason);
    };
    transport.onDiagnostic(text => { emit('codex.error', `App-server diagnostic: ${sanitize(text, 1000)}`); });
  }
  async connect(): Promise<void> {
    if (this.closed) throw new Error('Bridge is closed');
    if (this.client.ready) return;
    await this.client.connect();
    try { await this.verifyAccount(); await this.verifyConfiguration(); this.verifiedConnection = true; this.record('bridge.connected', this.mode === 'mock' ? 'OFFLINE MOCK connected; no real Codex inference' : `Codex connected with ChatGPT (${this.plan})`); }
    catch (error) { await this.client.close(); throw error; }
  }
  async verifyAccount(): Promise<void> {
    const result = object(await this.client.request('account/read', { refreshToken: false }));
    if (this.mode === 'mock') { this.authentication = 'Mock only'; return; }
    if (!result.account) { this.authentication = 'Blocked'; throw new Error('App-server has no authenticated account; sign in through Codex using ChatGPT. Mock mode remains available.'); }
    const account = object(result.account);
    if (account.type !== 'chatgpt' || typeof account.planType !== 'string' || !['plus','pro','team','business','enterprise','edu'].includes(account.planType)) { this.authentication = 'Blocked'; throw new Error('Eligible ChatGPT authentication required; API-key and free accounts are refused; run codex login using ChatGPT'); }
    this.authentication = 'ChatGPT verified'; this.plan = account.planType;
  }
  async create(cwd: string, label: string, model?: string): Promise<string> {
    await this.verifyAccount(); await this.verifyConfiguration(cwd); const thread = await this.threads.create(cwd, label, model); this.record('thread.created', `Created ${thread.label}`, thread.id); return thread.id;
  }
  async select(id: string): Promise<void> {
    await this.verifyAccount();
    const selected=this.threads.get(id);if(selected.original){if(!this.ownershipGuard)throw Error('Stored session handoff is unavailable');await this.ownershipGuard(selected);}
    await this.verifyConfiguration(this.threads.get(id).cwd);
    if (!this.threads.loaded.has(id)) {
      const previous = this.threads.activeId;
      try { const wire = await this.threads.resume(id); this.turns.reconcile(id, wire); this.record('thread.resumed', 'Original companion-managed Codex thread resumed', id); }
      catch (error) { this.threads.loaded.delete(id); this.threads.get(id).status = 'unavailable'; this.threads.activeId = previous; this.store.save(); throw error; }
    }
    else { this.threads.get(id); this.threads.activeId = id; }
    this.threads.remember(id);
    this.threads.get(id).observed=false;this.store.save();
  }
  async send(id: string, text: string, beforeDispatch?: () => void): Promise<string> { const thread=this.threads.get(id);if(thread.original){if(!this.ownershipGuard)throw Error('Stored session handoff is unavailable');await this.ownershipGuard(thread);}await this.verifyAccount(); await this.verifyConfiguration(thread.cwd);const turn=(await this.turns.start(id, text, beforeDispatch)).id;this.threads.remember(id);return turn; }
  async verifyConfiguration(cwd?: string): Promise<void> {
    if (this.mode === 'mock') return;
    const config = object(object(await this.client.request('config/read', { includeLayers: false, ...(cwd ? { cwd } : {}) })).config);
    const endpoint = (value: unknown, host: string, path: RegExp): boolean => {
      if (value === null || value === undefined) return true;
      if (typeof value !== 'string') return false;
      try { const url = new URL(value); return url.protocol === 'https:' && url.hostname === host && !url.port && !url.username && !url.password && !url.search && !url.hash && path.test(url.pathname); } catch { return false; }
    };
    const failed = Object.entries({ model_provider: config.model_provider === 'openai', forced_login_method: config.forced_login_method === 'chatgpt', endpoints: endpoint(config.openai_base_url, 'api.openai.com', /^\/v1\/?$/) && endpoint(config.chatgpt_base_url, 'chatgpt.com', /^(\/|\/backend-api(?:\/codex)?\/?)$/), approval_policy: config.approval_policy === 'on-request', approvals_reviewer: config.approvals_reviewer === 'user', sandbox_mode: config.sandbox_mode === 'read-only' }).filter(([,valid]) => !valid).map(([key]) => key);
    if (failed.length) throw new Error(`Effective Codex configuration violates required settings: ${failed.join(', ')}`);
    if (config.service_tier !== 'default') throw new Error('Standard-speed service tier was not honored');
    if (!Array.isArray(config.notify) || config.notify.length) throw new Error('Inherited notification commands were not disabled');
    for (const section of ['mcp_servers','plugins']) if (config[section] && Object.values(object(config[section])).some(value => object(value).enabled !== false)) throw new Error('Inherited integrations were not disabled; real mode refused');
    const features = object(config.features);
    if (features.hooks !== false || features.apps !== false) throw new Error('Inherited hooks or apps were not disabled');
  }
  history(): BridgeEvent[] { return structuredClone(this.store.state.events); }
  observedUpdate(threadId:string,turnId?:string):void {
    const event=this.normalizer.create(turnId?'turn.progress':'thread.resumed','Original Codex chat synchronized',{threadId,...(turnId?{turnId}:{})});
    this.store.state.events.push(event);this.store.save();this.events.emit(event);
  }
  observedCompletion(threadId:string,turnId:string):void {
    const turn=this.turns.get(threadId,turnId);
    if(!turn||!terminal(turn.status)||this.threads.loaded.has(threadId))return;
    const type=turn.status==='completed'?'turn.completed':turn.status==='failed'?'turn.failed':'turn.interrupted';
    const event=this.normalizer.create(type,'Original Codex conversation '+turn.status,{threadId,turnId,metadata:{observed:true}});
    this.store.state.events.push(event);this.store.save();this.events.emit(event);
  }
  versionInfo(): { bridge: string; node: string; codex: string } { return { bridge: '0.1.0', node: process.versions.node, codex: this.mode === 'mock' ? 'offline mock' : 'version' in this.transport && typeof this.transport.version === 'string' ? this.transport.version : 'unverified' }; }
  private record(type: BridgeEvent['type'], summary: string, threadId?: string): void {
    const event = this.normalizer.create(type, summary, threadId ? { threadId } : {}); this.store.state.events.push(event); this.store.save(); this.events.emit(event);
  }
  async close(): Promise<void> {
    if (this.closed) return; this.closed = true;
    try {
      this.approvals.close();
      if (this.client.ready) {
        const active = this.store.state.turns.filter(t => !terminal(t.status) && this.threads.loaded.has(t.threadId));
        await Promise.allSettled(active.map(t => this.turns.interrupt(t.threadId, t.id)));
      }
      await this.client.close();
    } finally { this.approvals.disconnected(); this.store.close(); }
  }
}
