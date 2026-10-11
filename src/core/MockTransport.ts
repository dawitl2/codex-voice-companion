import { randomUUID } from 'node:crypto';
import type { RpcTransport, StoredState } from '../types/index.js';
import { object } from '../utils/safe.js';

interface MockThread { id: string; cwd: string; createdAt: number; turns: Record<string, unknown>[] }
/** Explicit offline simulation. Never invokes a model or reads Codex authentication. */
export class MockTransport implements RpcTransport {
  private data: (data: string) => void = () => {}; private closed: (reason: string) => void = () => {};
  private readonly threads = new Map<string, MockThread>();
  private readonly approvals = new Map<string | number, { threadId: string; turnId: string }>();
  private readonly timers = new Set<NodeJS.Timeout>(); private running = false;
  readonly sent: Record<string, unknown>[] = [];
  constructor(state?: StoredState, readonly delayMs = 10) {
    if (state) this.hydrate(state);
  }
  hydrate(state: StoredState): void {
    if (state.mode !== 'mock') throw new Error('Mock adapter cannot hydrate real Codex threads');
    for (const t of state.threads) this.threads.set(t.id, { id: t.id, cwd: t.cwd, createdAt: Math.floor(Date.parse(t.createdAt) / 1000), turns: state.turns.filter(x => x.threadId === t.id).map(x => ({ id: x.id, status: ['completed','failed','interrupted'].includes(x.status) ? x.status : 'interrupted', items: [{ id: randomUUID(), type: 'agentMessage', text: x.finalResponse }], error: null })) });
  }
  onData(listener: (data: string) => void): void { this.data = listener; }
  onClose(listener: (reason: string) => void): void { this.closed = listener; }
  onDiagnostic(_listener: (text: string) => void): void {}
  async start(): Promise<void> { this.running = true; }
  emitRaw(data: string): void { if (this.running) this.data(data); }
  emit(message: unknown): void {
    const line = JSON.stringify(message) + '\n'; const split = Math.floor(line.length / 2);
    this.emitRaw(line.slice(0, split)); this.emitRaw(line.slice(split));
  }
  crash(): void { this.running = false; this.clearTimers(); this.closed('MOCK app-server crashed'); }
  write(data: string): void {
    if (!this.running) throw new Error('MOCK transport stopped');
    const message = object(JSON.parse(data)); this.sent.push(message);
    const id = message.id;
    if (typeof message.method !== 'string') {
      if (typeof id !== 'string' && typeof id !== 'number') return;
      const pending = this.approvals.get(id); if (pending) {
        this.approvals.delete(id); const decision = object(message.result).decision;
        this.schedule(() => this.complete(pending.threadId, pending.turnId, decision === 'accept' ? 'completed' : 'interrupted', decision === 'accept' ? 'MOCK approval accepted' : 'MOCK approval declined'));
      }
      return;
    }
    const p = message.params ? object(message.params) : {};
    const reply = (result: unknown) => this.emit({ id, result });
    switch (message.method) {
      case 'initialize': reply({ userAgent: 'Codex Voice offline mock' }); break;
      case 'initialized': break;
      case 'account/read': reply({ account: { type: 'chatgpt', planType: 'plus', email: null }, requiresOpenaiAuth: true }); break;
      case 'model/list': reply({ data: [{ id: 'mock-model', model: 'mock-model', isDefault: true }], nextCursor: null }); break;
      case 'thread/start': {
        const thread: MockThread = { id: randomUUID(), cwd: String(p.cwd), createdAt: Math.floor(Date.now() / 1000), turns: [] }; this.threads.set(thread.id, thread); reply(this.threadResponse(thread, p)); break;
      }
      case 'thread/resume': {
        const thread = this.threads.get(String(p.threadId)); if (!thread) this.emit({ id, error: { code: -32000, message: 'MOCK thread unavailable' } }); else reply(this.threadResponse(thread, p)); break;
      }
      case 'turn/start': {
        const threadId = String(p.threadId); const thread = this.threads.get(threadId); if (!thread) throw new Error('MOCK unknown thread');
        const turnId = randomUUID(); const turn: Record<string, unknown> = { id: turnId, status: 'inProgress', items: [], error: null }; thread.turns.push(turn);
        this.emit({ method: 'turn/started', params: { threadId, turn } }); reply({ turn });
        const text = String(object((p.input as unknown[])[0]).text);
        this.schedule(() => {
          if (text === '[mock:crash]') { this.crash(); return; }
          if (text === '[mock:wait]') return;
          if (text === '[mock:approval]') {
            const requestId = 'approval-' + randomUUID(); this.approvals.set(requestId, { threadId, turnId });
            this.emit({ id: requestId, method: 'item/commandExecution/requestApproval', params: { threadId, turnId, itemId: randomUUID(), command: 'echo MOCK', cwd: thread.cwd, reason: 'Offline approval fixture', startedAtMs: Date.now() } }); return;
          }
          const itemId = randomUUID(); const response = text === '[mock:fail]' ? 'MOCK simulated failure' : 'MOCK response: ' + text;
          this.emit({ method: 'item/agentMessage/delta', params: { threadId, turnId, itemId, delta: response.slice(0, 8) } });
          this.emit({ method: 'item/agentMessage/delta', params: { threadId, turnId, itemId, delta: response.slice(8) } });
          this.emit({ method: 'item/completed', params: { threadId, turnId, item: { id: itemId, type: 'agentMessage', text: response } } });
          turn.items = [{ id: itemId, type: 'agentMessage', text: response }];
          this.complete(threadId, turnId, text === '[mock:fail]' ? 'failed' : 'completed', response);
          if (text === '[mock:duplicate]') this.complete(threadId, turnId, 'completed', response);
        }); break;
      }
      case 'turn/interrupt': reply({}); this.schedule(() => this.complete(String(p.threadId), String(p.turnId), 'interrupted', 'MOCK interrupted')); break;
      default: this.emit({ id, error: { code: -32601, message: 'MOCK unsupported method' } });
    }
  }
  private threadResponse(thread: MockThread, p: Record<string, unknown>): unknown {
    return { thread, cwd: thread.cwd, model: 'mock-model', modelProvider: 'openai', approvalPolicy: 'on-request', approvalsReviewer: 'user', sandbox: p.sandbox === 'workspace-write' ? { type: 'workspaceWrite', writableRoots: [thread.cwd], networkAccess: false } : { type: 'readOnly' } };
  }
  private complete(threadId: string, turnId: string, status: string, text: string): void {
    const turn = this.threads.get(threadId)?.turns.find(t => t.id === turnId); if (!turn) return;
    Object.assign(turn, { status, items: Array.isArray(turn.items) && turn.items.length ? turn.items : [{ id: randomUUID(), type: 'agentMessage', text }], error: status === 'failed' ? { message: 'MOCK failure' } : null });
    this.emit({ method: 'turn/completed', params: { threadId, turn } });
  }
  private schedule(callback: () => void): void { const timer = setTimeout(() => { this.timers.delete(timer); if (this.running) callback(); }, this.delayMs); this.timers.add(timer); }
  private clearTimers(): void { for (const timer of this.timers) clearTimeout(timer); this.timers.clear(); }
  async stop(): Promise<void> { const active = this.running; this.running = false; this.clearTimers(); if (active) this.closed('MOCK transport stopped'); }
}
