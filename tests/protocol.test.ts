import test from 'node:test';
import assert from 'node:assert/strict';
import { envelope, parseClient, encode, SERVER_LIMIT } from '../src/remote/Protocol.js';
import { sanitize } from '../src/utils/safe.js';

test('protocol v1 parses only the narrow monitoring, pairing and authentication contract', () => {
  for (const type of ['state.snapshot.request', 'thread.list.request', 'task.history.request', 'connection.status.request', 'heartbeat', 'disconnect']) {
    assert.equal(parseClient(JSON.stringify(envelope(type, {}))).type, type);
  }
  assert.equal(parseClient(JSON.stringify(envelope('client.hello', { name: 'Android' }))).type, 'client.hello');
  assert.equal(parseClient(JSON.stringify(envelope('pair.request', { code: '0123456789ABCDEF' }))).type, 'pair.request');
});
test('protocol requires ISO timestamps and known GitHub credentials are sanitized', () => {
  assert.throws(() => parseClient(JSON.stringify({ ...envelope('heartbeat', {}), timestamp: '123' })), /invalid_message/);
  assert.equal(sanitize('ghp_' + 'a'.repeat(36)), '[REDACTED]');
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

test('outbound validation rejects invalid snapshots, private extra fields and unknown message types', () => {
  const snapshot = { epoch: 'epoch', sequence: 0, bridgeName: 'PC', available: true, mode: 'mock', selectedThreadId: null,
    threads: [], turns: [], history: [], historyComplete: false, syncedAt: new Date().toISOString() };
  assert.equal(JSON.parse(encode('state.snapshot', snapshot)).payload.available, true);
  for (const bad of [{ ...snapshot, sequence: -1 }, { ...snapshot, prompt: 'private' }, { ...snapshot, selectedThreadId: 'missing' },
    { ...snapshot, threads: [{ id: 'x', label: 'Task', project: 'Project', status: 'running' }] },
    { ...snapshot, turns: [{ id: 't', threadId: 'missing', status: 'completed', startedAt: snapshot.syncedAt, finishedAt: null }] }]) {
    assert.throws(() => encode('state.snapshot', bad), /invalid_message/);
  }
  assert.throws(() => encode('turn/start', {}), /unknown_message/);
  assert.throws(() => encode('auth.success', { deviceId: 'invalid', bridgeName: 'PC' }), /invalid_message/);
});
