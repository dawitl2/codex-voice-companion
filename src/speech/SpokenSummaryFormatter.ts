import { sanitize } from '../utils/safe.js';
import type { ManagedThread, ManagedTurn } from '../types/index.js';
export function spokenSummary(thread: ManagedThread | undefined, turn: ManagedTurn | undefined): string {
  if (!thread || !turn || thread.id !== turn.threadId) return 'Task details are unavailable. You can review the task on your PC.';
  const status = { completed: 'The Codex turn completed. This does not independently verify the work.', failed: 'The Codex turn failed.', interrupted: 'The Codex turn was interrupted.', awaitingApproval: 'Approval needs attention on the PC.', starting: 'The Codex turn is starting.', running: 'The Codex turn is running.', unknown: 'The turn outcome is unknown. Review it on the PC.' }[turn.status];
  const cleaned = sanitize(turn.finalResponse, 4000).replace(/```[\s\S]*?```/g, ' ').replace(/(?:[A-Za-z]:\\|\\\\)[^\s]+|(?:\/[^\s/]+){2,}|https?:\/\/\S+/g, '[private path omitted]').replace(/[`*_#]/g, '').replace(/\s+/g, ' ').trim();
  let response=cleaned.slice(0,650); const excerpt=cleaned.length>650 || turn.finalResponse.length>4000;
  if(excerpt){const end=Math.max(response.lastIndexOf('.'),response.lastIndexOf('!'),response.lastIndexOf('?'));if(end>200)response=response.slice(0,end+1);else response+='…';}
  return ((thread.mode === 'mock' ? 'Simulated task. ' : '') + status + (response ? (excerpt?' Here is an excerpt of Codex’s reported response: ':' Codex reported: ') + response + (excerpt?' The full sanitized response is available on the screen.':'') : ' No response text is available.')).slice(0, 1000);
}
