import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { CallSessionManager } from '../src/calls/CallSessionManager.js';
import { audioFrame, validateAudioFrame, AudioSequence, BoundedAudioQueue, PCM } from '../src/calls/AudioProtocol.js';
import { encode, envelope, parseClient } from '../src/remote/Protocol.js';
import type { NotificationEvent } from '../src/notifications/NotificationPolicy.js';

function fixture(t: test.TestContext) {
  let time = Date.parse('2026-10-12T00:00:00Z'), data: unknown = [], paired = true, pending = true;
  const device = randomUUID(); const registry = { hasDevice: (id: string, identity?: string) => paired && id === device && (identity === undefined || identity === 'owner') };
  const storage = { load: () => structuredClone(data), save: (value: unknown) => { data = structuredClone(value); } };
  const manager = new CallSessionManager(storage, registry, () => time, () => pending);
  t.after(() => manager.close());
  const event = (key = 'a', kind: NotificationEvent['kind'] = 'completed'): NotificationEvent => ({ eventId: key.repeat(64), threadId: 'thread', turnId: key, kind, mode: 'mock', occurredAt: new Date(time).toISOString(), expiresAt: new Date(time + (kind === 'approval' ? 60000 : 1800000)).toISOString() });
  const offer = () => { manager.setPreferences(device, true); return manager.offer(device, event())!; };
  return { manager, device, storage, registry, event, offer, advance: (ms: number) => { time += ms; }, revoke: () => { paired = false; }, resolveApproval: () => { pending = false; }, now: () => time };
}
test('call invitations are opt-in, minimal, bounded and deduplicated by the actual notification event', t => {
  const f = fixture(t); assert.equal(f.manager.offer(f.device, f.event()), null); const offer = f.offer();
  assert.equal(offer.state, 'offered'); assert.equal(Date.parse(offer.expiresAt) - f.now(), 120000);
  assert.equal(f.manager.offer(f.device, f.event())?.callId, offer.callId);
  assert.equal(f.manager.offer(f.device, f.event('b')), null); assert.equal('deviceId' in offer, false);
});

