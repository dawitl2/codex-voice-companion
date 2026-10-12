import type { BridgeEvent, ManagedTurn, RpcCall, TurnStatus } from '../types/index.js';
import { AppServerClient } from './AppServerClient.js';
import { ThreadManager } from './ThreadManager.js';
import { JsonStore } from '../storage/JsonStore.js';
import { identifier, object, requiredString, sanitize, terminal } from '../utils/safe.js';
import { summarize } from './SummaryFormatter.js';

type Emit = (type: BridgeEvent['type'], summary: string, threadId?: string, turnId?: string, metadata?: BridgeEvent['metadata']) => void;
export class TurnManager {
  private readonly starting = new Set<string>();
  private readonly messages = new Map<string, Map<string, string>>();
  constructor(private readonly client: AppServerClient, private readonly threads: ThreadManager, private readonly store: JsonStore, private readonly emit: Emit) {}
  get(threadId: string, turnId: string): ManagedTurn | undefined { return this.store.state.turns.find(t => t.threadId === threadId && t.id === turnId); }
  active(threadId: string): ManagedTurn | undefined { return this.store.state.turns.find(t => t.threadId === threadId && !terminal(t.status)); }
  async start(threadId: string, text: string, beforeDispatch?: () => void): Promise<ManagedTurn> {
    const thread = this.threads.get(threadId);
    if (!this.threads.loaded.has(threadId)) throw new Error('Resume this thread before sending instructions');
    if (this.starting.has(threadId) || this.active(threadId)) throw new Error('Thread has active or unreconciled work; stop or resume it first');
    if (!text.trim() || text.length > 32000) throw new Error('Instruction must contain 1–32000 characters');
    beforeDispatch?.();
    this.starting.add(threadId);
    try {
      const sandboxPolicy = this.threads.sandbox === 'read-only' ? { type: 'readOnly' } : { type: 'workspaceWrite', writableRoots: [thread.cwd], networkAccess: false, excludeTmpdirEnvVar: true, excludeSlashTmp: true };
      const response = object(await this.client.request('turn/start', { threadId, input: [{ type: 'text', text }], serviceTier: 'default', serviceTierForTurn: 'default', cwd: thread.cwd, approvalPolicy: 'on-request', approvalsReviewer: 'user', sandboxPolicy }));
      const wire = object(response.turn);
      if (!['inProgress','completed','failed','interrupted'].includes(String(wire.status))) throw new Error('Invalid turn/start response status');
      const turn = this.ensure(threadId, identifier(wire.id));
      if (!terminal(turn.status)) turn.status = 'running';
      this.store.save(); return structuredClone(turn);
    } catch (error) {
      // A timed-out mutating RPC may have reached the server. Force a resume, never resend.
      this.threads.loaded.delete(threadId); thread.status = 'unavailable'; this.store.save(); throw error;
    } finally { this.starting.delete(threadId); }
  }
  async interrupt(threadId: string, turnId: string): Promise<void> {
    this.threads.get(threadId); const turn = this.get(threadId, turnId);
    if (!turn || terminal(turn.status)) throw new Error('No active managed turn to interrupt');
    if (!this.threads.loaded.has(threadId)) throw new Error('Resume the thread before interruption');
    await this.client.request('turn/interrupt', { threadId, turnId });
    // Only turn/completed confirms interruption.
  }
  private ensure(threadId: string, turnId: string): ManagedTurn {
    this.threads.get(threadId);
    let turn = this.get(threadId, turnId);
    if (!turn) {
      turn = { id: turnId, threadId, startedAt: new Date().toISOString(), status: 'running', finalResponse: '', commandResults: [], completionEmitted: false };
      this.store.state.turns.push(turn); this.emit('turn.started', 'Codex turn started', threadId, turnId);
    }
    return turn;
  }
  notification(call: RpcCall): void {
    const methods = ['turn/started','turn/completed','item/agentMessage/delta','item/completed','item/started','error'];
    if (!methods.includes(call.method)) return;
    const p = object(call.params); const threadId = identifier(p.threadId);
    // Events from unregistered threads must never enter the local registry.
    if (!this.store.state.threads.some(t => t.id === threadId)) return;
    if (call.method === 'error') {
      const error = object(p.error); this.emit('codex.error', sanitize(error.message ?? 'Codex error'), threadId, typeof p.turnId === 'string' ? p.turnId : undefined, { willRetry: p.willRetry === true }); return;
    }
    const wire = call.method.startsWith('turn/') ? object(p.turn) : undefined;
    const turnId = identifier(wire ? wire.id : p.turnId); const turn = this.ensure(threadId, turnId);
    if (wire) {
      if (call.method === 'turn/completed') this.finish(turn, wire);
      // A late started event must never regress a terminal turn.
    } else if (call.method === 'item/agentMessage/delta') {
      if (terminal(turn.status)) return;
      const itemId = identifier(p.itemId); const delta = requiredString(p.delta);
      let items = this.messages.get(threadId + ':' + turnId); if (!items) { items = new Map(); this.messages.set(threadId + ':' + turnId, items); }
      // Keep bounded raw text in memory until full-message redaction; do not log
      // token fragments that could split a credential pattern across events.
      items.set(itemId, ((items.get(itemId) ?? '') + delta).slice(0, 16000));
      this.emit('turn.progress', `Assistant output received (${delta.length} characters)`, threadId, turnId, { characters: delta.length });
    } else if (call.method === 'item/completed') {
      this.item(turn, object(p.item));
      if (terminal(turn.status)) {
        const event = this.store.state.events.find(e => e.threadId === threadId && e.turnId === turnId && ['turn.completed','turn.failed','turn.interrupted'].includes(e.type));
        if (event) event.summary = summarize(this.threads.get(threadId), turn);
      }
    } else {
      const item = object(p.item); this.emit('turn.progress', `Started ${sanitize(item.type, 80)}`, threadId, turnId);
    }
    this.store.save();
  }
  private item(turn: ManagedTurn, item: Record<string, unknown>): void {
    const itemId = identifier(item.id);
    if (item.type === 'agentMessage' && typeof item.text === 'string') {
      if (item.phase === 'commentary') { this.messages.get(turn.threadId + ':' + turn.id)?.delete(itemId); return; }
      let items = this.messages.get(turn.threadId + ':' + turn.id); if (!items) { items = new Map(); this.messages.set(turn.threadId + ':' + turn.id, items); }
      if (item.phase === 'final_answer') items.clear();
      items.set(itemId, sanitize(item.text, 16000)); turn.finalResponse = sanitize([...items.values()].join('\n'), 16000);
      if (terminal(turn.status)) this.messages.delete(turn.threadId + ':' + turn.id);
    }
    if (item.type === 'commandExecution') {
      const row = { itemId, status: sanitize(item.status, 80), exitCode: typeof item.exitCode === 'number' ? item.exitCode : null };
      const existing = turn.commandResults.findIndex(x => x.itemId === itemId);
      if (existing < 0) turn.commandResults.push(row); else turn.commandResults[existing] = row;
    }
  }
  private finish(turn: ManagedTurn, wire: Record<string, unknown>, reconciled = false): void {
    if (!['completed','failed','interrupted'].includes(String(wire.status))) throw new Error('Invalid terminal turn status');
    if (terminal(turn.status)) return;
    if (Array.isArray(wire.items)) for (const item of wire.items) this.item(turn, object(item));
    const items = this.messages.get(turn.threadId + ':' + turn.id);
    if (items && !turn.finalResponse) turn.finalResponse = sanitize([...items.values()].join('\n'), 16000);
    this.messages.delete(turn.threadId + ':' + turn.id);
    turn.status = wire.status as TurnStatus; turn.finishedAt = new Date().toISOString();
    if (wire.error) turn.error = sanitize(object(wire.error).message ?? 'Codex turn failed');
    if (!turn.completionEmitted) {
      turn.completionEmitted = true;
      this.emit(turn.status === 'completed' ? 'turn.completed' : turn.status === 'failed' ? 'turn.failed' : 'turn.interrupted', summarize(this.threads.get(turn.threadId), turn), turn.threadId, turn.id, { reconciled });
    }
  }
  reconcile(threadId: string, wire: Record<string, unknown>): void {
    if (!Array.isArray(wire.turns)) throw new Error('Resumption omitted turn history; cannot reconcile safely');
    for (const raw of wire.turns) {
      const t = object(raw); const turn = this.ensure(threadId, identifier(t.id));
      if (['completed','failed','interrupted'].includes(String(t.status))) this.finish(turn, t, true);
      else if (t.status === 'inProgress' && !terminal(turn.status)) turn.status = 'running';
      else if (t.status !== 'inProgress') throw new Error('Unknown resumed turn status');
    }
    this.store.save(); // Unknown turns absent from history deliberately remain blocked.
  }
  approvalState(threadId: string, turnId: string, waiting: boolean): void {
    const turn = this.get(threadId, turnId); if (turn && !terminal(turn.status)) turn.status = waiting ? 'awaitingApproval' : 'running'; this.store.save();
  }
  disconnected(): void {
    for (const turn of this.store.state.turns) if (!terminal(turn.status)) { turn.status = 'unknown'; turn.error = 'Transport lost; resume to reconcile the actual outcome'; }
    this.messages.clear(); this.store.save();
  }
}
