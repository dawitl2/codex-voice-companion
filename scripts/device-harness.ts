// Physical-device fixture: normal Tailscale Serve/WSS and production credentials.
// Codex activity is explicitly simulated; no inference or public/control endpoint.
import { mkdirSync, existsSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { Bridge } from '../src/core/Bridge.js';
import { CodexProcessManager, resolveCodex } from '../src/core/CodexProcessManager.js';
import { MockTransport } from '../src/core/MockTransport.js';
import { DeviceRegistry, WindowsRegistryStorage } from '../src/remote/DeviceRegistry.js';
import { RemoteServer } from '../src/remote/RemoteServer.js';

const real = process.env.CODEX_VOICE_DEVICE_REAL === '1';
const directory = resolve('.runtime', real ? 'device-verification-real' : 'device-verification'); mkdirSync(directory, { recursive: true });
const metadata = join(directory, 'fixture.json'), control = join(directory, 'control.txt');
if (existsSync(metadata)) throw new Error('Existing device fixture must be inspected before restarting');
const endpoint = process.env.CODEX_VOICE_REMOTE_URL;
if (!endpoint) throw new Error('Actual private WSS endpoint required');
const port = Number(process.env.CODEX_VOICE_DEVICE_PORT ?? 8788);
const registry = new DeviceRegistry(new WindowsRegistryStorage(join(directory, 'registry')));
if (registry.list().length) throw new Error('Revoke previous fixture pairing before another run');
let bridge!: Bridge, remote!: RemoteServer, threadId!: string;
let closing = false, busy = false;
async function start() {
  const realDirectory = resolve('.runtime', 'real-test');
  const transport = real ? new CodexProcessManager(await resolveCodex(), realDirectory) : new MockTransport(undefined, 150);
  bridge = new Bridge(real ? 'real' : 'mock', transport, real ? join(realDirectory, 'state') : join(directory, 'state'));
  if (transport instanceof MockTransport) transport.hydrate(bridge.store.state);
  await bridge.connect(); remote = new RemoteServer(bridge, registry, endpoint!, port); await remote.start();
  const prior = real ? bridge.threads.list().find(t => t.label === 'Step 2 controlled connection test') : bridge.threads.list().at(-1);
  if (real && !prior) throw new Error('Existing controlled real thread required; no inference will be submitted');
  threadId = prior ? prior.id : await bridge.create(directory, 'Physical device verification (simulated)');
  await bridge.select(threadId);
}
async function close() {
  if (closing) return; closing = true; clearInterval(poll);
  for (const device of registry.list()) { registry.revoke(device.id); remote.revoke(device.id); }
  await remote.close(); await bridge.close();
  for (const path of [metadata, control]) if (existsSync(path)) rmSync(path);
}
await start();
const invitation = registry.beginPairing();
const priorTurn = bridge.store.state.turns.find(t => t.threadId === threadId && t.status === 'completed');
writeFileSync(metadata, JSON.stringify({ endpoint, code: invitation.code, ...(real ? { expectedThreadId: threadId, expectedTurnId: priorTurn?.id } : {}) }), { mode: 0o600 });
console.log(real ? 'Private real snapshot fixture ready; no new inference.' : 'Private physical-device fixture ready; all task activity is simulated.');
const poll = setInterval(() => {
  if (busy || closing || !existsSync(control)) return;
  const command = readFileSync(control, 'utf8').trim(); rmSync(control); busy = true;
  void (async () => {
    if (real && !['revoke','quit'].includes(command)) throw new Error('Real fixture supports no task controls');
    switch (command) {
      case 'complete': await bridge.send(threadId, '[mock:duplicate]'); break;
      case 'failure': await bridge.send(threadId, '[mock:fail]'); break;
      case 'waiting': await bridge.send(threadId, '[mock:wait]'); break;
      case 'interrupt': { const turn = bridge.turns.active(threadId); if (turn) await bridge.turns.interrupt(threadId, turn.id); break; }
      case 'approval': await bridge.send(threadId, '[mock:approval]'); break;
      case 'decline': for (const approval of bridge.approvals.list()) if (approval.status === 'pending') bridge.approvals.resolve(approval.id, false); break;
      case 'restart': await remote.close(); await bridge.close(); await start(); break;
      case 'revoke': for (const device of registry.list()) { registry.revoke(device.id); remote.revoke(device.id); } break;
      case 'quit': await close(); break;
      default: throw new Error('Unknown fixed fixture control');
    }
  })().catch(() => { console.error('Device fixture command failed'); void close(); }).finally(() => { busy = false; });
}, 100);
process.on('SIGINT', () => { void close(); }); process.on('SIGTERM', () => { void close(); });