test('local stored-task reviews require opt-in, preserve real provenance and are visible only to their owner', t => {
  const f=fixture(t); assert.throws(()=>f.manager.review(f.device,'thread','stored-turn','real'),/invitations_disabled/);
  f.manager.setPreferences(f.device,true); const review=f.manager.review(f.device,'thread','stored-turn','real');
  assert.equal(review.kind,'review'); assert.equal(review.mode,'real'); assert.equal(review.reason,'manual_task_review'); assert.equal(review.turnId,'stored-turn');
  assert.equal(f.manager.current(f.device)?.callId,review.callId); assert.throws(()=>f.manager.current(randomUUID()),/revoked/);
  assert.throws(()=>f.manager.review(f.device,'thread','another-turn','real'),/call_busy/);
  parseClient(JSON.stringify(envelope('call.current.request',{})));
  f.manager.end(f.device,review.callId); assert.equal(f.manager.current(f.device),null);
});
test('offers expire independently of still-available completion history', t => {
  const f = fixture(t), offer = f.offer(); f.advance(120000); assert.equal(f.manager.byEvent(f.device, offer.eventId)?.state, 'expired');
  assert.throws(() => f.manager.accept(f.device, offer.callId, 'owner'), /call_not_available/);
});
test('approval invitations cannot extend the original approval deadline or survive resolution', t => {
  const f = fixture(t); f.manager.setPreferences(f.device, true); const offer = f.manager.offer(f.device, f.event('a', 'approval'))!;
  assert.equal(Date.parse(offer.expiresAt) - f.now(), 60000); f.resolveApproval(); assert.equal(f.manager.status(f.device, offer.callId).state, 'expired');
});
test('answer cannot activate before one-time authenticated media setup succeeds', t => {
  const f = fixture(t), offer = f.offer(); const grant = f.manager.accept(f.device, offer.callId, 'owner');
  assert.equal(grant.call.state, 'connecting'); assert.equal(f.manager.accept(f.device, offer.callId, 'owner').token, grant.token);
  assert.throws(() => f.manager.activate(f.device, offer.callId), /invalid_transition/);
  assert.throws(() => f.manager.consume(grant.token, 'other'), /media_unauthorized/);
  assert.equal(f.manager.consume(grant.token, 'owner').callId, offer.callId);
  assert.throws(() => f.manager.consume(grant.token, 'owner'), /media_unauthorized/);
  f.manager.activate(f.device, offer.callId); assert.equal(f.manager.status(f.device, offer.callId).state, 'active');
});
test('wrong device, wrong tailnet identity and revoked device cannot answer or consume a grant', t => {
  const f = fixture(t), offer = f.offer(); assert.throws(() => f.manager.accept(randomUUID(), offer.callId, 'owner'), /revoked/);
  assert.throws(() => f.manager.accept(f.device, offer.callId, 'other'), /wrong_identity/);
  const grant = f.manager.accept(f.device, offer.callId, 'owner'); f.revoke();
  assert.throws(() => f.manager.consume(grant.token, 'owner'), /media_unauthorized/); assert.equal(f.manager.diagnostics()[0]?.reason, 'revoked');
});
test('expired media grants and abandoned connections fail with no active zombie call', t => {
  const f = fixture(t), offer = f.offer(), grant = f.manager.accept(f.device, offer.callId, 'owner'); f.advance(15000);
  assert.throws(() => f.manager.consume(grant.token, 'owner'), /media_unauthorized/); assert.equal(f.manager.status(f.device, offer.callId).state, 'failed');
});
test('decline is idempotent and rejects invalid transitions', t => {
  const f = fixture(t), offer = f.offer(); assert.equal(f.manager.decline(f.device, offer.callId).state, 'declined');
  assert.equal(f.manager.decline(f.device, offer.callId).state, 'declined'); assert.throws(() => f.manager.activate(f.device, offer.callId), /invalid_transition/);
});
test('hangup is idempotent, revokes pending media authorization and frees the device for a new offer', t => {
  const f = fixture(t), offer = f.offer(), grant = f.manager.accept(f.device, offer.callId, 'owner');
  assert.equal(f.manager.end(f.device, offer.callId).state, 'ended'); const ended = f.manager.end(f.device, offer.callId); assert.equal(ended.endedAt, f.manager.status(f.device, offer.callId).endedAt);
  assert.throws(() => f.manager.consume(grant.token, 'owner')); assert.ok(f.manager.offer(f.device, f.event('b')));
});
test('mute applies only to an authenticated active session', t => {
  const f = fixture(t), offer = f.offer(); assert.throws(() => f.manager.mute(f.device, offer.callId, true), /call_not_active/);
  const grant = f.manager.accept(f.device, offer.callId, 'owner'); f.manager.consume(grant.token, 'owner'); f.manager.activate(f.device, offer.callId);
  f.manager.mute(f.device, offer.callId, true); assert.equal(f.manager.status(f.device, offer.callId).muted, true);
});
test('media recovery uses a fresh one-time grant and is bounded to two attempts', t => {
  const f = fixture(t), offer = f.offer(); let grant = f.manager.accept(f.device, offer.callId, 'owner');
  f.manager.consume(grant.token, 'owner'); f.manager.activate(f.device, offer.callId);
  for (let i = 0; i < 2; i++) { f.manager.disconnect(f.device, offer.callId); const next = f.manager.accept(f.device, offer.callId, 'owner', true); assert.notEqual(next.token, grant.token); grant = next; f.manager.consume(next.token, 'owner'); f.manager.activate(f.device, offer.callId); }
  f.manager.disconnect(f.device, offer.callId); assert.throws(() => f.manager.accept(f.device, offer.callId, 'owner', true), /recovery_exhausted/);
});
test('lost media without safe reauthorization terminates promptly', t => {
  const f = fixture(t), offer = f.offer(), grant = f.manager.accept(f.device, offer.callId, 'owner'); f.manager.consume(grant.token, 'owner'); f.manager.activate(f.device, offer.callId);
  f.manager.disconnect(f.device, offer.callId); f.advance(15000); assert.equal(f.manager.status(f.device, offer.callId).reason, 'connection_timeout');
});
test('restart preserves terminal deduplication and fails live calls without persisting media tokens', t => {
  const f = fixture(t), offer = f.offer(), grant = f.manager.accept(f.device, offer.callId, 'owner');
  assert.equal(JSON.stringify(f.storage.load()).includes(grant.token), false);
  const restored = new CallSessionManager(f.storage, f.registry, f.now); t.after(() => restored.close());
  assert.equal(restored.status(f.device, offer.callId).reason, 'bridge_restarted'); assert.equal(restored.offer(f.device, f.event())?.callId, offer.callId);
});
test('disabling invitations expires offers without altering an already active user session', t => {
  const f = fixture(t), offer = f.offer(); f.manager.setPreferences(f.device, false); assert.equal(f.manager.status(f.device, offer.callId).reason, 'invitations_disabled');
  f.manager.setPreferences(f.device, true); const active = f.manager.offer(f.device, f.event('b'))!;
  const grant = f.manager.accept(f.device, active.callId, 'owner'); f.manager.consume(grant.token, 'owner'); f.manager.activate(f.device, active.callId);
  f.manager.setPreferences(f.device, false); assert.equal(f.manager.status(f.device, active.callId).state, 'active');
});
test('maximum active duration is bounded even with a healthy media connection', t => {
  const f = fixture(t), offer = f.offer(), grant = f.manager.accept(f.device, offer.callId, 'owner');
  f.manager.consume(grant.token, 'owner'); f.manager.activate(f.device, offer.callId); f.advance(1800000);
  assert.equal(f.manager.status(f.device, offer.callId).reason, 'duration_limit');
});
test('PCM frames have a fixed endian-safe codec boundary and measured sample level', () => {
  const pcm = Buffer.alloc(640); for (let i = 0; i < 640; i += 2) pcm.writeInt16LE(16384, i);
  const frame = audioFrame(7, 1234, pcm, 0), parsed = validateAudioFrame(frame, 0);
  assert.equal(frame.length, 660); assert.equal(frame.subarray(0, 20).toString('hex'), '4356010007000000d20400000000000040010000');
  assert.equal(parsed.sequence, 7); assert.equal(parsed.timestamp, 1234); assert.equal(parsed.level, .5); assert.deepEqual(parsed.pcm, pcm);
});
test('oversized, malformed, wrong-direction and unsafe timestamp audio is rejected', () => {
  const frame = audioFrame(0, 0, Buffer.alloc(640), 0);
  for (const bad of [frame.subarray(1), Buffer.concat([frame, Buffer.alloc(1)]), Buffer.alloc(660)]) assert.throws(() => validateAudioFrame(bad, 0));
  assert.throws(() => validateAudioFrame(frame, 1)); const corrupt = Buffer.from(frame); corrupt.writeBigUInt64LE(2n ** 63n, 8); assert.throws(() => validateAudioFrame(corrupt, 0));
});
test('sequence validation rejects replay and clock reversal while counting gaps and stale frames', () => {
  const stream = new AudioSequence(); assert.equal(stream.accept(0, 1000, 0), true); assert.equal(stream.accept(2, 1040, 40), true); assert.equal(stream.dropped, 1);
  assert.throws(() => stream.accept(2, 1060, 60), /audio_sequence_error/); assert.equal(stream.sequenceErrors, 1);
  assert.equal(stream.accept(3, 1060, 1000), false); assert.equal(stream.relativeDelayMs, 940);
});
test('media rate and bounded queues drop stale backlog rather than growing memory', () => {
  const stream = new AudioSequence(); for (let i = 0; i < 150; i++) stream.accept(i, i * 20, 0); assert.throws(() => stream.accept(150, 3000, 0), /audio_rate_limited/);
  const queue = new BoundedAudioQueue<number>(3); for (let i = 0; i < 100; i++) queue.push(i);
  assert.equal(queue.size, 3); assert.equal(queue.dropped, 97); assert.equal(queue.shift(), 97); queue.clear(); assert.equal(queue.size, 0);
});
test('protocol v2 call extension is request-driven, rejects foreign targets and preserves strict media negotiation', t => {
  const f = fixture(t), offer = f.offer();
  assert.equal(parseClient(JSON.stringify(envelope('call.accept', { callId: offer.callId }))).type, 'call.accept');
  for (const payload of [{ callId: offer.callId, deviceId: randomUUID() }, { callId: 'guess' }, { callId: offer.callId, command: 'approve' }]) assert.throws(() => parseClient(JSON.stringify(envelope('call.accept', payload))));
  assert.doesNotThrow(() => encode('call.status.response', { enabled: true, call: offer }));
  const grant = f.manager.accept(f.device, offer.callId, 'owner'); const payload = { ...grant, mediaUrl: 'wss://test.example.ts.net/media', format: PCM };
  assert.doesNotThrow(() => encode('call.media.response', payload)); assert.throws(() => encode('call.media.response', { ...payload, mediaUrl: payload.mediaUrl + '?token=secret' }));
  assert.throws(() => encode('call.media.response', { ...payload, format: { ...PCM, sampleRate: 48000 } }));
});
