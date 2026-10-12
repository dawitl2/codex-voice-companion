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

async function main(): Promise<void> {
  if (Number(process.versions.node.split('.')[0]) !== 24) throw new Error('Node.js 24 LTS required; use scripts/run.ps1 or install Node 24');
  const args = process.argv.slice(2);
  if (args.includes('--help')) { console.log('Usage: node dist/src/index.js [--mock | --real] [--workspace-write] [--remote] [--notifications]\nRemote requires CODEX_VOICE_REMOTE_URL=wss://actual-pc.actual-tailnet.ts.net/remote; optional CODEX_VOICE_REMOTE_PORT=8787.\nNotifications require --remote, CODEX_VOICE_FIREBASE_PROJECT_ID, GOOGLE_APPLICATION_CREDENTIALS and CODEX_VOICE_FIREBASE_SPARK_CONFIRMED=1.'); return; }
  if (args.some(x => !['--mock','--real','--workspace-write','--remote','--notifications'].includes(x)) || (args.includes('--mock') && args.includes('--real')) || (args.includes('--notifications') && !args.includes('--remote'))) throw new Error('Invalid CLI options; use --help');
  const mode = args.includes('--real') ? 'real' : 'mock';
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
  const cwd = projectDirectory(root); const directory = resolve(root, '.runtime', mode);
  // Mock mode hydrates its own fixture history only. The real adapter never reads private Codex transcripts.
  const transport = mode === 'real' ? new CodexProcessManager(await resolveCodex(), cwd) : new MockTransport(undefined, 200);
  const bridge = new Bridge(mode, transport, directory, args.includes('--workspace-write') ? 'workspace-write' : 'read-only');
  if (transport instanceof MockTransport) transport.hydrate(bridge.store.state);
  let remote: RemoteServer | undefined; let devices: DeviceRegistry | undefined; let notifications: NotificationDispatcher | undefined;
  try {
    if (args.includes('--remote')) {
      const endpoint = process.env.CODEX_VOICE_REMOTE_URL;
      if (!endpoint) throw new Error('Set CODEX_VOICE_REMOTE_URL to the actual private Tailscale WSS endpoint');
      devices = new DeviceRegistry(new WindowsRegistryStorage(resolve(root, '.runtime', 'remote', mode)));
      const provider = args.includes('--notifications') ? new FirebasePushProvider(root) : undefined;
      notifications = new NotificationDispatcher(bridge, devices, new NotificationDeliveryStore(new WindowsRegistryStorage(resolve(root, '.runtime', 'remote', mode), 'notifications.dpapi')), provider);
      remote = new RemoteServer(bridge, devices, endpoint, Number(process.env.CODEX_VOICE_REMOTE_PORT ?? 8787), 15000, 20000,
        { registry: devices, dispatcher: notifications, projectId: process.env.CODEX_VOICE_FIREBASE_PROJECT_ID });
      await remote.start(); console.log(`Remote monitor: 127.0.0.1:${remote.address} through ${remote.endpoint.href}`);
    }
    await runTerminal(bridge, remote, devices, notifications);
  } finally { await remote?.close(); await notifications?.close(); await bridge.close(); }
}
main().catch(error => { console.error(errorText(error)); process.exitCode = 1; });
