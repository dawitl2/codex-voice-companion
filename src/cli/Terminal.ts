import { createInterface } from 'node:readline';
import { Bridge } from '../core/Bridge.js';
import { errorText, sanitize } from '../utils/safe.js';
import { summarize } from '../core/SummaryFormatter.js';
import type { RemoteServer } from '../remote/RemoteServer.js';
import type { DeviceRegistry } from '../remote/DeviceRegistry.js';
import type { NotificationDispatcher } from '../notifications/NotificationDispatcher.js';
import type { CallSessionManager } from '../calls/CallSessionManager.js';
import type { MediaTransportServer } from '../calls/MediaTransportServer.js';

export const HELP = `Codex Voice Bridge — local terminal commands
status                       Connection, auth, selected thread and active turn
threads                      Managed threads in this mode
new "C:\\path to project" label  Create and select a thread (quoted path required)
select <thread-id>            Select/resume an original managed conversation
send <instruction>           Start a turn on the explicitly selected thread
watch on|off                 Toggle streamed events (default on)
history                      Recent recorded events
summary                      Most recent turn on selected thread
approvals                    List pending approval IDs and action descriptions
approve <approval-id>         Explicit one-action approval
decline <approval-id>         Reject one action
stop                         Request interruption of selected active turn
reconnect                    Restart only the transport; select/resume before send
pair-device                  Issue one two-minute invitation (--remote required)
devices                      List paired devices (no credentials)
notification-status          Non-sensitive push attempts and device observations
call-status                  Call states and PCM metrics; no audio content
audio-test <call-id>          One brief quiet PC-generated tone on an active call
end-call <call-id>            End the selected device's audio session
revoke-device <device-id>     Revoke and disconnect a paired device
help                         Show this help
exit                         Cancel pending approvals and shut down
Mock fixtures: send [mock:fail], [mock:approval], [mock:wait], [mock:crash], [mock:duplicate]
This manages companion conversations; it does not attach to live VS Code sessions.`;

