import { basename } from 'node:path';
import type { ManagedThread, ManagedTurn } from '../types/index.js';
import { sanitize } from '../utils/safe.js';
export function summarize(thread: ManagedThread, turn: ManagedTurn): string {
  const seconds = Math.max(0, Math.round((Date.parse(turn.finishedAt ?? new Date().toISOString()) - Date.parse(turn.startedAt)) / 1000));
  const commands = turn.commandResults.length ? ` Command outcomes: ${turn.commandResults.map(c => c.exitCode === null ? c.status : `exit ${c.exitCode}`).join(', ')}. These are command outcomes, not independently verified test results.` : ' No structured test results were reported.';
  return sanitize(`${basename(thread.cwd)} / ${thread.label}: turn ${turn.status} (${seconds}s).${turn.error ? ` ${turn.error}.` : ''}${commands}${turn.finalResponse ? ` Codex response: ${turn.finalResponse.slice(0, 1600)}` : ''} A completed turn does not prove that the broader task succeeded.`);
}
