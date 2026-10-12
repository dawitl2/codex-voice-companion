// Offline fixture only. Simulated Serve headers are not private-route verification.
import { mkdirSync, mkdtempSync, writeFileSync, existsSync, readFileSync, rmSync } from 'node:fs';
import { resolve, join, dirname } from 'node:path';
import { Bridge } from '../src/core/Bridge.js';
import { MockTransport } from '../src/core/MockTransport.js';
import { RemoteServer } from '../src/remote/RemoteServer.js';
import { DeviceRegistry } from '../src/remote/DeviceRegistry.js';
import { NotificationDispatcher } from '../src/notifications/NotificationDispatcher.js';
import { NotificationDeliveryStore } from '../src/notifications/NotificationDeliveryStore.js';

const directory = resolve('.runtime', 'protocol-integration'); mkdirSync(directory, { recursive: true });
const metadata = join(directory, 'fixture.json'), control = join(directory, 'control.txt');
const pushFile = join(directory, 'push.json');
if (existsSync(pushFile)) rmSync(pushFile);
if (existsSync(metadata)) rmSync(metadata); if (existsSync(control)) rmSync(control);
let data: unknown = [];
const registry = new DeviceRegistry({ load: () => data, save: value => { data = structuredClone(value); } });
// Each invocation has a fresh history; prior fixture runs must not count as live events.
const stateDirectory = mkdtempSync(join(directory, 'state-'));
if (dirname(resolve(stateDirectory)) !== directory) throw new Error('Fixture state must remain in its runtime directory');
const bridge = new Bridge('mock', new MockTransport(undefined, 15), stateDirectory);
let deliveryData: unknown = [];
const dispatcher = new NotificationDispatcher(bridge, registry, new NotificationDeliveryStore({ load: () => deliveryData, save: value => { deliveryData = structuredClone(value); } }),
  { send: async (_fid, payload) => { writeFileSync(pushFile, JSON.stringify(payload), { mode: 0o600 }); } });
const remote = new RemoteServer(bridge, registry, 'wss://test.example.ts.net/remote', 0, 15000, 20000, { registry, dispatcher, projectId: 'codex-voice-example' });
let started = false, closing = false;
async function close() {
  if (closing) return; closing = true; clearInterval(poll);
  await remote.close(); await dispatcher.close(); await bridge.close();
  rmSync(stateDirectory, { recursive: true });
  if (existsSync(metadata)) rmSync(metadata); if (existsSync(control)) rmSync(control);
  if (existsSync(pushFile)) rmSync(pushFile);
}
await bridge.connect(); await remote.start(); const threadId = await bridge.create(directory, 'Protocol fixture');
const invitation = registry.beginPairing();
writeFileSync(metadata, JSON.stringify({ port: remote.address, code: invitation.code, endpoint: remote.endpoint.href, control, pushFile }), { mode: 0o600 });
console.log('Offline protocol fixture ready; no Codex inference or private route.');
const poll = setInterval(() => {
  if (!started && remote.connectedDevices && registry.pushTargets().some(t => t.preferences.enabled)) { started = true; void bridge.send(threadId, '[mock:duplicate]').catch(() => { void close(); }); }
  if (existsSync(control)) {
    const command = readFileSync(control, 'utf8').trim(); rmSync(control);
    if (command === 'revoke') for (const device of registry.list()) { registry.revoke(device.id); remote.revoke(device.id); }
    if (command === 'quit') void close();
  }
}, 30);
process.on('SIGINT', () => { void close(); }); process.on('SIGTERM', () => { void close(); });
