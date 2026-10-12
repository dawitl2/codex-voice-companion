import { createServer, type IncomingMessage } from 'node:http';
import { hostname } from 'node:os';
import { basename } from 'node:path';
import { randomUUID } from 'node:crypto';
import { WebSocket, WebSocketServer } from 'ws';
import type { Bridge } from '../core/Bridge.js';
import type { BridgeEvent } from '../types/index.js';
import { sanitize } from '../utils/safe.js';
import { CLIENT_LIMIT, encode, parseClient, ProtocolError, type Snapshot } from './Protocol.js';
import type { DeviceRegistry } from './DeviceRegistry.js';
import type { NotificationDispatcher } from '../notifications/NotificationDispatcher.js';

export interface NotificationAccess { registry: DeviceRegistry; dispatcher: NotificationDispatcher; projectId: string | undefined }

export interface RemoteAccess {
  authenticate(deviceId: string, token: string, identity: string): boolean;
  pair(code: string, name: string, identity: string): { deviceId: string; token: string };
}
interface Session { socket: WebSocket; identity: string; name: string | null; deviceId: string | null; alive: boolean; count: number; window: number; timer: NodeJS.Timeout }
export function privateEndpoint(value: string): URL {
  const url = new URL(value);
  if (url.protocol !== 'wss:' || !/^[a-z0-9-]+\.[a-z0-9.-]+\.ts\.net$/.test(url.hostname) ||
      (url.port && url.port !== '443') || url.username || url.password || url.search || url.hash || url.pathname !== '/remote') throw new Error('Use the actual Tailscale Serve wss://PC.TAILNET.ts.net/remote address');
  return url;
}
export class RemoteServer {
  readonly epoch = randomUUID(); private sequence = 0; private readonly sessions = new Set<Session>();
  private readonly seen = new Set<string>(); private readonly order: string[] = []; private unsubscribe: (() => void) | undefined;
  private readonly server = createServer((_req, res) => { res.writeHead(404); res.end(); });
  private readonly sockets = new WebSocketServer({ noServer: true, maxPayload: CLIENT_LIMIT, perMessageDeflate: false });
  private heartbeat: NodeJS.Timeout | undefined; private pendingPublish: NodeJS.Timeout | undefined;
  private closing = false; private listening = false;
  private readonly pendingTypes = new Set<string>();
  readonly endpoint: URL;
  constructor(private readonly bridge: Bridge, private readonly access: RemoteAccess,
    endpoint: string, private readonly port = 8787, private readonly handshakeMs = 15000, private readonly heartbeatMs = 20000, private readonly notifications?: NotificationAccess) {
    this.endpoint = privateEndpoint(endpoint);
    if (!Number.isInteger(port) || port < 0 || port > 65535) throw new Error('Invalid remote port');
    this.server.headersTimeout = 10000; this.server.requestTimeout = 15000;
    this.server.on('upgrade', (req, socket, head) => {
      const identity = this.proxyIdentity(req);
      if (this.closing || !identity || this.sessions.size >= 8) { socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n'); return; }
      this.sockets.handleUpgrade(req, socket, head, ws => this.accept(ws, identity));
    });
  }
  private proxyIdentity(req: IncomingMessage): string | null {
    // Only local Serve is trusted to terminate TLS and replace identity headers.
    // Same-user malicious local processes are outside this trust boundary.
    const login = req.headers['tailscale-user-login'];
    if (req.socket.remoteAddress !== '127.0.0.1' || req.url !== '/remote' || req.headers.origin ||
      req.headers['tailscale-funnel-request'] || req.headers['x-forwarded-proto'] !== 'https' ||
      req.headers['x-forwarded-host'] !== this.endpoint.host || typeof login !== 'string' || !login || login.length > 256) return null;
    return login;
  }
  async start(): Promise<void> {
    if (this.closing || this.listening) throw new Error('Remote server already started or closed');
    await new Promise<void>((resolve, reject) => {
      this.server.once('error', reject); this.server.listen(this.port, '127.0.0.1', () => { this.server.off('error', reject); resolve(); });
    });
    this.listening = true;
    this.unsubscribe = this.bridge.events.subscribe(event => {
      if (this.seen.has(event.id)) return;
      this.seen.add(event.id); this.order.push(event.id);
      if (this.order.length > 500) this.seen.delete(this.order.shift()!);
      this.publish(event.type);
    });
    this.heartbeat = setInterval(() => {
      for (const s of this.sessions) { if (!s.alive) { s.socket.terminate(); continue; } s.alive = false; s.socket.ping(); }
    }, this.heartbeatMs); this.heartbeat.unref();
  }
  get address(): number { const address = this.server.address(); if (!address || typeof address === 'string') throw new Error('Not listening'); return address.port; }
  get connectedDevices(): number { return [...this.sessions].filter(s => s.deviceId).length; }
  snapshot(): Snapshot {
    const state = this.bridge.store.state;
    const threads = state.threads.slice(-50).map(t => ({ id: t.id, label: sanitize(t.label, 80) || 'Managed conversation', project: sanitize(basename(t.cwd), 80) || 'Project root', status: t.status }));
    const ids = new Set(threads.map(t => t.id));
    return { epoch: this.epoch, sequence: this.sequence, bridgeName: sanitize(hostname(), 80), available: this.bridge.available,
      mode: this.bridge.mode, selectedThreadId: ids.has(this.bridge.threads.activeId ?? '') ? this.bridge.threads.activeId! : null,
      threads, turns: state.turns.filter(t => ids.has(t.threadId)).slice(-100).map(t => ({ id: t.id, threadId: t.threadId, status: t.status, startedAt: t.startedAt, finishedAt: t.finishedAt ?? null })),
      history: state.events.slice(-100).map(e => ({ id: e.id, type: e.type, timestamp: e.timestamp,
        summary: this.safeSummary(e), threadId: e.threadId ?? null, turnId: e.turnId ?? null })),
      historyComplete: false, syncedAt: new Date().toISOString() };
  }
  private safeSummary(event: BridgeEvent): string {
    // Completion summaries can contain private assistant text; send status only.
    if (event.type === 'turn.completed') return 'Codex turn completed; command/test outcomes are not independently verified.';
    if (event.type === 'turn.failed') return 'Codex turn failed. Inspect the PC for details.';
    if (event.type === 'turn.interrupted') return 'Codex turn interrupted.';
    if (event.type.startsWith('approval.')) return event.type === 'approval.requested' ? 'Approval needs attention on the PC.' : 'Approval state changed on the PC.';
    if (event.type === 'codex.error') return 'Codex reported an error. Inspect the PC for details.';
    return sanitize(event.summary, 240);
  }
  publish(type = 'state.snapshot'): void {
    if (this.closing) return;
    this.sequence++;
    this.pendingTypes.add(['turn.progress','turn.completed','turn.failed','turn.interrupted'].includes(type) ? type : type === 'approval.requested' ? 'approval.pending' : 'state.snapshot');
    // Defer until the originating bridge notification has finished updating state.
    if (this.pendingPublish) clearTimeout(this.pendingPublish);
    this.pendingPublish = setTimeout(() => {
      this.pendingPublish = undefined;
      const snapshot = this.snapshot();
      for (const messageType of this.pendingTypes) for (const s of this.sessions) if (s.deviceId) this.send(s, messageType, snapshot);
      this.pendingTypes.clear();
    }, 0);
  }
  revoke(deviceId: string): void {
    for (const s of this.sessions) if (s.deviceId === deviceId) { this.send(s, 'auth.failed', { code: 'revoked' }); s.socket.close(4003, 'Pair again'); }
  }
  private accept(socket: WebSocket, identity: string): void {
    const timer = setTimeout(() => socket.close(4001, 'Authentication timeout'), this.handshakeMs);
    const session: Session = { socket, identity, name: null, deviceId: null, alive: true, count: 0, window: Date.now(), timer };
    this.sessions.add(session);
    socket.on('error', () => {});
    socket.on('pong', () => { session.alive = true; });
    socket.on('close', () => { clearTimeout(timer); this.sessions.delete(session); });
    socket.on('message', (data, binary) => {
      try {
        if (binary) throw new ProtocolError('binary_not_supported');
        if (Date.now() - session.window > 10000) { session.window = Date.now(); session.count = 0; }
        if (++session.count > 40) throw new ProtocolError('rate_limited');
        const m = parseClient(data.toString());
        if (m.type === 'client.hello') {
          if (session.name !== null || session.deviceId) throw new ProtocolError('invalid_handshake');
          session.name = sanitize(m.payload.name, 80); this.send(session, 'server.hello', { bridgeName: sanitize(hostname(), 80) }, m.id); return;
        }
        if (!session.name) throw new ProtocolError('hello_required');
        if (m.type === 'auth.request' || m.type === 'pair.request') {
          if (session.deviceId) throw new ProtocolError('already_authenticated');
          try {
            if (m.type === 'pair.request') {
              const credential = this.access.pair(m.payload.code, session.name, identity); session.deviceId = credential.deviceId;
              this.send(session, 'pair.success', { ...credential, bridgeName: sanitize(hostname(), 80) }, m.id);
            } else {
              if (!this.access.authenticate(m.payload.deviceId, m.payload.token, identity)) throw new ProtocolError('invalid_credentials');
              session.deviceId = m.payload.deviceId; this.send(session, 'auth.success', { deviceId: session.deviceId, bridgeName: sanitize(hostname(), 80) }, m.id);
            }
            clearTimeout(timer); this.send(session, 'state.snapshot', this.snapshot());
          } catch (error) { this.send(session, m.type === 'pair.request' ? 'pair.failed' : 'auth.failed', { code: error instanceof ProtocolError ? error.code : 'storage_unavailable' }, m.id); socket.close(4003, 'Authentication failed'); }
          return;
        }
        if (!session.deviceId) throw new ProtocolError('authentication_required');
        if (m.type.startsWith('notification.')) {
          try {
            const n = this.notifications; if (!n) throw new ProtocolError('notifications_unavailable');
            if (m.type === 'notification.register') {
              if (!n.projectId || n.projectId !== m.payload.projectId) throw new ProtocolError('firebase_project_mismatch');
              n.registry.registerPush(session.deviceId, m.payload.fid, m.payload.preferences);
            } else if (m.type === 'notification.preferences.update') n.registry.updatePushPreferences(session.deviceId, m.payload.preferences);
            if (m.type === 'notification.event.request') this.send(session, 'notification.event.response', n.dispatcher.event(session.deviceId, m.payload.eventId), m.id);
            else if (m.type === 'notification.receipt') {
              n.dispatcher.receipt(session.deviceId, m.payload.eventId, m.payload.stage);
              this.send(session, 'notification.receipt.response', m.payload, m.id);
            } else this.send(session, 'notification.status.response', { ...n.registry.pushState(session.deviceId), configured: n.dispatcher.configured, diagnostic: n.dispatcher.diagnostic }, m.id);
          } catch (error) { this.send(session, 'notification.error', { code: error instanceof ProtocolError ? error.code : error instanceof Error && error.message === 'unknown_event' ? 'unknown_event' : 'notification_storage_unavailable' }, m.id); }
        }
        else if (m.type === 'heartbeat') this.send(session, 'heartbeat', {}, m.id);
        else if (m.type === 'disconnect') socket.close(1000, 'Disconnected');
        else {
          const response = m.type === 'thread.list.request' ? 'thread.list.response' : m.type === 'task.history.request' ? 'task.history.response' : m.type === 'connection.status.request' ? 'connection.status' : 'state.snapshot';
          this.send(session, response, this.snapshot(), m.id);
        }
      } catch (error) { this.send(session, 'protocol.error', { code: error instanceof ProtocolError ? error.code : 'invalid_message' }); socket.close(4002, 'Protocol error'); }
    });
  }
  private send(s: Session, type: string, payload: unknown, correlationId?: string): void {
    if (s.socket.readyState !== WebSocket.OPEN) return;
    if (s.socket.bufferedAmount > 524288) { s.socket.terminate(); return; }
    try { s.socket.send(encode(type, payload, correlationId)); } catch { s.socket.close(1011, 'Snapshot unavailable'); }
  }
  async close(): Promise<void> {
    if (this.closing) return; this.closing = true; this.unsubscribe?.();
    if (this.heartbeat) clearInterval(this.heartbeat); if (this.pendingPublish) clearTimeout(this.pendingPublish);
    for (const s of this.sessions) { clearTimeout(s.timer); s.socket.terminate(); }
    this.sessions.clear();
    await new Promise<void>(resolve => this.sockets.close(() => resolve()));
    if (this.listening) await new Promise<void>(resolve => this.server.close(() => resolve()));
  }
}
