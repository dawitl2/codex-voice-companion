import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { Bridge } from './core/Bridge.js';
import { MockTransport } from './core/MockTransport.js';
import { CodexProcessManager, resolveCodex } from './core/CodexProcessManager.js';
import { projectDirectory } from './config/settings.js';
import { runTerminal } from './cli/Terminal.js';
import { errorText } from './utils/safe.js';

async function main(): Promise<void> {
  if (Number(process.versions.node.split('.')[0]) !== 24) throw new Error('Node.js 24 LTS required; use scripts/run.ps1 or install Node 24');
  const args = process.argv.slice(2);
  if (args.includes('--help')) { console.log('Usage: node dist/src/index.js [--mock | --real] [--workspace-write]\nDefault: offline mock with read-only sandbox. --real uses ChatGPT-authenticated Codex 0.160.0.'); return; }
  if (args.some(x => !['--mock','--real','--workspace-write'].includes(x)) || (args.includes('--mock') && args.includes('--real'))) throw new Error('Invalid CLI options; use --help');
  const mode = args.includes('--real') ? 'real' : 'mock';
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
  const cwd = projectDirectory(root); const directory = resolve(root, '.runtime', mode);
  // Mock mode hydrates its own fixture history only. The real adapter never reads private Codex transcripts.
  const transport = mode === 'real' ? new CodexProcessManager(await resolveCodex(), cwd) : new MockTransport(undefined, 200);
  const bridge = new Bridge(mode, transport, directory, args.includes('--workspace-write') ? 'workspace-write' : 'read-only');
  if (transport instanceof MockTransport) transport.hydrate(bridge.store.state);
  try { await runTerminal(bridge); } finally { await bridge.close(); }
}
main().catch(error => { console.error(errorText(error)); process.exitCode = 1; });
