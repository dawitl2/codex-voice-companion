import { spawn, execFile } from 'node:child_process';
import type { ChildProcessWithoutNullStreams } from 'node:child_process';
import { promisify } from 'node:util';
import { existsSync, readdirSync, realpathSync } from 'node:fs';
import { homedir } from 'node:os';
import { delimiter, join, resolve } from 'node:path';
import type { RpcTransport } from '../types/index.js';
import { isolatedOverrides, safeChildEnvironment } from '../config/settings.js';
import { sanitize } from '../utils/safe.js';
const execute = promisify(execFile);

export async function resolveCodex(explicit = process.env.CODEX_VOICE_CODEX_PATH): Promise<string> {
  const candidates: string[] = [];
  if (explicit) candidates.push(resolve(explicit));
  else {
    const extensions = join(homedir(), '.vscode', 'extensions');
    if (existsSync(extensions)) for (const folder of readdirSync(extensions).filter(x => /^openai\.chatgpt-/.test(x)).sort().reverse()) candidates.push(join(extensions, folder, 'bin', 'windows-x86_64', 'codex.exe'));
    for (const directory of (process.env.PATH || '').split(delimiter)) candidates.push(join(directory, process.platform === 'win32' ? 'codex.exe' : 'codex'));
  }
  for (const path of candidates) {
    if (!existsSync(path)) continue;
    if (process.platform === 'win32' && !path.toLowerCase().endsWith('.exe')) throw new Error('Use a native codex.exe, not a .cmd wrapper or shell expression');
    const executable = realpathSync(path);
    const version = (await execute(executable, ['--version'], { windowsHide: true, timeout: 10000, env: safeChildEnvironment() })).stdout;
    if (!/^codex-cli 0\.160\.0\s*$/.test(version.trim())) {
      if (explicit) throw new Error('This bridge verifies Codex 0.160.0. Revalidate protocol schemas before selecting another version.');
      continue;
    }
    return executable;
  }
  throw new Error('Compatible native Codex 0.160.0 was not found. Set CODEX_VOICE_CODEX_PATH; mock mode does not require Codex.');
}

export class CodexProcessManager implements RpcTransport {
  private child: ChildProcessWithoutNullStreams | undefined;
  private dataListener: (data: string) => void = () => {};
  private closeListener: (reason: string) => void = () => {};
  private diagnosticListener: (text: string) => void = () => {};
  private stopPromise: Promise<void> | undefined;
  private closedPromise: Promise<void> | undefined;
  version = 'not checked';
  authentication = 'not checked';
  constructor(readonly executable: string, private readonly cwd: string) {}
  get pid(): number | undefined { return this.child?.pid; }
  onData(listener: (data: string) => void): void { this.dataListener = listener; }
  onClose(listener: (reason: string) => void): void { this.closeListener = listener; }
  onDiagnostic(listener: (text: string) => void): void { this.diagnosticListener = listener; }
  async start(): Promise<void> {
    if (this.child) throw new Error('Process already started; create a new connection to restart');
    this.stopPromise = undefined;
    const env = safeChildEnvironment();
    this.version = (await execute(this.executable, ['--version'], { timeout: 10000, windowsHide: true, env })).stdout.trim();
    if (this.version !== 'codex-cli 0.160.0') throw new Error('Unsupported Codex version');
    const login = await execute(this.executable, ['login','status'], { timeout: 10000, windowsHide: true, env });
    if (!/Logged in using ChatGPT/i.test(login.stdout + login.stderr)) throw new Error('ChatGPT authentication required. Use codex login in your terminal; API-key mode is rejected.');
    this.authentication = 'CLI ChatGPT login verified; app-server check pending';
    const args = ['app-server','--listen','stdio://', ...isolatedOverrides(this.cwd).flatMap(value => ['-c', value])];
    this.child = spawn(this.executable, args, { cwd: this.cwd, env, shell: false, windowsHide: true, stdio: ['pipe','pipe','pipe'] });
    this.child.stdout.setEncoding('utf8'); this.child.stderr.setEncoding('utf8');
    this.child.stdout.on('data', (data: string) => this.dataListener(data));
    this.child.stderr.on('data', (text: string) => this.diagnosticListener(sanitize(text, 1000)));
    this.child.stdin.on('error', () => this.closeListener('Codex stdin pipe error'));
    this.closedPromise = new Promise<void>(resolveClose => this.child!.once('close', resolveClose));
    this.child.on('close', (code, signal) => { this.child = undefined; this.closeListener(`Codex process closed (code ${code}, signal ${signal})`); });
    await new Promise<void>((resolveStart, reject) => {
      this.child!.once('spawn', resolveStart); this.child!.once('error', reject);
    });
    this.child.on('error', () => this.closeListener('Codex child process error'));
  }
  write(data: string): void {
    if (!this.child || this.child.exitCode !== null || this.child.stdin.destroyed) throw new Error('Codex process is unavailable');
    this.child.stdin.write(data, error => { if (error) this.closeListener('Codex stdin write failed'); });
  }
  async stop(): Promise<void> {
    if (this.stopPromise) return this.stopPromise;
    this.stopPromise = this.stopOwnedProcess(); return this.stopPromise;
  }
  private async stopOwnedProcess(): Promise<void> {
    const child = this.child;
    if (!child) return;
    const closed = this.closedPromise!;
    if (child.exitCode !== null || child.signalCode !== null) { await closed; return; }
    child.stdin.end();
    let timer: NodeJS.Timeout;
    const graceful = await Promise.race([closed.then(() => true), new Promise<false>(resolveWait => { timer = setTimeout(() => resolveWait(false), 2500); })]);
    clearTimeout(timer!);
    if (!graceful && child.pid) {
      // Only our child tree, never all Codex processes. No shell interpolation.
      if (process.platform === 'win32') {
        await execute(join(process.env.SystemRoot || 'C:\\Windows', 'System32','taskkill.exe'), ['/PID', String(child.pid), '/T', '/F'], { windowsHide: true, timeout: 5000 }).catch(() => { child.kill(); });
      } else child.kill('SIGKILL');
      await Promise.race([closed, new Promise<void>(resolveWait => { const timeout = setTimeout(resolveWait, 1000); timeout.unref(); })]);
      if (child.exitCode === null && child.signalCode === null) throw new Error('Owned Codex process did not shut down');
    }
  }
}
