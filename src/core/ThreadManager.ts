import type { Mode, ManagedThread } from '../types/index.js';
import { AppServerClient } from './AppServerClient.js';
import { JsonStore } from '../storage/JsonStore.js';
import { projectDirectory, samePath, disabledIntegrations, type Sandbox } from '../config/settings.js';
import { identifier, object, requiredString, sanitize } from '../utils/safe.js';

export class ThreadManager {
  private selectedId: string | undefined;
  selectionVersion = 0;
  get activeId(): string | undefined { return this.selectedId; }
  set activeId(id: string | undefined) { if (id !== this.selectedId) { this.selectedId = id; this.selectionVersion++; } }
  readonly loaded = new Set<string>();
  constructor(private readonly client: AppServerClient, private readonly store: JsonStore, private readonly mode: Mode, readonly sandbox: Sandbox) {}
  list(): ManagedThread[] { return structuredClone(this.store.state.threads); }
  get(id: string): ManagedThread {
    identifier(id); const thread = this.store.state.threads.find(x => x.id === id && x.mode === this.mode);
    if (!thread) throw new Error('Thread is not companion-managed in this mode'); return thread;
  }
  selected(): ManagedThread { if (!this.activeId) throw new Error('Select a managed thread first'); return this.get(this.activeId); }
  async create(cwdInput: string, label: string, model?: string): Promise<ManagedThread> {
    const cwd = projectDirectory(cwdInput);
    const result = object(await this.client.request('thread/start', { cwd, modelProvider: 'openai', serviceTier: 'default', approvalPolicy: 'on-request', approvalsReviewer: 'user', sandbox: this.sandbox, config: this.config(cwd), ...(model ? { model } : {}) }));
    const wire = this.validate(result, cwd);
    const id = identifier(wire.id);
    if (this.store.state.threads.some(x => x.id === id)) throw new Error('Server returned an existing thread identifier');
    const thread: ManagedThread = { id, cwd, label: sanitize(label.trim() || 'Untitled thread', 80), createdAt: new Date(Number(wire.createdAt) * 1000 || Date.now()).toISOString(), model: requiredString(result.model), status: 'ready', mode: this.mode };
    this.store.state.threads.push(thread); this.loaded.add(id); this.activeId = id; this.store.save(); return thread;
  }
  async resume(id: string): Promise<Record<string, unknown>> {
    const thread = this.get(id); const cwd = projectDirectory(thread.cwd);
    if (!samePath(cwd, thread.cwd)) throw new Error('Project path changed; refusing resumption');
    const result = object(await this.client.request('thread/resume', { threadId: id, cwd, model: thread.model, modelProvider: 'openai', serviceTier: 'default', approvalPolicy: 'on-request', approvalsReviewer: 'user', sandbox: this.sandbox, config: this.config(cwd) }));
    const wire = this.validate(result, cwd); if (identifier(wire.id) !== id) throw new Error('Resumption returned the wrong thread');
    if (requiredString(result.model) !== thread.model) throw new Error('Resumption changed the selected model');
    thread.status = 'ready'; thread.model = requiredString(result.model); this.loaded.add(id); this.activeId = id; this.store.save(); return wire;
  }
  private config(cwd: string): Record<string, unknown> {
    // Config values are application-owned overrides, never user credential content.
    const values: Record<string, unknown> = { model_provider: 'openai', forced_login_method: 'chatgpt', service_tier: 'default', approvals_reviewer: 'user', approval_policy: 'on-request', sandbox_mode: this.sandbox, notify: [], mcp_servers: {}, plugins: {}, features: { apps: false, hooks: false, remote_plugin: false } };
    if (this.mode === 'real') Object.assign(values, disabledIntegrations(cwd));
    return values;
  }
  private validate(result: Record<string, unknown>, cwd: string): Record<string, unknown> {
    const wire = object(result.thread); const policy = object(result.sandbox);
    const expected = this.sandbox === 'read-only' ? 'readOnly' : 'workspaceWrite';
    if (result.modelProvider !== 'openai' || result.approvalPolicy !== 'on-request' || result.approvalsReviewer !== 'user' || policy.type !== expected || !samePath(requiredString(result.cwd), cwd) || !samePath(requiredString(wire.cwd), cwd)) throw new Error('Server did not honor required authentication, project, or safety settings');
    if (policy.type === 'workspaceWrite' && policy.networkAccess !== false) throw new Error('Workspace network access must be disabled');
    if (policy.type === 'workspaceWrite' && (!Array.isArray(policy.writableRoots) || policy.writableRoots.some(root => typeof root !== 'string' || !samePath(root, cwd)))) throw new Error('Additional writable roots are refused');
    return wire;
  }
  disconnected(): void { this.loaded.clear(); for (const thread of this.store.state.threads) thread.status = 'unavailable'; }
}
