import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { DeviceRegistry, WindowsRegistryStorage, type RegistryStorage } from '../src/remote/DeviceRegistry.js';

class MemoryStorage implements RegistryStorage { data: unknown = []; load() { return this.data; } save(value: unknown) { this.data = structuredClone(value); } }
test('pairing is single use and persisted credentials authenticate only the same identity', () => {
  const storage = new MemoryStorage(); const registry = new DeviceRegistry(storage); const invite = registry.beginPairing();
  const credential = registry.pair(invite.code, 'Phone', 'user');
  assert.equal(credential.token.length, 43); assert.equal(JSON.stringify(storage.data).includes(credential.token), false);
  assert.equal(registry.authenticate(credential.deviceId, credential.token, 'user'), true);
  assert.equal(registry.authenticate(credential.deviceId, credential.token, 'other'), false);
  assert.equal(registry.authenticate(credential.deviceId, 'wrong', 'user'), false);
  assert.throws(() => registry.pair(invite.code, 'Phone', 'user'), /pairing_expired/);
  assert.throws(() => registry.beginPairing(), /One device/);
  const resumed = new DeviceRegistry(storage); assert.equal(resumed.authenticate(credential.deviceId, credential.token, 'user'), true);
  resumed.revoke(credential.deviceId); assert.equal(resumed.authenticate(credential.deviceId, credential.token, 'user'), false);
});
test('pairing expires and global attempt limiting survives invitation rotation', () => {
  let now = 0; const registry = new DeviceRegistry(new MemoryStorage(), () => now); const invite = registry.beginPairing(); now = 120000;
  assert.throws(() => registry.pair(invite.code, 'Phone', 'user'), /pairing_expired/);
  for (let i = 0; i < 4; i++) { registry.beginPairing(); assert.throws(() => registry.pair('WRONG', 'Phone', 'user'), /incorrect_code/); }
  const fresh = registry.beginPairing(); assert.throws(() => registry.pair(fresh.code, 'Phone', 'user'), /rate_limited/);
  now += 60001; assert.equal(registry.pair(fresh.code, 'Phone', 'user').token.length, 43);
});
test('failed protected writes never grant a device and corrupt stores fail closed', () => {
  const registry = new DeviceRegistry({ load: () => [], save: () => { throw new Error('disk error'); } }); const invite = registry.beginPairing();
  assert.throws(() => registry.pair(invite.code, 'Phone', 'user'), /disk error/); assert.equal(registry.list().length, 0);
  assert.throws(() => new DeviceRegistry({ load: () => [{ id: 'bad' }], save: () => {} }), /Invalid protected/);
});
test('actual Windows DPAPI registry roundtrip protects contents and preserves revocation', { skip: process.platform !== 'win32' }, () => {
  const root = resolve('.runtime/device-tests'); mkdirSync(root, { recursive: true }); const dir = mkdtempSync(join(root, 'protected-'));
  try {
    const storage = new WindowsRegistryStorage(dir); const registry = new DeviceRegistry(storage);
    const device = registry.pair(registry.beginPairing().code, 'Private phone', 'fixture-user');
    const raw = readFileSync(join(dir, 'devices.dpapi'), 'utf8'); assert.equal(raw.includes(device.token), false); assert.equal(raw.includes('Private phone'), false);
    const resumed = new DeviceRegistry(new WindowsRegistryStorage(dir)); assert.equal(resumed.authenticate(device.deviceId, device.token, 'fixture-user'), true);
    resumed.revoke(device.deviceId); assert.equal(new DeviceRegistry(storage).list().length, 0);
  } finally { assert.ok(dir.startsWith(root)); rmSync(dir, { recursive: true, force: true }); }
});
