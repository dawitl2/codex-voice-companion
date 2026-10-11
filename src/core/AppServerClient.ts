import type { RpcCall, RpcTransport } from '../types/index.js';
import { object } from '../utils/safe.js';

export class AppServerClient {
  private sequence = 0;
  private buffer = '';
  private readonly pending = new Map<string | number, { resolve: (value: unknown) => void; reject: (error: Error) => void; timer: NodeJS.Timeout }>();
  private closed = true;
  ready = false;
  onNotification: (call: RpcCall) => void = () => {};
  onServerRequest: (call: RpcCall & { id: string | number }) => void = () => {};
  onDisconnected: (reason: string) => void = () => {};
  constructor(private readonly transport: RpcTransport, private readonly timeoutMs = 30000) {
    transport.onData(data => this.receive(data));
    transport.onClose(reason => this.disconnect(reason));
  }
  get pendingCount(): number { return this.pending.size; }
  async connect(): Promise<void> {
    this.closed = false;
    this.buffer = '';
    try {
      await this.transport.start();
      object(await this.request('initialize', { clientInfo: { name: 'codex_voice', title: 'Codex Voice', version: '0.1.0' }, capabilities: { experimentalApi: false } }));
      this.notify('initialized', {}); this.ready = true;
    } catch (error) { this.disconnect('Initialization failed'); await this.transport.stop(); throw error; }
  }
  request(method: string, params: unknown): Promise<unknown> {
    if (this.closed) return Promise.reject(new Error('App-server is disconnected'));
    if (!this.ready && method !== 'initialize') return Promise.reject(new Error('Initialization required'));
    const id = ++this.sequence;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error(`RPC timeout: ${method}; outcome may be unknown, do not replay automatically`)); }, this.timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      try { this.transport.write(JSON.stringify({ id, method, params }) + '\n'); }
      catch (error) { clearTimeout(timer); this.pending.delete(id); reject(error); }
    });
  }
  notify(method: string, params: unknown): void { if (this.closed) throw new Error('App-server is disconnected'); this.transport.write(JSON.stringify({ method, params }) + '\n'); }
  respond(id: string | number, result: unknown): void { if (!this.closed) this.transport.write(JSON.stringify({ id, result }) + '\n'); }
  rejectRequest(id: string | number, message = 'Unsupported server request'): void { if (!this.closed) this.transport.write(JSON.stringify({ id, error: { code: -32601, message } }) + '\n'); }
  private receive(data: string): void {
    if (this.closed) return;
    // The transport decodes UTF-8 bytes once; avoid re-encoding split JS strings.
    this.buffer += data;
    if (Buffer.byteLength(this.buffer) > 4 * 1024 * 1024) { this.fatal(); return; }
    let boundary: number;
    while ((boundary = this.buffer.indexOf('\n')) >= 0) {
      const line = this.buffer.slice(0, boundary).trim(); this.buffer = this.buffer.slice(boundary + 1);
      if (!line) continue;
      try { this.dispatch(object(JSON.parse(line))); }
      catch { this.fatal(); return; }
    }
  }
  private dispatch(message: Record<string, unknown>): void {
    const id = message.id;
    if (id !== undefined && typeof id !== 'number' && typeof id !== 'string') throw new Error('Invalid RPC id');
    if (typeof message.method === 'string') {
      if ('result' in message || 'error' in message) throw new Error('Invalid RPC request');
      const call: RpcCall = { method: message.method, params: message.params };
      if (id === undefined) this.onNotification(call);
      else this.onServerRequest({ ...call, id });
      return;
    }
    if (id === undefined || (('result' in message) === ('error' in message))) throw new Error('Invalid RPC response');
    const request = this.pending.get(id); if (!request) return; // Late timed-out responses are never replayed.
    const error = 'error' in message ? object(message.error) : undefined;
    clearTimeout(request.timer); this.pending.delete(id);
    if (error) { request.reject(new Error(typeof error.message === 'string' ? error.message : 'Codex RPC error')); }
    else request.resolve(message.result);
  }
  private fatal(): void { this.disconnect('Malformed app-server protocol; connection closed'); void this.transport.stop().catch(() => {}); }
  private disconnect(reason: string): void {
    if (this.closed) return;
    this.closed = true; this.ready = false;
    for (const request of this.pending.values()) { clearTimeout(request.timer); request.reject(new Error(reason)); }
    this.pending.clear(); this.buffer = ''; this.onDisconnected(reason);
  }
  async close(): Promise<void> { this.disconnect('Bridge shut down'); await this.transport.stop(); }
}
