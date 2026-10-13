import { createInterface } from 'node:readline';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFileSync } from 'node:fs';
import { CompanionService } from './CompanionService.js';
import { object, errorText, identifier } from '../utils/safe.js';
import { projectDirectory } from '../config/settings.js';

const execute = promisify(execFile);
const config = object(JSON.parse(readFileSync(process.argv[2]!, 'utf8')));
let service: CompanionService | undefined;
let stopping = false;
let tailscale = false;
let checkingTail = false;
async function checkTail() { if(checkingTail||stopping)return; checkingTail=true;try{const t=object(JSON.parse((await execute('C:\\Program Files\\Tailscale\\tailscale.exe',['status','--json'],{windowsHide:true,timeout:5000})).stdout));tailscale=t.BackendState==='Running';}catch{tailscale=false;}finally{checkingTail=false;} }
const tailTimer=setInterval(()=>{void checkTail();},10000);
let status: Record<string, unknown> = { running: false, tailscale: false, error: '' };
const emit = (value: unknown) => process.stdout.write(JSON.stringify(value) + '\n');
const timer = setInterval(() => { if (!stopping) emit({ type: 'state', state: service ? { ...service.state(), tailscale } : status }); }, 1000);
const lines = createInterface({ input: process.stdin });
let chain = Promise.resolve();
async function stop() {
  if (stopping) return; stopping = true; clearInterval(timer);clearInterval(tailTimer);
  await service?.stop(); status = { running: false, tailscale: true, error: '', codexPid: null, mediaSessions: 0 };
  service = undefined; emit({ type: 'state', state: status });
}
async function action(type: string, p: Record<string, unknown>): Promise<unknown> {
  if (type === 'start') {
    if (service || stopping) throw new Error('Companion already started or stopping');
    const project = projectDirectory(String(config.project));
    const tail = object(JSON.parse((await execute('C:\\Program Files\\Tailscale\\tailscale.exe', ['status','--json'], { windowsHide: true, timeout: 10000 })).stdout));
    const self = object(tail.Self); if (tail.BackendState !== 'Running' || typeof self.DNSName !== 'string') throw new Error('Sign in to Tailscale on PC and phone');
    tailscale=true;
    const dns = self.DNSName.replace(/\.$/, '');
    const serve = object(JSON.parse((await execute('C:\\Program Files\\Tailscale\\tailscale.exe', ['serve','status','--json'], { windowsHide: true, timeout: 10000 })).stdout));
    const web = object(serve.Web), tcp = object(serve.TCP), handlers = object(object(web[dns + ':443']).Handlers);
    if (Object.keys(web).length !== 1 || Object.keys(tcp).length !== 1 || object(tcp['443']).HTTPS !== true ||
      (serve.AllowFunnel && Object.keys(object(serve.AllowFunnel)).length) || Object.keys(handlers).length !== 1 || object(handlers['/']).Proxy !== 'http://127.0.0.1:8787') throw new Error('Private Tailscale Serve must point only to loopback 8787; no Funnel');
    if (config.notifications === true) {
      if (config.sparkConfirmed !== true) throw new Error('Confirm Firebase Spark with billing unlinked on the desktop');
      const credentialPath = String(config.credentialPath), admin = object(JSON.parse(readFileSync(credentialPath, 'utf8')));
      process.env.GOOGLE_APPLICATION_CREDENTIALS = credentialPath;
      process.env.CODEX_VOICE_FIREBASE_PROJECT_ID = String(admin.project_id);
      process.env.CODEX_VOICE_FIREBASE_SPARK_CONFIRMED = '1';
    }
    service = new CompanionService({ assets: String(config.assets), data: String(config.data), project,
      codex: String(config.codex), endpoint: 'wss://' + dns + '/remote', writable: config.writable === true, notifications: config.notifications === true });
    await service.start();
    if (typeof config.selectedThreadId === 'string') { try { await service.bridge.select(config.selectedThreadId); } catch { /* User can choose/reconcile a different original thread. */ } }
    return { ...service.state(), tailscale: true };
  }
  if (type === 'stop') { await stop(); return { stopped: true }; }
  const s = service; if (!s || stopping) throw new Error('Start Companion first');
  const text = (key: string, max: number) => { const v = p[key]; if (typeof v !== 'string' || !v.trim() || v.length > max) throw new Error('Invalid ' + key); return v; };
  switch (type) {
    case 'state': return { ...s.state(), tailscale: true };
    case 'create': return { threadId: await s.bridge.create(s.options.project, text('label', 80)) };
    case 'select': await s.bridge.select(identifier(p.threadId)); return s.state();
    case 'send': { const id = identifier(p.threadId); if (s.bridge.threads.activeId !== id) throw new Error('Select this conversation before sending'); return { turnId: await s.bridge.send(id, text('text', 2000)) }; }
    case 'interrupt': { const t = s.bridge.turns.active(identifier(p.threadId)); if (!t) throw new Error('No active turn'); await s.bridge.turns.interrupt(t.threadId, t.id); return { requested: true }; }
    case 'pair': return { ...s.registry.beginPairing(), endpoint: s.remote.endpoint.href };
    case 'revoke': { const id = identifier(p.deviceId); s.registry.revoke(id); s.remote.revoke(id); return { revoked: true }; }
    case 'nominate': { const id = identifier(p.approvalId), a = s.bridge.approvals.review(id); s.bridge.approvals.nominate(id, s.bridge.threads.get(a.threadId).cwd); return { nominated: true }; }
    case 'decision': if (typeof p.accept !== 'boolean') throw new Error('Explicit decision required');
      if (p.accept && !s.bridge.approvals.review(identifier(p.approvalId)).eligible) throw new Error('Nominate the completely reviewed local command first; incomplete/file-change review remains desktop-only');
      s.bridge.approvals.resolve(identifier(p.approvalId), p.accept); return { sent: true };
    case 'trust': s.controls.trust(identifier(p.deviceId), text('fingerprint', 64)); return { trusted: true };
    default: throw new Error('Unsupported desktop action');
  }
}
lines.on('line', line => {
  if (line.length > 8192) { void stop().finally(() => process.exit(1)); return; }
  chain = chain.then(async () => {
    let id = 0;
    try { const m = object(JSON.parse(line)); if (!Number.isSafeInteger(m.id) || typeof m.type !== 'string') throw new Error('Invalid command'); id = Number(m.id);
      const result = await action(m.type, m.payload === undefined ? {} : object(m.payload)); emit({ id, result });
      if (m.type === 'stop') { lines.close(); setImmediate(() => process.exit(0)); }
    } catch (e) { emit({ id, error: errorText(e) }); }
  });
});
lines.on('close', () => { void chain.finally(() => stop()).finally(() => process.exit(0)); });
process.on('SIGTERM', () => { void stop().finally(() => process.exit(0)); });
process.on('SIGINT', () => { void stop().finally(() => process.exit(0)); });
process.on('uncaughtException', () => { void stop().finally(() => process.exit(1)); });
