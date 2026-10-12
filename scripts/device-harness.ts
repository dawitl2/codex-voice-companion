// Physical-device fixture: normal Tailscale Serve/WSS and production credentials.
// Codex activity is explicitly simulated; no inference or public/control endpoint.
import { mkdirSync, existsSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { Bridge } from '../src/core/Bridge.js';
import { MockTransport } from '../src/core/MockTransport.js';
import { DeviceRegistry, WindowsRegistryStorage } from '../src/remote/DeviceRegistry.js';
import { RemoteServer } from '../src/remote/RemoteServer.js';

const directory = resolve('.runtime', 'device-verification'); mkdirSync(directory, { recursive: true });
const metadata = join(directory, 'fixture.json'), control = join(directory, 'control.txt');
if (existsSync(metadata)) throw new Error('Existing device fixture must be inspected before restarting');
const endpoint = process.env.CODEX_VOICE_REMOTE_URL;
if (!endpoint) throw new Error('Actual private WSS endpoint required');
const port = Number(process.env.CODEX_VOICE_DEVICE_PORT ?? 8788);
const registry = new DeviceRegistry(new WindowsRegistryStorage(join(directory, 'registry')));
if (registry.list().length) throw new Error('Revoke previous fixture pairing before another run');
let bridge: Bridge, remote: RemoteServer, threadId: string, closing = false, busy = false;
async function start() {
  const transport = new MockTransport(undefined, 150);
  bridge = new Bridge('mock', transport, join(directory, 'state'));
  transport.hydrate(bridge.store.state);
  await bridge.connect(); remote = new RemoteServer(bridge, registry, endpoint!, port); await remote.start();
  const prior = bridge.threads.list().at(-1);
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
writeFileSync(metadata, JSON.stringify({ endpoint, code: invitation.code }), { mode: 0o600 });
console.log('Private physical-device fixture ready; all task activity is simulated.');
const poll = setInterval(() => {
  if (busy || closing || !existsSync(control)) return;
  const command = readFileSync(control, 'utf8').trim(); rmSync(control); busy = true;
  void (async () => {
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
