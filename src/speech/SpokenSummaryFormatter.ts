import { sanitize } from '../utils/safe.js';
import type { ManagedThread, ManagedTurn } from '../types/index.js';
export function spokenSummary(thread: ManagedThread | undefined, turn: ManagedTurn | undefined): string {
  if (!thread || !turn || thread.id !== turn.threadId) return 'Task details are unavailable. You can review the task on your PC.';
  const status = { completed: 'The Codex turn completed. This does not independently verify the work.', failed: 'The Codex turn failed.', interrupted: 'The Codex turn was interrupted.', awaitingApproval: 'Approval needs attention on the PC.', starting: 'The Codex turn is starting.', running: 'The Codex turn is running.', unknown: 'The turn outcome is unknown. Review it on the PC.' }[turn.status];
  const response = sanitize(turn.finalResponse, 4000).replace(/```[\s\S]*?```/g, ' ').replace(/(?:[A-Za-z]:\\|\\\\)[^\s]+|(?:\/[^\s/]+){2,}|https?:\/\/\S+/g, '[private path omitted]').replace(/[`*_#]/g, '').replace(/\s+/g, ' ').trim().slice(0, 700);
  return ((thread.mode === 'mock' ? 'Simulated task. ' : '') + status + (response ? ' Codex reported: ' + response : ' No response text is available.')).slice(0, 1000);
}
