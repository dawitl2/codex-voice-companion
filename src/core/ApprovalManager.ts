import { randomUUID } from 'node:crypto';
import type { ApprovalRecord, BridgeEvent, RpcCall } from '../types/index.js';
import { AppServerClient } from './AppServerClient.js';
import { TurnManager } from './TurnManager.js';
import { JsonStore } from '../storage/JsonStore.js';
import { identifier, object, sanitize, terminal } from '../utils/safe.js';

type Emit = (type: BridgeEvent['type'], summary: string, threadId?: string, turnId?: string) => void;
export class ApprovalManager {
  private readonly timers = new Map<string, NodeJS.Timeout>();
  constructor(private readonly client: AppServerClient, private readonly turns: TurnManager, private readonly store: JsonStore, private readonly emit: Emit, private readonly timeoutMs = 60000) {}
  list(): ApprovalRecord[] { return structuredClone(this.store.state.approvals.filter(a => a.status === 'pending')); }
  request(call: RpcCall & { id: string | number }): void {
    const supported = ['item/commandExecution/requestApproval','item/fileChange/requestApproval'];
    const p = object(call.params);
    if (this.store.state.approvals.some(a => a.requestId === call.id && a.status === 'pending')) return;
    if (!supported.includes(call.method)) {
      if (call.method === 'item/permissions/requestApproval') this.client.respond(call.id, { permissions: {}, scope: 'turn' });
      else if (call.method === 'mcpServer/elicitation/request') this.client.respond(call.id, { action: 'decline' });
      else if (call.method === 'item/tool/requestUserInput') this.client.respond(call.id, { answers: {} });
      else this.client.rejectRequest(call.id);
      this.emit('codex.error', `Unsupported permission or input request rejected: ${call.method}`);
      if (typeof p.threadId === 'string' && typeof p.turnId === 'string') void this.turns.interrupt(p.threadId, p.turnId).catch(() => {});
      return;
    }
    const threadId = identifier(p.threadId); const turnId = identifier(p.turnId); const turn = this.turns.get(threadId, turnId);
    if (!turn || terminal(turn.status) || !this.client.ready) { this.client.respond(call.id, { decision: 'cancel' }); return; }
    const summary = sanitize(`${call.method.includes('commandExecution') ? 'Command' : 'File change'} approval. Item: ${p.itemId ?? 'unknown'}. ${p.command ?? ''} ${p.cwd ? `Directory: ${p.cwd}.` : ''} ${p.grantRoot ? `Requested write root: ${p.grantRoot}.` : ''} ${p.reason ?? ''} ${p.networkApprovalContext ? `Network context: ${JSON.stringify(p.networkApprovalContext)}.` : ''}`, 2000);
    const record: ApprovalRecord = { id: randomUUID(), requestId: call.id, method: call.method, threadId, turnId, summary, requestedAt: new Date().toISOString(), expiresAt: new Date(Date.now() + this.timeoutMs).toISOString(), status: 'pending' };
    this.store.state.approvals.push(record); this.turns.approvalState(threadId, turnId, true);
    this.emit('approval.requested', `${record.id}: ${summary}`, threadId, turnId);
    this.timers.set(record.id, setTimeout(() => { try { this.resolve(record.id, false, 'expired'); } catch { if (record.status === 'pending') this.clear(record, 'unavailable'); } }, this.timeoutMs));
    this.store.save();
  }
  resolve(id: string, accept: boolean, status?: 'expired'): void {
    const record = this.store.state.approvals.find(a => a.id === id && a.status === 'pending');
    if (!record) throw new Error('Approval is absent, expired, or already resolved');
    if (!this.client.ready) throw new Error('Cannot resolve an approval while disconnected');
    const turn = this.turns.get(record.threadId, record.turnId);
    if (!turn || terminal(turn.status)) { this.clear(record, 'unavailable'); throw new Error('Approval turn is no longer active'); }
    // One action only. No persistent grants or accept-for-session decisions.
    this.client.respond(record.requestId, { decision: accept ? 'accept' : status === 'expired' ? 'cancel' : 'decline' });
    this.clear(record, status ?? (accept ? 'accepted' : 'declined'));
  }
  serverResolved(params: unknown): void {
    const p = object(params); const record = this.store.state.approvals.find(a => a.requestId === p.requestId && a.threadId === p.threadId && a.status === 'pending');
    if (record) this.clear(record, 'resolved');
  }
  private clear(record: ApprovalRecord, status: ApprovalRecord['status']): void {
    const timer = this.timers.get(record.id); if (timer) clearTimeout(timer); this.timers.delete(record.id);
    record.status = status;
    const waiting = this.store.state.approvals.some(a => a.status === 'pending' && a.threadId === record.threadId && a.turnId === record.turnId);
    this.turns.approvalState(record.threadId, record.turnId, waiting);
    this.emit('approval.resolved', `Approval ${record.id}: ${status}`, record.threadId, record.turnId); this.store.save();
  }
  close(): void { for (const record of this.list()) { try { this.resolve(record.id, false); } catch { this.disconnected(); } } }
  disconnected(): void {
    for (const timer of this.timers.values()) clearTimeout(timer); this.timers.clear();
    for (const record of this.store.state.approvals) if (record.status === 'pending') record.status = 'unavailable'; this.store.save();
  }
}
