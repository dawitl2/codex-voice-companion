import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Bridge } from '../src/core/Bridge.js';
import { CodexProcessManager, resolveCodex } from '../src/core/CodexProcessManager.js';
import { object, errorText } from '../src/utils/safe.js';

async function main(): Promise<void> {
  if (Number(process.versions.node.split('.')[0]) !== 24) throw new Error('Node 24 required');
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
  const directory = resolve(root, '.runtime/real-test'); const cwd = resolve(directory, 'isolated-project'); mkdirSync(cwd, { recursive: true });
  const report: Record<string, unknown> = { timestamp: new Date().toISOString(), node: process.versions.node, handshake: false, authentication: false, inference: 'skipped', reason: 'Preflight pending', resumption: 'Not tested; no second inference requested' };
  let bridge: Bridge | undefined; let transport: CodexProcessManager | undefined; let inferenceSubmitted = false;
  let cleanupWait: (() => void) | undefined;
  try {
    transport = new CodexProcessManager(await resolveCodex(), cwd); bridge = new Bridge('real', transport, resolve(directory, 'state'), 'read-only', 20000);
    await bridge.connect(); report.handshake = true; report.authentication = bridge.authentication; report.plan = bridge.plan; report.codex = transport.version;
    if (bridge.plan !== 'plus') throw new Error('Controlled test requires verified Plus account');
    const catalog = object(await bridge.client.request('model/list', { includeHidden: false, limit: 100 }));
    if (!Array.isArray(catalog.data)) throw new Error('Invalid model catalog');
    const models = catalog.data.map(object);
    // Prefer the account's visible default. Catalog discovery is a preflight, not proof of entitlement.
    const selected = models.find(m => m.isDefault === true) ?? models.find(m => m.model === 'gpt-6.1-sol');
    if (!selected || typeof selected.model !== 'string') throw new Error('No visible account model; skip inference');
    report.model = selected.model;
    const threadId = await bridge.create(cwd, 'Step 2 controlled connection test', selected.model); report.threadId = threadId;
    const completed = new Promise<import('../src/types/index.js').BridgeEvent>((resolveEvent, reject) => {
      const timer = setTimeout(() => { unsubscribe(); reject(new Error('Controlled inference terminal event timed out; no retry')); }, 60000);
      const unsubscribe = bridge!.events.subscribe(event => {
        if (event.threadId === threadId && ['turn.completed','turn.failed','turn.interrupted'].includes(event.type)) { clearTimeout(timer); unsubscribe(); resolveEvent(event); }
        if (event.type === 'bridge.disconnected') { clearTimeout(timer); unsubscribe(); reject(new Error('Transport disconnected during test')); }
      });
      cleanupWait = () => { clearTimeout(timer); unsubscribe(); };
    });
    // Handle early rejection while awaiting turn/start without leaking a promise.
    void completed.catch(() => {});
    inferenceSubmitted = true; report.inference = 'submitted';
    const turnId = await bridge.send(threadId, 'Reply with exactly: CODEX VOICE BRIDGE CONNECTED'); report.turnId = turnId;
    const event = await completed; const turn = bridge.turns.get(threadId, turnId);
    if (!turn || event.turnId !== turnId || turn.status !== 'completed' || turn.finalResponse.trim() !== 'CODEX VOICE BRIDGE CONNECTED') throw new Error(`Controlled test mismatch: ${turn?.status ?? 'missing turn'}`);
    report.inference = 'passed'; report.completionEvent = event.type; report.finalResponse = turn.finalResponse; report.reason = 'One ChatGPT-authenticated Codex turn completed; IDs persisted';
    console.log('REAL TEST PASSED:', JSON.stringify(report, null, 2));
  } catch (error) {
    report.inference = inferenceSubmitted ? 'failed' : 'skipped'; report.reason = errorText(error); process.exitCode = 1; console.error(JSON.stringify(report, null, 2));
  } finally {
    cleanupWait?.();
    try { await bridge?.close(); report.cleanShutdown = transport?.pid === undefined; } catch (error) { report.cleanShutdown = false; report.shutdownError = errorText(error); process.exitCode = 1; }
    writeFileSync(resolve(directory, 'result.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
  }
}
main().catch(error => { console.error(errorText(error)); process.exitCode = 1; });
