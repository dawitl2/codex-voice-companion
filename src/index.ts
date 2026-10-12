import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { Bridge } from './core/Bridge.js';
import { MockTransport } from './core/MockTransport.js';
import { CodexProcessManager, resolveCodex } from './core/CodexProcessManager.js';
import { projectDirectory } from './config/settings.js';
import { runTerminal } from './cli/Terminal.js';
import { errorText } from './utils/safe.js';
import { RemoteServer } from './remote/RemoteServer.js';
import { DeviceRegistry, WindowsRegistryStorage } from './remote/DeviceRegistry.js';
import { NotificationDispatcher } from './notifications/NotificationDispatcher.js';
import { NotificationDeliveryStore } from './notifications/NotificationDeliveryStore.js';
import { FirebasePushProvider } from './notifications/FirebasePushProvider.js';
import { CallSessionManager } from './calls/CallSessionManager.js';
import { CallEventCoordinator } from './calls/CallEventCoordinator.js';
import { MediaTransportServer } from './calls/MediaTransportServer.js';
import { SpeechSessionCoordinator } from './speech/SpeechSessionCoordinator.js';
import { WhisperCppEngine } from './speech/SpeechRecognitionEngine.js';
import { VoiceConversationController } from './conversation/VoiceConversationController.js';
import { SubmissionLedger } from './conversation/SubmissionLedger.js';
import {TaskControl} from './control/TaskControl.js';

async function main(): Promise<void> {
  if (Number(process.versions.node.split('.')[0]) !== 24) throw new Error('Node.js 24 LTS required; use scripts/run.ps1 or install Node 24');
  const args = process.argv.slice(2);
  if (args.includes('--help')) { console.log('Usage: node dist/src/index.js [--mock | --real] [--workspace-write] [--remote] [--notifications] [--calls] [--speech] [--conversation]\nRemote requires CODEX_VOICE_REMOTE_URL=wss://actual-pc.actual-tailnet.ts.net/remote; optional CODEX_VOICE_REMOTE_PORT=8787.\nNotifications require --remote, CODEX_VOICE_FIREBASE_PROJECT_ID, GOOGLE_APPLICATION_CREDENTIALS and CODEX_VOICE_FIREBASE_SPARK_CONFIRMED=1. Calls require --remote and explicit phone opt-in; --speech enables local English recognition; --conversation adds confirmed managed-thread instructions. Codex approvals remain on the PC.'); return; }
  if (args.some(x => !['--mock','--real','--workspace-write','--remote','--notifications','--calls','--speech','--conversation','--controls'].includes(x)) || (args.includes('--mock') && args.includes('--real')) || ((args.includes('--notifications') || args.includes('--calls')) && !args.includes('--remote'))) throw new Error('Invalid CLI options; use --help');
  if (args.includes('--speech') && !args.includes('--calls')) throw new Error('--speech requires --calls and --remote');
  if (args.includes('--conversation') && !args.includes('--speech')) throw new Error('--conversation requires --speech --calls --remote');
  if(args.includes('--controls')&&!args.includes('--conversation'))throw new Error('--controls requires --conversation --speech --calls --remote');
  const mode = args.includes('--real') ? 'real' : 'mock';
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
  const cwd = projectDirectory(root); const directory = resolve(root, '.runtime', mode);
  // Mock mode hydrates its own fixture history only. The real adapter never reads private Codex transcripts.
  const transport = mode === 'real' ? new CodexProcessManager(await resolveCodex(), cwd) : new MockTransport(undefined, 200);
  const bridge = new Bridge(mode, transport, directory, args.includes('--workspace-write') ? 'workspace-write' : 'read-only');
  if (transport instanceof MockTransport) transport.hydrate(bridge.store.state);
  let remote: RemoteServer | undefined; let devices: DeviceRegistry | undefined; let notifications: NotificationDispatcher | undefined;
  let speech: SpeechSessionCoordinator | undefined;
  let conversation: VoiceConversationController | undefined;
  let controls:TaskControl|undefined;
  let calls: CallSessionManager | undefined, coordinator: CallEventCoordinator | undefined, media: MediaTransportServer | undefined;
  try {
    if (args.includes('--remote')) {
      const endpoint = process.env.CODEX_VOICE_REMOTE_URL;
      if (!endpoint) throw new Error('Set CODEX_VOICE_REMOTE_URL to the actual private Tailscale WSS endpoint');
      devices = new DeviceRegistry(new WindowsRegistryStorage(resolve(root, '.runtime', 'remote', mode)));
      const provider = args.includes('--notifications') ? new FirebasePushProvider(root) : undefined;
      notifications = new NotificationDispatcher(bridge, devices, new NotificationDeliveryStore(new WindowsRegistryStorage(resolve(root, '.runtime', 'remote', mode), 'notifications.dpapi')), provider);
      if (args.includes('--calls')) {
        calls = new CallSessionManager(new WindowsRegistryStorage(resolve(root, '.runtime', 'remote', mode), 'calls.dpapi'), devices, Date.now,
          call => bridge.store.state.approvals.some(a => a.id === call.approvalId && a.status === 'pending' && Date.parse(a.expiresAt) > Date.now()));
        coordinator = new CallEventCoordinator(bridge, devices, calls);
        if (args.includes('--speech')) speech = new SpeechSessionCoordinator(calls, bridge, new WhisperCppEngine(root));
        if (args.includes('--conversation') && speech) {
          const ledger=new SubmissionLedger(new WindowsRegistryStorage(resolve(root,'.runtime','remote',mode),'submissions.dpapi'));
          if(args.includes('--controls'))controls=new TaskControl(bridge,ledger,new WindowsRegistryStorage(resolve(root,'.runtime','remote',mode),'controls.dpapi'),id=>devices!.hasDevice(id));
          conversation = new VoiceConversationController(bridge, calls, ledger,Date.now,controls);
          speech.onTranscription((call, utterance) => { void conversation!.transcription(call, utterance); });
        }
        media = new MediaTransportServer(calls, endpoint, speech ? (call, pcm, level) => speech!.frame(call, pcm, level) : undefined);
      }
      remote = new RemoteServer(bridge, devices, endpoint, Number(process.env.CODEX_VOICE_REMOTE_PORT ?? 8787), 15000, 20000,
        { registry: devices, dispatcher: notifications, projectId: process.env.CODEX_VOICE_FIREBASE_PROJECT_ID }, calls && media ? { manager: calls, media, ...(speech ? { speech } : {}), ...(conversation ? { conversation } : {}) } : undefined,controls);
      await remote.start(); console.log(`Remote monitor: 127.0.0.1:${remote.address} through ${remote.endpoint.href}`);
    }
    await runTerminal(bridge, remote, devices, notifications, calls, media);
  } finally { controls?.close(); conversation?.close(); speech?.close(); coordinator?.close(); await remote?.close(); await media?.close(); calls?.close(); await notifications?.close(); await bridge.close(); }
}
main().catch(error => { console.error(errorText(error)); process.exitCode = 1; });
