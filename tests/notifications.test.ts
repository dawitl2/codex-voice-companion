import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash, createHmac } from 'node:crypto';
import { mkdirSync, mkdtempSync, rmSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { DeviceRegistry, type RegistryStorage } from '../src/remote/DeviceRegistry.js';
import { eligible, pushPayload, canonical, preferences, validFid, DEFAULT_PREFERENCES } from '../src/notifications/NotificationPolicy.js';
import { NotificationDeliveryStore } from '../src/notifications/NotificationDeliveryStore.js';
import { NotificationDispatcher, PushFailure, type PushProvider } from '../src/notifications/NotificationDispatcher.js';
import { Bridge } from '../src/core/Bridge.js';
import { MockTransport } from '../src/core/MockTransport.js';
import type { BridgeEvent } from '../src/types/index.js';

class Memory implements RegistryStorage { data: unknown = []; load() { return structuredClone(this.data); } save(value: unknown) { this.data = structuredClone(value); } }
const fid = 'c' + 'A'.repeat(21);
const enabled = { ...DEFAULT_PREFERENCES, enabled: true, interrupted: true };
const timestamp = '2026-10-12T00:00:00.000Z';
function event(type: BridgeEvent['type'] = 'turn.completed', turnId = 'turn'): BridgeEvent { return { id: 'local', type, timestamp, threadId: 'thread', turnId, summary: 'SECRET transcript C:\\private\\path' }; }
async function fixture(t: test.TestContext, provider?: PushProvider, liveClock = false) {
  const root = resolve('.runtime/notification-tests'); mkdirSync(root, { recursive: true }); const dir = mkdtempSync(join(root, 'fixture-'));
  let now = liveClock ? Date.now() : Date.parse(timestamp);
  const registryMemory = new Memory(), deliveryMemory = new Memory();
  const registry = new DeviceRegistry(registryMemory, () => now), credential = registry.pair(registry.beginPairing().code, 'Fixture', 'identity');
  registry.registerPush(credential.deviceId, fid, enabled);
  const bridge = new Bridge('mock', new MockTransport(undefined, 5), dir);
  const sent: unknown[] = [], store = new NotificationDeliveryStore(deliveryMemory);
  const dispatcher = new NotificationDispatcher(bridge, registry, store, provider ?? { send: async (...args) => { sent.push(args); } }, () => now, async () => {});
  t.after(async () => { await dispatcher.close(); await bridge.close(); assert.ok(dir.startsWith(root)); rmSync(dir, { recursive: true, force: true }); });
  return { bridge, registry, credential, store, dispatcher, sent, deliveryMemory, registryMemory, advance: (ms: number) => { now += ms; } };
}
test('policy filters progress, stale, future, unscoped and reconciled history', () => {
  const now = Date.parse(timestamp);
  for (const raw of [event('turn.progress'), { ...event(), timestamp: '2020-01-01T00:00:00Z' }, { ...event(), timestamp: '2030-01-01T00:00:00Z' }, { ...event(), metadata: { reconciled: true } }, { ...event(), threadId: '' }]) assert.equal(eligible(raw, 'real', now), null);
  for (const type of ['turn.completed','turn.failed','turn.interrupted'] as const) assert.ok(eligible(event(type), 'real', now));
});
test('approval requires actual pending record and expires at its authoritative deadline', () => {
  const raw = { ...event('approval.requested'), metadata: { approvalId: 'approval' } };
  const approval = { id: 'approval', requestId: 1, method: 'method', threadId: 'thread', turnId: 'turn', summary: 'PRIVATE', requestedAt: timestamp, expiresAt: '2026-10-12T00:00:05Z', status: 'pending' as const };
  assert.equal(eligible(raw, 'real', Date.parse(timestamp)), null);
  assert.equal(eligible(raw, 'real', Date.parse(timestamp), [approval])?.expiresAt, '2026-10-12T00:00:05.000Z');
  assert.equal(eligible(raw, 'real', Date.parse(timestamp) + 5000, [approval]), null);
});
test('push payload contains only opaque generic fields with pairing-bound signature', () => {
  const notification = eligible(event(), 'mock', Date.parse(timestamp))!;
  const digest = createHash('sha256').update('paired secret').digest('hex');
  const payload = pushPayload(notification, 'paired-id', digest), { signature, ...unsigned } = payload;
  assert.equal(signature, createHmac('sha256', Buffer.from(digest, 'hex')).update(canonical(unsigned)).digest('base64url'));
  assert.deepEqual(Object.keys(payload), ['version','eventId','pairingId','kind','occurredAt','expiresAt','mode','signature']);
  for (const privateValue of ['SECRET','transcript','thread','turn','private','paired secret']) assert.equal(JSON.stringify(payload).includes(privateValue), false);
  assert.equal(eligible({ ...event(), id: 'duplicate-id' }, 'mock', Date.parse(timestamp))?.eventId, notification.eventId);
  assert.notEqual(eligible(event(), 'real', Date.parse(timestamp))?.eventId, notification.eventId);
});
test('preferences and FIDs fail closed without implicit truthy values', () => {
  assert.deepEqual(preferences(enabled), enabled);
  for (const value of [{ ...enabled, enabled: 1 }, { ...enabled, extra: true }, {}, null]) assert.throws(() => preferences(value));
  assert.equal(validFid(fid), true); for (const value of ['legacy:token','a'.repeat(22), 'c'.repeat(23), null]) assert.equal(validFid(value), false);
});
test('protected device destination rotates and is never disclosed by ordinary device list', async t => {
  const f = await fixture(t); assert.equal(JSON.stringify(f.registry.list()).includes(fid), false);
  const fresh = 'd' + 'B'.repeat(21); f.registry.registerPush(f.credential.deviceId, fresh, enabled);
  assert.equal(new DeviceRegistry(f.registryMemory).pushTargets()[0]?.fid, fresh);
  assert.throws(() => f.registry.registerPush('unknown', fresh, enabled), /revoked/);
  f.registry.removePush(f.credential.deviceId, fid); assert.equal(f.registry.pushTargets().length, 1);
  f.registry.removePush(f.credential.deviceId, fresh); assert.equal(f.registry.pushTargets().length, 0);
});
test('dispatcher targets one paired device and persists duplicate suppression across restart', async t => {
  const f = await fixture(t); f.bridge.events.emit(event()); f.bridge.events.emit({ ...event(), id: 'another-id' }); await f.dispatcher.drain();
  assert.equal(f.sent.length, 1); const id = eligible(event(), 'mock', Date.parse(timestamp))!.eventId;
  assert.equal(f.store.find(f.credential.deviceId, id)?.status, 'accepted');
  await f.dispatcher.close(); const second = new NotificationDispatcher(f.bridge, f.registry, new NotificationDeliveryStore(f.deliveryMemory), { send: async () => { assert.fail('replayed push'); } }, () => Date.parse(timestamp));
  f.bridge.events.emit(event()); await second.drain(); await second.close();
});
test('category preferences and revocation prevent future sends', async t => {
  const f = await fixture(t); f.registry.updatePushPreferences(f.credential.deviceId, { ...enabled, completed: false });
  f.bridge.events.emit(event()); await f.dispatcher.drain(); assert.equal(f.sent.length, 0);
  f.registry.revoke(f.credential.deviceId); f.bridge.events.emit(event('turn.failed')); await f.dispatcher.drain(); assert.equal(f.sent.length, 0);
});
test('temporary failures retry at most three times and do not escape event subscriber', async t => {
  let calls = 0; const f = await fixture(t, { send: async () => { calls++; throw new PushFailure('temporary'); } });
  f.bridge.events.emit(event()); await f.dispatcher.drain(); assert.equal(calls, 3);
  assert.equal(f.store.diagnostics(f.credential.deviceId)[0]?.status, 'failed');
});
test('transient failure recovers with one logical record', async t => {
  let calls = 0; const f = await fixture(t, { send: async () => { if (++calls < 2) throw new PushFailure('temporary'); } });
  f.bridge.events.emit(event()); await f.dispatcher.drain(); assert.equal(calls, 2);
  assert.equal(f.store.diagnostics(f.credential.deviceId)[0]?.status, 'accepted');
});
test('unregistered destination is removed without indefinite retry', async t => {
  let calls = 0; const f = await fixture(t, { send: async () => { calls++; throw new PushFailure('unregistered'); } });
  f.bridge.events.emit(event()); await f.dispatcher.drain(); assert.equal(calls, 1); assert.equal(f.registry.pushTargets().length, 0);
});
test('retries recheck revocation, preferences and event expiry', async t => {
  let calls = 0; const f = await fixture(t, { send: async () => { calls++; f.registry.revoke(f.credential.deviceId); throw new PushFailure('temporary'); } });
  f.bridge.events.emit(event()); await f.dispatcher.drain(); assert.equal(calls, 1);
  assert.equal(f.store.diagnostics(f.credential.deviceId)[0]?.status, 'suppressed');
});
test('rate limit bounds bursts without reconnect backlog', async t => {
  const f = await fixture(t); for (let i = 0; i < 12; i++) f.bridge.events.emit(event('turn.completed', String(i)));
  await f.dispatcher.drain(); assert.equal(f.sent.length, 6);
  assert.equal(f.store.diagnostics(f.credential.deviceId).filter(r => r.code === 'rate_limited').length, 6);
});
test('provider disabled keeps bridge events working and honestly reports unavailable transport', async t => {
  const f = await fixture(t); await f.dispatcher.close();
  const offline = new NotificationDispatcher(f.bridge, f.registry, f.store, undefined);
  assert.equal(offline.configured, false); assert.equal(offline.diagnostic, 'provider_disabled');
  f.bridge.events.emit(event()); assert.equal(f.store.diagnostics(f.credential.deviceId).length, 0); await offline.close();
});
test('FCM acceptance and authenticated device observations remain separate diagnostics', async t => {
  const f = await fixture(t); f.bridge.events.emit(event()); await f.dispatcher.drain();
  const id = eligible(event(), 'mock', Date.parse(timestamp))!.eventId;
  assert.deepEqual(f.store.diagnostics(f.credential.deviceId)[0]?.receipts, {});
  f.dispatcher.receipt(f.credential.deviceId, id, 'received'); f.dispatcher.receipt(f.credential.deviceId, id, 'posted');
  assert.ok(f.store.diagnostics(f.credential.deviceId)[0]?.receipts.posted);
  assert.throws(() => f.dispatcher.receipt('other-device', id, 'opened'), /unknown_event/);
  assert.equal(f.dispatcher.event(f.credential.deviceId, id).threadId, 'thread');
  f.advance(1800001); assert.equal(f.dispatcher.event(f.credential.deviceId, id).expired, true);
});
test('ambiguous sends on restart become uncertain and cannot alert again', () => {
  const memory = new Memory(), first = new NotificationDeliveryStore(memory), notification = eligible(event(), 'mock', Date.parse(timestamp))!;
  const row = first.create('00000000-0000-0000-0000-000000000000', notification)!; row.status = 'sending'; first.save();
  const resumed = new NotificationDeliveryStore(memory);
  assert.equal(resumed.find(row.deviceId, row.eventId)?.status, 'uncertain'); assert.equal(resumed.create(row.deviceId, notification), null);
});
test('actual mock turn completion enters the same dispatcher without inference', async t => {
  const f = await fixture(t, undefined, true); await f.bridge.connect(); const id = await f.bridge.create(resolve('.'), 'Notification fixture');
  await f.bridge.send(id, '[mock:duplicate]'); await new Promise(r => setTimeout(r, 100)); await f.dispatcher.drain();
  assert.equal(f.bridge.store.state.turns[0]?.status, 'completed');
  assert.equal(f.bridge.history().filter(e => e.type === 'turn.completed').length, 1);
  assert.equal(f.sent.length, 1);
});
