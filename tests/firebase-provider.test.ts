import test from 'node:test';
import assert from 'node:assert/strict';
import { firebaseConfiguration, firebaseFailure, firebaseMessage } from '../src/notifications/FirebasePushProvider.js';
import { eligible, pushPayload } from '../src/notifications/NotificationPolicy.js';
test('Firebase requires explicit no-billing confirmation, project and outside-Git ADC path', () => {
  const env = { CODEX_VOICE_FIREBASE_SPARK_CONFIRMED: '1', CODEX_VOICE_FIREBASE_PROJECT_ID: 'codex-voice-example', GOOGLE_APPLICATION_CREDENTIALS: 'C:\\outside\\service-account.json' };
  assert.equal(firebaseConfiguration(env).projectId, 'codex-voice-example');
  for (const bad of [{}, { ...env, CODEX_VOICE_FIREBASE_SPARK_CONFIRMED: '0' }, { ...env, CODEX_VOICE_FIREBASE_PROJECT_ID: 'invalid project' }, { ...env, GOOGLE_APPLICATION_CREDENTIALS: 'relative.json' }]) assert.throws(() => firebaseConfiguration(bad));
});
test('official Admin FID message uses high priority data only, bounded TTL and intended Android package', () => {
  const timestamp = new Date().toISOString(); const event = eligible({ id: 'local', type: 'turn.completed', timestamp, summary: 'SECRET', threadId: 'thread', turnId: 'turn' }, 'mock', Date.now())!;
  const message = firebaseMessage('c' + 'A'.repeat(21), pushPayload(event, 'paired', 'a'.repeat(64)), 10000);
  assert.equal(message.fid.length, 22); assert.equal(message.android?.priority, 'high'); assert.equal(message.android?.ttl, 10000);
  assert.equal(message.notification, undefined); assert.equal(message.android?.restrictedPackageName, 'com.codexvoice.app');
  assert.equal(JSON.stringify(message).includes('SECRET'), false);
  assert.throws(() => firebaseMessage(message.fid, message.data as never, 0));
});
test('provider failures expose only fixed classifications and stale installations are removed', () => {
  assert.equal(firebaseFailure({ code: 'messaging/registration-token-not-registered', message: 'SECRET' }).code, 'unregistered');
  assert.equal(firebaseFailure({ code: 'messaging/server-unavailable' }).code, 'temporary');
  assert.equal(firebaseFailure({ code: 'messaging/authentication-error', message: 'PRIVATE KEY' }).message, 'configuration');
});
