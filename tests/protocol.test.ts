import test from 'node:test';
import assert from 'node:assert/strict';
import { envelope, parseClient, encode, SERVER_LIMIT } from '../src/remote/Protocol.js';

test('protocol v1 parses only the narrow monitoring, pairing and authentication contract', () => {
  for (const type of ['state.snapshot.request', 'thread.list.request', 'task.history.request', 'connection.status.request', 'heartbeat', 'disconnect']) {
    assert.equal(parseClient(JSON.stringify(envelope(type, {}))).type, type);
  }
  assert.equal(parseClient(JSON.stringify(envelope('client.hello', { name: 'Android' }))).type, 'client.hello');
  assert.equal(parseClient(JSON.stringify(envelope('pair.request', { code: '0123456789ABCDEF' }))).type, 'pair.request');
});
test('protocol rejects unknown versions, extra fields, malformed JSON, wrong payloads and privileged RPC', () => {
  for (const raw of ['{}', 'null', '[1]', '{', JSON.stringify({ ...envelope('heartbeat', {}), version: 2 }),
    JSON.stringify(envelope('turn/start', {})), JSON.stringify(envelope('heartbeat', { command: 'whoami' })),
    JSON.stringify(envelope('auth.request', { deviceId: 'x', token: 'short' })),
    JSON.stringify({ ...envelope('heartbeat', {}), token: 'unexpected' })]) assert.throws(() => parseClient(raw));
});
test('protocol limits UTF-8 input and output sizes and never reflects input in errors', () => {
  assert.throws(() => parseClient('😀'.repeat(3000)), /message_too_large/);
  assert.throws(() => encode('state.snapshot', 'x'.repeat(SERVER_LIMIT)), /snapshot_too_large/);
  assert.throws(() => parseClient(JSON.stringify(envelope('pair.request', { code: 'secret-not-a-code' }))), /^Error: invalid_message$/);
});
