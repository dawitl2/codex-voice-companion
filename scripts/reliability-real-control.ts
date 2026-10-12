// Opt-in one-turn real control check; no tools, no remote server and no policy changes.
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import { Bridge } from '../src/core/Bridge.js';
import { CodexProcessManager, resolveCodex } from '../src/core/CodexProcessManager.js';
import { TaskControl } from '../src/control/TaskControl.js';
import { WindowsRegistryStorage } from '../src/remote/DeviceRegistry.js';
import { SubmissionLedger } from '../src/conversation/SubmissionLedger.js';

if (!process.argv.includes('--one-turn-confirmed')) throw new Error('Explicit --one-turn-confirmed required');
const directory = resolve('.runtime/step10-real-control'); mkdirSync(directory, { recursive: true });
const receipt = resolve(directory, 'one-turn-receipt.json');
if (existsSync(receipt)) throw new Error('The one permitted attempt already has a receipt; do not automatically retry');
const transport = new CodexProcessManager(await resolveCodex(), directory);
const bridge = new Bridge('real', transport, resolve(directory, 'state'));
let controls: TaskControl | undefined;
let starts = 0;
const originalWrite = transport.write.bind(transport);
transport.write = data => {
  const message = JSON.parse(data);
  if (message.method === 'turn/start') {
    if (++starts > 1) throw new Error('One inference attempt only');
    writeFileSync(receipt, JSON.stringify({ attemptedAt: new Date().toISOString(), turnStarts: starts }), { mode: 0o600 });
  }
  originalWrite(data);
};
try {
  await bridge.connect();
  const threadId = await bridge.create(directory, 'Step 10 isolated real interruption');
  const deviceId = randomUUID();
  const storage = new WindowsRegistryStorage(directory, 'controls.dpapi');
  controls = new TaskControl(bridge, new SubmissionLedger(new WindowsRegistryStorage(directory, 'submissions.dpapi')), storage, id => id === deviceId);
  // Local fixture ownership only; handset transport/authentication is separately verified.
  controls.connection(deviceId, 1);
  const turnId = await bridge.send(threadId, 'Write a numbered list from 1 to 100, with one neutral sentence about each integer. Use no tools, commands, files, network, or external actions.');
  let requested = false; let operationState = 'completion_race';
  const active = bridge.turns.active(threadId);
  if (active?.id === turnId) {
    const proposal = controls.propose(deviceId, threadId, turnId, 'interrupt');
    await controls.confirm(deviceId, threadId, proposal.id); requested = true;
    const deadline = Date.now() + 15000;
    while (Date.now() < deadline && !['completed','interrupted','failed','unknown'].includes(bridge.turns.get(threadId, turnId)?.status ?? '')) await new Promise(r => setTimeout(r, 100));
    operationState = controls.status(deviceId, threadId).operations.find(o => o.id === proposal.id)?.state ?? 'unknown';
  }
  const status = bridge.turns.get(threadId, turnId)?.status ?? 'unknown';
  const proof = { threadId, turnId, turnStarts: starts, interruptionRequested: requested, status, operationState,
    realAppServer: true, handsetAuth: 'local_fixture_only', ordinaryApprovalsObserved: bridge.store.state.approvals.length,
    commandResults: bridge.turns.get(threadId, turnId)?.commandResults.length ?? 0, verified: requested && status === 'interrupted' && operationState === 'interrupted' };
  writeFileSync(receipt, JSON.stringify(proof), { mode: 0o600 });
  console.log(JSON.stringify({ ...proof, threadId: undefined, turnId: undefined }));
} finally {
  controls?.close(); await bridge.close();
  console.log(JSON.stringify({ ownedPidAfterShutdown: transport.pid ?? null }));
}
