import { mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { CodexProcessManager, resolveCodex } from '../src/core/CodexProcessManager.js';
import { Bridge } from '../src/core/Bridge.js';
import { object, errorText } from '../src/utils/safe.js';
async function main(): Promise<void> {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..'); const resumeTest = process.argv.includes('--resume-test');
  const directory = resolve(root, resumeTest ? '.runtime/real-test' : '.runtime/connection-check'); mkdirSync(directory, { recursive: true });
  const transport = new CodexProcessManager(await resolveCodex(), directory); const bridge = new Bridge('real', transport, resolve(directory, 'state'));
  try {
    await bridge.connect();
    const effective = object(object(await bridge.client.request('config/read', { includeLayers: false, cwd: directory })).config);
    console.log(JSON.stringify({ authentication: bridge.authentication, plan: bridge.plan, version: transport.version, notifyEmpty: Array.isArray(effective.notify) && effective.notify.length === 0, enabledMcpServers: effective.mcp_servers ? Object.values(object(effective.mcp_servers)).filter(x => object(x).enabled !== false).length : 0, enabledPlugins: effective.plugins ? Object.values(object(effective.plugins)).filter(x => object(x).enabled !== false).length : 0 }, null, 2));
    if (resumeTest) {
      const thread = bridge.threads.list().find(t => t.label === 'Step 2 controlled connection test'); if (!thread) throw new Error('No controlled-test managed thread exists');
      await bridge.select(thread.id); const turn = bridge.store.state.turns.find(t => t.threadId === thread.id);
      if (turn?.status !== 'completed' || turn.finalResponse.trim() !== 'CODEX VOICE BRIDGE CONNECTED') throw new Error('Resumed history did not match the original result');
      console.log(JSON.stringify({ originalThreadResumed: thread.id, originalTurn: turn.id, status: turn.status, inferenceTurnsSubmitted: 0 }));
    }
  } finally { const before = Date.now(); await bridge.close(); console.log(JSON.stringify({ ownedPidAfterShutdown: transport.pid ?? null, shutdownMs: Date.now() - before })); }
}
main().catch(error => { console.error(errorText(error)); process.exitCode = 1; });
