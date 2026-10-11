import { closeSync, copyFileSync, existsSync, fsyncSync, mkdirSync, openSync, readFileSync, renameSync, unlinkSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import type { Mode, StoredState } from '../types/index.js';
import { identifier, object } from '../utils/safe.js';
import { atomicReplace } from './atomicReplace.js';

export class JsonStore {
  readonly state: StoredState;
  private readonly file: string;
  private readonly lock: string;
  private closed = false;
  constructor(directory: string, mode: Mode) {
    mkdirSync(directory, { recursive: true });
    this.file = join(directory, 'state.json'); this.lock = join(directory, 'bridge.lock');
    // Serialize startup/reclamation so two processes cannot both remove the
    // same stale lock and accidentally acquire independent writer ownership.
    const gate = this.lock + '.acquiring'; let gateFd: number;
    try { gateFd = openSync(gate, 'wx', 0o600); }
    catch { throw new Error('Storage acquisition is busy or abandoned; inspect bridge.lock.acquiring manually'); }
    try {
      writeFileSync(gateFd, JSON.stringify({ pid: process.pid }));
      if (existsSync(this.lock)) {
        const lockData = object(JSON.parse(readFileSync(this.lock, 'utf8')));
        if (!Number.isInteger(lockData.pid) || Number(lockData.pid) <= 0) throw new Error('Invalid bridge lock; inspect it manually');
        try { process.kill(Number(lockData.pid), 0); throw new Error('Another bridge owns this state directory'); }
        catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ESRCH') throw error; }
        unlinkSync(this.lock);
      }
      const lockFd = openSync(this.lock, 'wx', 0o600);
      try { writeFileSync(lockFd, JSON.stringify({ pid: process.pid })); } finally { closeSync(lockFd); }
    } finally { closeSync(gateFd); unlinkSync(gate); }
    try {
      if (!existsSync(this.file)) this.state = { version: 1, mode, threads: [], turns: [], events: [], approvals: [] };
      else {
        try { this.state = this.read(mode, this.file); }
        catch (mainError) {
          if (!existsSync(this.file + '.bak')) throw mainError;
          this.state = this.read(mode, this.file + '.bak');
          renameSync(this.file, this.file + '.corrupt-' + Date.now());
        }
      }
      // Lost transport does not prove that Codex completed or interrupted work.
      for (const turn of this.state.turns) if (['starting','running','awaitingApproval'].includes(turn.status)) { turn.status = 'unknown'; turn.error = 'Bridge restarted; resume and reconcile before another instruction'; }
      for (const approval of this.state.approvals) if (approval.status === 'pending') approval.status = 'unavailable';
      for (const thread of this.state.threads) thread.status = 'unavailable';
      this.save();
    } catch (error) { unlinkSync(this.lock); throw error; }
  }
  private read(mode: Mode, file: string): StoredState {
    const state = object(JSON.parse(readFileSync(file, 'utf8')));
    if (state.version !== 1 || state.mode !== mode || !['threads','turns','events','approvals'].every(key => Array.isArray(state[key]))) throw new Error('Invalid or incompatible state file');
    for (const row of state.threads as unknown[]) { const thread = object(row); identifier(thread.id); if (typeof thread.cwd !== 'string' || thread.mode !== mode || typeof thread.label !== 'string') throw new Error('Invalid stored thread'); }
    for (const row of state.turns as unknown[]) { const turn = object(row); identifier(turn.id); identifier(turn.threadId); if (typeof turn.finalResponse !== 'string' || !Array.isArray(turn.commandResults) || !['starting','running','awaitingApproval','completed','failed','interrupted','unknown'].includes(String(turn.status))) throw new Error('Invalid stored turn'); }
    const date = (value: unknown) => typeof value === 'string' && Number.isFinite(Date.parse(value));
    for (const row of state.threads as unknown[]) { const thread = object(row); if (!date(thread.createdAt) || typeof thread.model !== 'string' || !['ready','unavailable'].includes(String(thread.status))) throw new Error('Invalid stored thread metadata'); }
    for (const row of state.turns as unknown[]) { const turn = object(row); if (!date(turn.startedAt) || turn.finishedAt !== undefined && !date(turn.finishedAt) || typeof turn.completionEmitted !== 'boolean') throw new Error('Invalid stored turn metadata');
      if (!(state.threads as { id: string }[]).some(t => t.id === turn.threadId)) throw new Error('Stored turn has no managed thread');
      for (const raw of turn.commandResults as unknown[]) { const result = object(raw); identifier(result.itemId); if (typeof result.status !== 'string' || result.exitCode !== null && !Number.isInteger(result.exitCode)) throw new Error('Invalid stored command result'); }
    }
    for (const row of state.events as unknown[]) { const event = object(row); identifier(event.id); if (!date(event.timestamp) || typeof event.summary !== 'string' || !['bridge.connected','bridge.disconnected','thread.created','thread.resumed','turn.started','turn.progress','turn.completed','turn.failed','turn.interrupted','approval.requested','approval.resolved','codex.error'].includes(String(event.type))) throw new Error('Invalid stored event'); }
    for (const row of state.approvals as unknown[]) { const approval = object(row); identifier(approval.id); identifier(approval.threadId); identifier(approval.turnId); if (!date(approval.requestedAt) || !date(approval.expiresAt) || typeof approval.summary !== 'string' || typeof approval.method !== 'string' || !['string','number'].includes(typeof approval.requestId) || !['pending','accepted','declined','expired','unavailable','resolved'].includes(String(approval.status))) throw new Error('Invalid stored approval'); }
    return state as unknown as StoredState;
  }
  save(): void {
    if (this.closed) throw new Error('Storage is closed');
    this.state.events = this.state.events.slice(-500);
    this.state.approvals = this.state.approvals.filter(x => x.status === 'pending').concat(this.state.approvals.filter(x => x.status !== 'pending').slice(-100));
    const temp = this.file + '.tmp';
    const fd = openSync(temp, 'w', 0o600);
    try { writeFileSync(fd, JSON.stringify(this.state, null, 2)); fsyncSync(fd); } finally { closeSync(fd); }
    if (existsSync(this.file)) copyFileSync(this.file, this.file + '.bak');
    atomicReplace(temp, this.file);
  }
  close(): void { if (!this.closed) { this.save(); this.closed = true; unlinkSync(this.lock); } }
}
