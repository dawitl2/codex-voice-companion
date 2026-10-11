import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdtempSync, mkdirSync, rmSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { once } from 'node:events';
import { WebSocket } from 'ws';
import { Bridge } from '../src/core/Bridge.js';
import { MockTransport } from '../src/core/MockTransport.js';
import { RemoteServer, privateEndpoint } from '../src/remote/RemoteServer.js';
import { envelope } from '../src/remote/Protocol.js';

const endpoint = 'wss://test.example.ts.net/remote';
const headers = { 'X-Forwarded-Proto': 'https', 'X-Forwarded-Host': 'test.example.ts.net', 'Tailscale-User-Login': 'fixture@example.invalid' };
async function fixture(t: test.TestContext, timeout = 1000) {
  const root = resolve('.runtime/remote-tests'); mkdirSync(root, { recursive: true }); const dir = mkdtempSync(join(root, 'server-'));
  const bridge = new Bridge('mock', new MockTransport(undefined, 5), dir);
  const deviceId = randomUUID(), token = randomBytes(32).toString('base64url');
  const remote = new RemoteServer(bridge, { authenticate: (id, secret) => id === deviceId && secret === token, pair: () => { throw new Error('Disabled fixture'); } }, endpoint, 0, timeout, 2000);
  await bridge.connect(); await remote.start();
  t.after(async () => { await remote.close(); await bridge.close(); assert.ok(dir.startsWith(root)); rmSync(dir, { recursive: true, force: true }); });
  return { bridge, remote, deviceId, token, dir };
}
async function client(remote: RemoteServer) {
  const ws = new WebSocket(`ws://127.0.0.1:${remote.address}/remote`, { headers });
  const inbox: { type: string; payload: Record<string, unknown> }[] = [];
  ws.on('message', data => inbox.push(JSON.parse(data.toString()))); await once(ws, 'open');
  const send = (type: string, payload = {}) => ws.send(JSON.stringify(envelope(type, payload)));
  const wait = async (type: string) => {
    for (let i = 0; i < 200; i++) { const row = inbox.findIndex(x => x.type === type); if (row >= 0) return inbox.splice(row, 1)[0]!; await new Promise(r => setTimeout(r, 5)); }
    throw new Error(`Missing ${type}`);
  };
  return { ws, send, wait, inbox };
}
test('remote endpoint enforces private WSS configuration and proxy identity boundary', async t => {
  for (const url of ['ws://test.example.ts.net/remote','wss://public.example/remote','wss://test.example.ts.net/remote?token=x']) assert.throws(() => privateEndpoint(url));
  const { remote } = await fixture(t);
  for (const bad of [{}, { ...headers, Origin: 'https://evil.invalid' }, { ...headers, 'Tailscale-Funnel-Request': '?1' }]) {
    const ws = new WebSocket(`ws://127.0.0.1:${remote.address}/remote`, { headers: bad });
    const [error] = await once(ws, 'error'); assert.match(String(error), /403/);
  }
});
test('unauthenticated monitoring and unsupported messages reveal no task state', async t => {
  const { remote } = await fixture(t); const c = await client(remote);
  c.send('client.hello', { name: 'Fixture' }); await c.wait('server.hello'); c.send('state.snapshot.request');
  assert.equal((await c.wait('protocol.error')).payload.code, 'authentication_required');
  assert.equal(c.inbox.some(x => x.type === 'state.snapshot'), false);
});
test('actual loopback websocket authenticates, receives bridge events and reconnects with snapshots', async t => {
  const { bridge, remote, deviceId, token, dir } = await fixture(t);
  const c = await client(remote); c.send('client.hello', { name: 'Fixture' }); await c.wait('server.hello');
  c.send('auth.request', { deviceId, token }); await c.wait('auth.success'); const initial = await c.wait('state.snapshot'); assert.equal(initial.payload.mode, 'mock');
  const id = await bridge.create(dir, 'Fixture'); await bridge.send(id, 'hello');
  const final = await c.wait('turn.completed'); assert.equal((final.payload.turns as { status: string }[]).at(-1)?.status, 'completed');
  assert.equal(JSON.stringify(final).includes('MOCK response: hello'), false);
  assert.ok(Number(final.payload.sequence) > Number(initial.payload.sequence));
  c.ws.close(); await once(c.ws, 'close');
  const d = await client(remote); d.send('client.hello', { name: 'Fixture' }); await d.wait('server.hello'); d.send('auth.request', { deviceId, token }); await d.wait('auth.success');
  const resumed = await d.wait('state.snapshot'); assert.equal(resumed.payload.epoch, final.payload.epoch); assert.equal((resumed.payload.turns as unknown[]).length, 1);
  d.send('heartbeat'); await d.wait('heartbeat'); d.ws.close();
});
test('handshake times out and revocation terminates authenticated sockets', async t => {
  const { remote, deviceId, token } = await fixture(t, 50); const idle = await client(remote);
  const [code] = await once(idle.ws, 'close'); assert.equal(code, 4001);
  const c = await client(remote); c.send('client.hello', { name: 'Fixture' }); await c.wait('server.hello'); c.send('auth.request', { deviceId, token }); await c.wait('auth.success');
  const closed = once(c.ws, 'close'); remote.revoke(deviceId); assert.equal((await closed)[0], 4003);
});
test('duplicate publications do not advance cursors and late clients receive bounded current state', async t => {
  const { bridge, remote, dir } = await fixture(t); await bridge.create(dir, 'Deduplicated');
  const sequence = remote.snapshot().sequence; const event = bridge.history().at(-1)!;
  bridge.events.emit(event); bridge.events.emit(event); assert.equal(remote.snapshot().sequence, sequence);
  assert.equal(remote.snapshot().historyComplete, false);
});
test('bad authentication and malformed frames produce fixed errors without revealing state', async t => {
  const { remote, deviceId } = await fixture(t); const c = await client(remote);
  c.send('client.hello', { name: 'Fixture' }); await c.wait('server.hello'); c.send('auth.request', { deviceId, token: 'A'.repeat(43) });
  assert.equal((await c.wait('auth.failed')).payload.code, 'invalid_credentials');
  assert.equal(c.inbox.some(x => x.type === 'state.snapshot'), false);
  const d = await client(remote); d.ws.send('not-json-private-value');
  const error = await d.wait('protocol.error'); assert.equal(error.payload.code, 'invalid_message'); assert.equal(JSON.stringify(error).includes('private-value'), false);
});
test('shutdown closes clients and unsubscribes without forwarding new bridge events', async t => {
  const { bridge, remote, dir } = await fixture(t); const c = await client(remote); const closed = once(c.ws, 'close');
  await remote.close(); await closed; const sequence = remote.snapshot().sequence;
  await bridge.create(dir, 'After remote close'); assert.equal(remote.snapshot().sequence, sequence);
});
