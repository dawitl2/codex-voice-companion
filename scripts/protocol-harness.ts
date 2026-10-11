// Offline fixture only. Simulated Serve headers are not private-route verification.
import { mkdirSync, writeFileSync, existsSync, readFileSync, rmSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { Bridge } from '../src/core/Bridge.js';
import { MockTransport } from '../src/core/MockTransport.js';
import { RemoteServer } from '../src/remote/RemoteServer.js';
import { DeviceRegistry } from '../src/remote/DeviceRegistry.js';

const directory = resolve('.runtime', 'protocol-integration'); mkdirSync(directory, { recursive: true });
const metadata = join(directory, 'fixture.json'), control = join(directory, 'control.txt');
if (existsSync(metadata)) rmSync(metadata); if (existsSync(control)) rmSync(control);
let data: unknown = [];
const registry = new DeviceRegistry({ load: () => data, save: value => { data = structuredClone(value); } });
const bridge = new Bridge('mock', new MockTransport(undefined, 15), join(directory, 'state'));
const remote = new RemoteServer(bridge, registry, 'wss://test.example.ts.net/remote', 0);
let started = false, closing = false;
async function close() {
  if (closing) return; closing = true; clearInterval(poll);
  await remote.close(); await bridge.close();
  if (existsSync(metadata)) rmSync(metadata); if (existsSync(control)) rmSync(control);
}
await bridge.connect(); await remote.start(); const threadId = await bridge.create(directory, 'Protocol fixture');
const invitation = registry.beginPairing();
writeFileSync(metadata, JSON.stringify({ port: remote.address, code: invitation.code, endpoint: remote.endpoint.href, control }), { mode: 0o600 });
console.log('Offline protocol fixture ready; no Codex inference or private route.');
const poll = setInterval(() => {
  if (!started && remote.connectedDevices) { started = true; void bridge.send(threadId, '[mock:duplicate]').catch(() => { void close(); }); }
  if (existsSync(control)) {
    const command = readFileSync(control, 'utf8').trim(); rmSync(control);
    if (command === 'revoke') for (const device of registry.list()) { registry.revoke(device.id); remote.revoke(device.id); }
    if (command === 'quit') void close();
  }
}, 30);
process.on('SIGINT', () => { void close(); }); process.on('SIGTERM', () => { void close(); });
