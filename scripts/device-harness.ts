// Physical-device fixture: normal Tailscale Serve/WSS and production credentials.
// Codex activity is explicitly simulated; no inference or public/control endpoint.
import { mkdirSync, existsSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { Bridge } from '../src/core/Bridge.js';
import { CodexProcessManager, resolveCodex } from '../src/core/CodexProcessManager.js';
import { MockTransport } from '../src/core/MockTransport.js';
import { DeviceRegistry, WindowsRegistryStorage } from '../src/remote/DeviceRegistry.js';
import { RemoteServer } from '../src/remote/RemoteServer.js';
import { NotificationDispatcher } from '../src/notifications/NotificationDispatcher.js';
import { NotificationDeliveryStore } from '../src/notifications/NotificationDeliveryStore.js';
import { FirebasePushProvider } from '../src/notifications/FirebasePushProvider.js';
import { createHash } from 'node:crypto';
import { CallSessionManager } from '../src/calls/CallSessionManager.js';
import { CallEventCoordinator } from '../src/calls/CallEventCoordinator.js';
import { MediaTransportServer } from '../src/calls/MediaTransportServer.js';
import { SpeechSessionCoordinator } from '../src/speech/SpeechSessionCoordinator.js';
import { WhisperCppEngine } from '../src/speech/SpeechRecognitionEngine.js';
import { VoiceConversationController } from '../src/conversation/VoiceConversationController.js';
import { SubmissionLedger } from '../src/conversation/SubmissionLedger.js';

const real = process.env.CODEX_VOICE_DEVICE_REAL === '1';
const push = process.env.CODEX_VOICE_DEVICE_PUSH === '1';
const callSupport = process.env.CODEX_VOICE_DEVICE_CALL === '1';
const speechSupport = process.env.CODEX_VOICE_DEVICE_SPEECH === '1';
const conversationSupport=process.env.CODEX_VOICE_DEVICE_CONVERSATION==='1';
if (real && push && !conversationSupport) throw new Error('Real push requires the explicitly authorized voice fixture');
const directory = resolve('.runtime', real ? 'device-verification-real' : 'device-verification'); mkdirSync(directory, { recursive: true });
const metadata = join(directory, 'fixture.json'), control = join(directory, 'control.txt');
if (existsSync(metadata)) throw new Error('Existing device fixture must be inspected before restarting');
const endpoint = process.env.CODEX_VOICE_REMOTE_URL;
if (!endpoint) throw new Error('Actual private WSS endpoint required');
const port = Number(process.env.CODEX_VOICE_DEVICE_PORT ?? 8788);
const registry = new DeviceRegistry(new WindowsRegistryStorage(join(directory, 'registry')));
if (registry.list().length) throw new Error('Revoke previous fixture pairing before another run');
let bridge!: Bridge, remote!: RemoteServer, dispatcher: NotificationDispatcher | undefined, threadId!: string;
let calls: CallSessionManager | undefined, coordinator: CallEventCoordinator | undefined, media: MediaTransportServer | undefined;
let speech: SpeechSessionCoordinator | undefined;
let conversation:VoiceConversationController|undefined;let voiceTurnStarts=0;
let closing = false, busy = false;
async function start() {
  const realDirectory = resolve('.runtime', 'real-test');
  const transport = real ? new CodexProcessManager(await resolveCodex(), realDirectory) : new MockTransport(undefined, 150);
  if(real && conversationSupport) {
    if(existsSync(join(directory,'voice-inference-used.json')))throw new Error('Real voice inference already attempted; inspect the receipt before any further allowance use');
    const originalWrite=transport.write.bind(transport);
    transport.write=data=>{
      const message=JSON.parse(data);
      if(message.method==='turn/start') {
        if(++voiceTurnStarts>1)throw new Error('One authorized real inference only');
        writeFileSync(join(directory,'voice-inference-used.json'),JSON.stringify({turnStarts:voiceTurnStarts,attemptedAt:new Date().toISOString()}),{mode:0o600});
      }
      originalWrite(data);
    };
  }
  bridge = new Bridge(real ? 'real' : 'mock', transport, real ? join(realDirectory, 'state') : join(directory, 'state'));
  if (transport instanceof MockTransport) transport.hydrate(bridge.store.state);
  await bridge.connect();
  if (push) dispatcher = new NotificationDispatcher(bridge, registry,
    new NotificationDeliveryStore(new WindowsRegistryStorage(join(directory, 'registry'), 'notifications.dpapi')), new FirebasePushProvider(resolve('.')));
  if (callSupport) {
    calls = new CallSessionManager(new WindowsRegistryStorage(join(directory, 'registry'), 'calls.dpapi'), registry, Date.now,
      call => bridge.store.state.approvals.some(a => a.id === call.approvalId && a.status === 'pending' && Date.parse(a.expiresAt) > Date.now()));
    coordinator = new CallEventCoordinator(bridge, registry, calls);
    if (speechSupport) speech = new SpeechSessionCoordinator(calls, bridge, new WhisperCppEngine(resolve('.')));
    if(conversationSupport && speech) {
      conversation=new VoiceConversationController(bridge,calls,new SubmissionLedger(new WindowsRegistryStorage(join(directory,'registry'),'submissions.dpapi')));
      speech.onTranscription((call,utterance)=>{void conversation!.transcription(call,utterance);});
    }
    media = new MediaTransportServer(calls, endpoint!, (call, pcm, level) => speech?.frame(call, pcm, level));
  }
  remote = new RemoteServer(bridge, registry, endpoint!, port, 15000, 20000,
    dispatcher ? { registry, dispatcher, projectId: process.env.CODEX_VOICE_FIREBASE_PROJECT_ID } : undefined, calls && media ? { manager: calls, media, ...(speech ? { speech } : {}),...(conversation?{conversation}:{}) } : undefined); await remote.start();
  const prior = real ? bridge.threads.list().find(t => t.label === 'Step 2 controlled connection test') : bridge.threads.list().at(-1);
  if (real && !prior) throw new Error('Existing controlled real thread required; no inference will be submitted');
  threadId = prior ? prior.id : await bridge.create(directory, 'Physical device verification (simulated)');
  await bridge.select(threadId);
}
async function close() {
  if (closing) return; closing = true; clearInterval(poll);
  for (const device of registry.list()) { registry.revoke(device.id); remote.revoke(device.id); }
  conversation?.close();coordinator?.close(); await speech?.close(); await remote.close(); calls?.close(); await dispatcher?.close(); await bridge.close();
  for (const path of [metadata, control]) if (existsSync(path)) rmSync(path);
}
await start();
const invitation = registry.beginPairing();
const priorTurn = bridge.store.state.turns.find(t => t.threadId === threadId && t.status === 'completed');
writeFileSync(metadata, JSON.stringify({ endpoint, code: invitation.code, ...(real ? { expectedThreadId: threadId, expectedTurnId: priorTurn?.id } : {}) }), { mode: 0o600 });
console.log(real ? 'Private real snapshot fixture ready; no new inference.' : 'Private physical-device fixture ready; all task activity is simulated.');
const poll = setInterval(() => {
  if ((dispatcher || (calls && media)) && !closing) {
    writeFileSync(join(directory, 'push-status.json'), JSON.stringify({ configured: dispatcher?.configured ?? false, diagnostic: dispatcher?.diagnostic ?? 'calls_only',
      connectedDevices: remote.connectedDevices, registration: registry.list().map(d => ({ ...registry.pushState(d.id), fidHash: registry.pushTargets().find(t => t.id === d.id)?.fid ? createHash('sha256').update(registry.pushTargets().find(t => t.id === d.id)!.fid).digest('hex') : null })),
      deliveries: registry.list().flatMap(d => dispatcher?.store.diagnostics(d.id) ?? []),
      voiceTurnStarts,voiceSubmissionCount:conversation?.ledger.list().length??0,voiceSubmissionState:conversation?.ledger.list().at(-1)?.state??null,
      ...(calls && media ? { calls: calls.diagnostics(), media: media.diagnostics(), mediaConnected: media.connected } : {}) }), { mode: 0o600 });
  }
  if (busy || closing || !existsSync(control)) return;
  const command = readFileSync(control, 'utf8').trim(); rmSync(control); busy = true;
  void (async () => {
    if (real && !['offer','revoke','quit'].includes(command)) throw new Error('Real fixture supports no task controls');
    switch (command) {
      case 'offer': {
        const turn = bridge.store.state.turns.filter(t => t.threadId === threadId).at(-1);
        if (!calls || !turn) throw new Error('Stored task required');
        for (const device of registry.list()) calls.review(device.id, threadId, turn.id, real ? 'real' : 'mock');
        break;
      }
      case 'complete': await bridge.send(threadId, '[mock:duplicate]'); break;
      case 'failure': await bridge.send(threadId, '[mock:fail]'); break;
      case 'waiting': await bridge.send(threadId, '[mock:wait]'); break;
      case 'interrupt': { const turn = bridge.turns.active(threadId); if (turn) await bridge.turns.interrupt(threadId, turn.id); break; }
      case 'approval': await bridge.send(threadId, '[mock:approval]'); break;
      case 'decline': for (const approval of bridge.approvals.list()) if (approval.status === 'pending') bridge.approvals.resolve(approval.id, false); break;
      case 'restart': coordinator?.close(); await speech?.close(); await remote.close(); calls?.close(); await dispatcher?.close(); await bridge.close(); await start(); break;
      case 'offline': await remote.close(); break;
      case 'online': if (calls) media = new MediaTransportServer(calls, endpoint!, (call, pcm, level) => speech?.frame(call, pcm, level)); remote = new RemoteServer(bridge, registry, endpoint!, port, 15000, 20000,
        dispatcher ? { registry, dispatcher, projectId: process.env.CODEX_VOICE_FIREBASE_PROJECT_ID } : undefined, calls && media ? { manager: calls, media, ...(speech ? { speech } : {}) } : undefined); await remote.start(); break;
      case 'revoke': for (const device of registry.list()) { registry.revoke(device.id); remote.revoke(device.id); } break;
      case 'quit': await close(); break;
      default: throw new Error('Unknown fixed fixture control');
    }
  })().catch(() => { console.error('Device fixture command failed'); void close(); }).finally(() => { busy = false; });
}, 100);
process.on('SIGINT', () => { void close(); }); process.on('SIGTERM', () => { void close(); });