export async function runTerminal(bridge: Bridge, remote?: RemoteServer, devices?: DeviceRegistry, notifications?: NotificationDispatcher, calls?: CallSessionManager, media?: MediaTransportServer): Promise<void> {
  console.log(`Codex Voice Bridge 0.1.0 | ${bridge.mode.toUpperCase()} | Node ${process.versions.node}`);
  console.log(HELP);
  let watching = true;
  const unsubscribe = bridge.events.subscribe(event => {
    if (watching || event.type === 'approval.requested') console.log(`[${event.type}] ${event.threadId ?? ''} ${event.turnId ?? ''} ${sanitize(event.summary)}`);
  });
  try { await bridge.connect(); } catch (error) { console.error(`Connection unavailable: ${errorText(error)}. Mock mode is available; use --mock.`); }
  const terminal = createInterface({ input: process.stdin, output: process.stdout, terminal: Boolean(process.stdin.isTTY) });
  let exiting = false; let chain = Promise.resolve();
  const shutdown = () => { if (!exiting) { exiting = true; terminal.close(); } };
  const signal = () => shutdown(); process.on('SIGINT', signal); process.on('SIGTERM', signal); terminal.on('SIGINT', signal);
  terminal.setPrompt('codex-voice> '); terminal.prompt();
  const command = async (line: string) => {
    if (exiting) return;
    const match = /^\s*(\S+)(?:\s+([\s\S]*))?$/.exec(line); if (!match) return;
    const verb = match[1]?.toLowerCase(); const rest = match[2]?.trim() ?? '';
    switch (verb) {
      case 'help': console.log(HELP); break;
      case 'status': {
        const selected = bridge.threads.activeId;
        console.log(JSON.stringify({ mode: bridge.mode, bridge: 'running', connection: bridge.client.ready ? 'ready' : 'disconnected', authentication: bridge.authentication, plan: bridge.plan, activeThread: selected ?? null, activeTurn: selected ? bridge.turns.active(selected) ?? null : null, versions: bridge.versionInfo() }, null, 2)); break;
      }
      case 'threads': console.table(bridge.threads.list()); break;
      case 'new': {
        const args = /^"([^"]+)"(?:\s+(.*))?$/.exec(rest); if (!args?.[1]) throw new Error('Usage: new "absolute project directory" label');
        console.log('Thread:', await bridge.create(args[1], args[2] ?? 'Untitled')); break;
      }
      case 'select': await bridge.select(rest); console.log('Selected:', rest); break;
      case 'send': console.log('Turn:', await bridge.send(bridge.threads.selected().id, rest)); break;
      case 'stop': {
        const thread = bridge.threads.selected(); const turn = bridge.turns.active(thread.id); if (!turn) throw new Error('No active turn');
        await bridge.turns.interrupt(thread.id, turn.id); console.log('Interruption requested; waiting for terminal event'); break;
      }
      case 'watch': if (!['on','off'].includes(rest)) throw new Error('Usage: watch on|off'); watching = rest === 'on'; break;
      case 'history': for (const event of bridge.history().slice(-30)) console.log(JSON.stringify(event)); break;
      case 'summary': {
        const thread = bridge.threads.selected(); const turn = bridge.store.state.turns.filter(t => t.threadId === thread.id).at(-1); console.log(turn ? summarize(thread, turn) : 'No recorded turn'); break;
      }
      case 'approvals': console.table(bridge.approvals.list()); break;
      case 'approve': bridge.approvals.resolve(rest, true); break;
      case 'decline': bridge.approvals.resolve(rest, false); break;
      case 'reconnect': await bridge.client.close(); await bridge.connect(); console.log('Transport connected; explicitly select a thread to resume'); break;
      case 'pair-device': {
        if (!remote || !devices) throw new Error('Start with --remote and a private WSS endpoint first');
        const invitation = devices.beginPairing();
        console.log(`Private address: ${remote.endpoint.href}\nOne-time pairing code: ${invitation.code}\nExpires: ${invitation.expiresAt}. Enter only in your Android app; do not share or log this invitation.`); break;
      }
      case 'devices': if (!devices) throw new Error('Remote monitoring is not enabled'); console.table(devices.list()); break;
      case 'notification-status':
        if (!notifications || !devices) throw new Error('Remote monitoring is not enabled');
        console.log(`Push provider: ${notifications.diagnostic}; configured=${notifications.configured}`);
        for (const device of devices.list()) console.log(JSON.stringify({ deviceId: device.id, ...devices.pushState(device.id), deliveries: notifications.store.diagnostics(device.id) }));
        break;
      case 'call-status': if (!calls || !media) throw new Error('Start with --remote --calls'); console.log(JSON.stringify({ calls: calls.diagnostics(), media: media.diagnostics() })); break;
      case 'audio-test': if (!media) throw new Error('Calls are disabled'); media.tone(rest); console.log('Quiet 600 ms PC tone requested. No AI speech.'); break;
      case 'end-call': {
        if (!calls || !devices) throw new Error('Calls are disabled');
        const device = devices.list()[0]; if (!device) throw new Error('No paired device'); calls.end(device.id, rest); console.log('Call ended'); break;
      }
      case 'revoke-device': if (!remote || !devices) throw new Error('Remote monitoring is not enabled'); devices.revoke(rest); remote.revoke(rest); console.log('Device revoked'); break;
      case 'exit': shutdown(); break;
      default: throw new Error('Unknown command; type help');
    }
    remote?.publish();
  };
  await new Promise<void>(resolve => {
    terminal.on('line', line => { chain = chain.then(() => command(line)).catch(error => console.error(errorText(error))).then(() => { if (!exiting) terminal.prompt(); }); });
    terminal.on('close', resolve);
  });
  await chain; unsubscribe(); process.off('SIGINT', signal); process.off('SIGTERM', signal); await bridge.close();
}
