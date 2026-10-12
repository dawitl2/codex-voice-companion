import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { mkdtempSync, mkdirSync, rmSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { WebSocket } from 'ws';
import { Bridge } from '../src/core/Bridge.js';
import { MockTransport } from '../src/core/MockTransport.js';
import { DeviceRegistry, type RegistryStorage } from '../src/remote/DeviceRegistry.js';
import { RemoteServer } from '../src/remote/RemoteServer.js';
import { envelope } from '../src/remote/Protocol.js';
import { CallSessionManager } from '../src/calls/CallSessionManager.js';
import { CallEventCoordinator } from '../src/calls/CallEventCoordinator.js';
import { MediaTransportServer } from '../src/calls/MediaTransportServer.js';
import { PCM, audioFrame, validateAudioFrame } from '../src/calls/AudioProtocol.js';
import { eligible, DEFAULT_PREFERENCES } from '../src/notifications/NotificationPolicy.js';
class Memory implements RegistryStorage { data: unknown = []; load() { return structuredClone(this.data); } save(value: unknown) { this.data = structuredClone(value); } }
const headers = { 'X-Forwarded-Proto': 'https', 'X-Forwarded-Host': 'test.example.ts.net', 'Tailscale-User-Login': 'fixture@example.invalid' };
async function until(predicate: () => boolean) { for (let i = 0; i < 400; i++) { if (predicate()) return; await new Promise(r => setTimeout(r, 5)); }; throw new Error('Media fixture deadline'); }
async function fixture(t: test.TestContext) {
  const root = resolve('.runtime/media-tests'); mkdirSync(root, { recursive: true }); const dir = mkdtempSync(join(root, 'media-'));
  const bridge = new Bridge('mock', new MockTransport(undefined, 5), dir); await bridge.connect();
  const registry = new DeviceRegistry(new Memory()), credential = registry.pair(registry.beginPairing().code, 'Fixture', headers['Tailscale-User-Login']);
  registry.registerPush(credential.deviceId, 'c' + 'A'.repeat(21), { ...DEFAULT_PREFERENCES, enabled: true });
  const calls = new CallSessionManager(new Memory(), registry); const coordinator = new CallEventCoordinator(bridge, registry, calls);
  const media = new MediaTransportServer(calls, 'wss://test.example.ts.net/remote');
  const remote = new RemoteServer(bridge, registry, 'wss://test.example.ts.net/remote', 0, 1000, 2000, undefined, { manager: calls, media }); await remote.start();
  t.after(async () => { coordinator.close(); await remote.close(); calls.close(); await bridge.close(); assert.ok(dir.startsWith(root + '\\')); rmSync(dir, { recursive: true, force: true }); });
  calls.setPreferences(credential.deviceId, true); const thread = await bridge.create(dir, 'Simulated media verification'); await bridge.send(thread, '[mock:duplicate]');
  await until(() => bridge.history().some(e => e.type === 'turn.completed'));
  const event = eligible(bridge.history().find(e => e.type === 'turn.completed')!, 'mock', Date.now())!; const offer = calls.byEvent(credential.deviceId, event.eventId)!;
  return { calls, credential, media, remote, offer };
}
async function openMedia(f: Awaited<ReturnType<typeof fixture>>) {
  const grant = f.calls.accept(f.credential.deviceId, f.offer.callId, headers['Tailscale-User-Login']);
  const ws = new WebSocket(`ws://127.0.0.1:${f.remote.address}/media`, { headers: { ...headers, Authorization: 'Bearer ' + grant.token } });
  const messages: Record<string, unknown>[] = [], frames: Buffer[] = [];
  ws.on('message', (data, binary) => { if (binary) frames.push(Buffer.from(data as Buffer)); else messages.push(JSON.parse(data.toString())); }); ws.on('error', () => {});
  await once(ws, 'open'); ws.send(JSON.stringify({ version: 1, type: 'media.hello', callId: f.offer.callId, format: PCM }));
  await until(() => messages.some(m => m.type === 'media.ready'));
  return { ws, grant, messages, frames };
}
test('real separate media endpoint authenticates and transports a finite PC-generated PCM tone', async t => {
  const f = await fixture(t); assert.equal(f.calls.diagnostics().length, 1); const c = await openMedia(f);
  assert.equal(f.calls.status(f.credential.deviceId, f.offer.callId).state, 'active');
  const pcm = Buffer.alloc(640); pcm.fill(0x11);
  c.ws.send(audioFrame(0, 100, pcm, 0)); await until(() => f.media.diagnostics().at(-1)?.metrics.uplinkFrames === 1);
  assert.ok(f.media.diagnostics().at(-1)!.metrics.inputLevel > 0);
  c.ws.send(JSON.stringify({ version: 1, type: 'media.tone' })); await until(() => c.frames.length === 30);
  assert.ok(c.frames.every(frame => validateAudioFrame(frame, 1).level > 0));
  const closed = once(c.ws, 'close'); c.ws.send(JSON.stringify({ version: 1, type: 'media.end' })); await closed;
  assert.equal(f.calls.status(f.credential.deviceId, f.offer.callId).state, 'ended'); assert.equal(f.media.connected, 0);
});
test('media rejects missing/reused bearer grants, URL credentials, wrong tailnet identity and public proxy headers', async t => {
  const f = await fixture(t), c = await openMedia(f);
  for (const [path, badHeaders] of [['/media', headers], ['/media', { ...headers, Authorization: 'Bearer ' + c.grant.token }],
    ['/media?token=secret', headers], ['/media', { ...headers, 'Tailscale-Funnel-Request': 'true' }], ['/media', { ...headers, Origin: 'https://evil.invalid' }]]) {
    const ws = new WebSocket(`ws://127.0.0.1:${f.remote.address}${path}`, { headers: badHeaders as typeof headers }); const [error] = await once(ws, 'error'); assert.match(String(error), /403/);
  }
  const ended = once(c.ws, 'close');
  f.calls.revoke(f.credential.deviceId); await ended; assert.equal(f.media.connected, 0);
});
test('mute drops microphone content on the PC and malformed/replayed frames fail the session', async t => {
  const f = await fixture(t), c = await openMedia(f); f.calls.mute(f.credential.deviceId, f.offer.callId, true);
  c.ws.send(audioFrame(0, 100, Buffer.alloc(640, 9), 0)); await new Promise(r => setTimeout(r, 30)); assert.equal(f.media.diagnostics().at(-1)?.metrics.uplinkFrames, 0);
  f.calls.mute(f.credential.deviceId, f.offer.callId, false); c.ws.send(audioFrame(1, 120, Buffer.alloc(640, 9), 0)); await until(() => f.media.diagnostics().at(-1)?.metrics.uplinkFrames === 1);
  const closed = once(c.ws, 'close'); c.ws.send(audioFrame(1, 120, Buffer.alloc(640), 0)); await closed;
  assert.equal(f.calls.status(f.credential.deviceId, f.offer.callId).reason, 'audio_sequence_error'); assert.equal(f.media.diagnostics().at(-1)?.metrics.sequenceErrors, 1);
});
test('oversized audio and excessive frame rate close abusive sessions without changing monitor limits', async t => {
  for (const oversized of [true, false]) {
    const f = await fixture(t), c = await openMedia(f); const closed = once(c.ws, 'close');
    if (oversized) c.ws.send(Buffer.alloc(1025)); else for (let i = 0; i < 151; i++) c.ws.send(audioFrame(i, i * 20, Buffer.alloc(640), 0));
    await closed; await until(() => f.media.connected === 0); assert.equal(f.calls.status(f.credential.deviceId, f.offer.callId).state, 'failed');
  }
});
test('call signaling requires the normal authenticated device session and is absent for legacy monitor-only clients', async t => {
  const f = await fixture(t); const ws = new WebSocket(`ws://127.0.0.1:${f.remote.address}/remote`, { headers });
  const messages: { type: string; payload: Record<string, unknown> }[] = []; ws.on('message', data => messages.push(JSON.parse(data.toString()))); await once(ws, 'open');
  ws.send(JSON.stringify(envelope('client.hello', { name: 'Fixture' }))); await until(() => messages.some(m => m.type === 'server.hello'));
  assert.equal(messages.some(m => m.type.startsWith('call.')), false);
  ws.send(JSON.stringify(envelope('call.accept', { callId: f.offer.callId }))); await until(() => messages.some(m => m.type === 'protocol.error'));
  assert.equal(messages.find(m => m.type === 'protocol.error')?.payload.code, 'authentication_required');
});
