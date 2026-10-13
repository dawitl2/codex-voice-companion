import { resolve } from 'node:path';
import { Bridge } from '../core/Bridge.js';
import { CodexProcessManager } from '../core/CodexProcessManager.js';
import { DeviceRegistry, WindowsRegistryStorage } from '../remote/DeviceRegistry.js';
import { RemoteServer } from '../remote/RemoteServer.js';
import { NotificationDispatcher } from '../notifications/NotificationDispatcher.js';
import { NotificationDeliveryStore } from '../notifications/NotificationDeliveryStore.js';
import { FirebasePushProvider } from '../notifications/FirebasePushProvider.js';
import { CallSessionManager } from '../calls/CallSessionManager.js';
import { CallEventCoordinator } from '../calls/CallEventCoordinator.js';
import { MediaTransportServer } from '../calls/MediaTransportServer.js';
import { SpeechSessionCoordinator } from '../speech/SpeechSessionCoordinator.js';
import { WhisperCppEngine } from '../speech/SpeechRecognitionEngine.js';
import { VoiceConversationController } from '../conversation/VoiceConversationController.js';
import { SubmissionLedger } from '../conversation/SubmissionLedger.js';
import { TaskControl } from '../control/TaskControl.js';
import { TextConversation } from '../conversation/TextConversation.js';

export interface ServiceOptions { assets: string; data: string; project: string; codex: string; endpoint: string; writable: boolean; notifications: boolean }
/** Real bridge modules, one ownership tree. No terminal or renderer access to subprocess RPC. */
export class CompanionService {
  readonly transport: CodexProcessManager;
  readonly bridge: Bridge;
  readonly registry: DeviceRegistry;
  readonly notifications: NotificationDispatcher;
  readonly calls: CallSessionManager;
  readonly media: MediaTransportServer;
  readonly speech: SpeechSessionCoordinator;
  readonly voice: VoiceConversationController;
  readonly controls: TaskControl;
  readonly chat: TextConversation;
  readonly remote: RemoteServer;
  private readonly coordinator: CallEventCoordinator;
  private stopped: Promise<void> | undefined;
  constructor(readonly options: ServiceOptions) {
    new WindowsRegistryStorage(options.data); // Current-user ACL inherited by JSON history.
    this.transport = new CodexProcessManager(options.codex, options.project);
    this.bridge = new Bridge('real', this.transport, resolve(options.data, 'real'), options.writable ? 'workspace-write' : 'read-only', 30000, 60000, options.writable ? options.project : undefined);
    const directory = resolve(options.data, 'remote/real');
    this.registry = new DeviceRegistry(new WindowsRegistryStorage(directory));
    this.notifications = new NotificationDispatcher(this.bridge, this.registry,
      new NotificationDeliveryStore(new WindowsRegistryStorage(directory, 'notifications.dpapi')),
      options.notifications ? new FirebasePushProvider(options.assets) : undefined);
    this.calls = new CallSessionManager(new WindowsRegistryStorage(directory, 'calls.dpapi'), this.registry, Date.now,
      c => this.bridge.store.state.approvals.some(a => a.id === c.approvalId && a.status === 'pending' && Date.parse(a.expiresAt) > Date.now()));
    this.coordinator = new CallEventCoordinator(this.bridge, this.registry, this.calls);
    this.media = new MediaTransportServer(this.calls, options.endpoint, (c, pcm, level) => this.speech.frame(c, pcm, level));
    this.speech = new SpeechSessionCoordinator(this.calls, this.bridge, new WhisperCppEngine(options.assets));
    const ledger = new SubmissionLedger(new WindowsRegistryStorage(directory, 'submissions.dpapi'));
    this.controls = new TaskControl(this.bridge, ledger, new WindowsRegistryStorage(directory, 'controls.dpapi'), id => this.registry.hasDevice(id));
    this.voice = new VoiceConversationController(this.bridge, this.calls, ledger, Date.now, this.controls);
    this.chat = new TextConversation(this.bridge, ledger);
    this.speech.onTranscription((c, u) => { void this.voice.transcription(c, u); });
    this.remote = new RemoteServer(this.bridge, this.registry, options.endpoint, 8787, 15000, 20000,
      { registry: this.registry, dispatcher: this.notifications, projectId: process.env.CODEX_VOICE_FIREBASE_PROJECT_ID },
      { manager: this.calls, media: this.media, speech: this.speech, conversation: this.voice }, this.controls, this.chat);
  }
  async start() {
    try { await this.bridge.connect(); await this.speech.initialization; await this.remote.start(); }
    catch (e) { await this.stop(); throw e; }
  }
  stop(): Promise<void> {
    this.stopped ??= (async () => {
      // Close external entry points and audio before interrupting owned Codex work.
      this.controls.close(); this.voice.close(); this.coordinator.close(); this.speech.close();
      await this.remote.close(); await this.media.close(); this.calls.close();
      await this.notifications.close(); await this.bridge.close();
    })();
    return this.stopped;
  }
  state(): Record<string, unknown> {
    const b = this.bridge, selected = b.threads.activeId;
    return { running: !this.stopped, codexConnected: b.available, authentication: b.authentication,
      policy: this.options.writable ? 'workspace-write (selected project only; network disabled)' : 'read-only', project: this.options.project,
      firebase: this.notifications.configured, firebaseDiagnostic: this.notifications.diagnostic, speech: this.speech.diagnostics(),
      paired: this.registry.list().map(d => ({ ...d, ...this.registry.pushState(d.id), ...this.remote.deviceState(d.id) })), connectedPhones: this.remote.connectedDevices,
      operations: selected ? this.registry.list().flatMap(d=>{try{return this.controls.status(d.id,selected).operations.map(o=>({...o,device:d.name}));}catch{return [];}}) : [],
      threads: b.threads.list(), selectedThreadId: selected ?? null, turns: b.store.state.turns.slice(-30),
      events: b.history().slice(-30), approvals: b.approvals.list().map(a => b.approvals.review(a.id)),
      reviewers: this.controls.reviewerList(), calls: this.calls.diagnostics(), media: this.media.diagnostics(), mediaSessions: this.media.connected,
      codexPid: this.transport.pid ?? null };
  }
}
