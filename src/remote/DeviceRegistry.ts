import { createHash, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { ProtocolError } from './Protocol.js';
import { sanitize } from '../utils/safe.js';
import type { RemoteAccess } from './RemoteServer.js';
import { atomicReplace } from '../storage/atomicReplace.js';

interface Device { id: string; name: string; identity: string; digest: string; createdAt: string }
export interface RegistryStorage { load(): unknown; save(value: unknown): void }
const hash = (secret: string) => createHash('sha256').update(secret).digest();
function equal(secret: string, digest: string): boolean { const expected = Buffer.from(digest, 'hex'); return expected.length === 32 && timingSafeEqual(hash(secret), expected); }

function powershell(script: string, input: string): string {
  if (process.platform !== 'win32') throw new Error('Windows per-user protection required');
  const executable = join(process.env.SystemRoot ?? 'C:\\Windows', 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe');
  const result = spawnSync(executable, ['-NoProfile', '-NonInteractive', '-Command', "$ErrorActionPreference = 'Stop'; " + script], { input, encoding: 'utf8', windowsHide: true, timeout: 10000, maxBuffer: 262144 });
  if (result.status !== 0 || result.error) throw new Error('Protected device storage unavailable');
  return result.stdout.trim();
}
export class WindowsRegistryStorage implements RegistryStorage {
  private readonly file: string;
  constructor(directory: string) {
    mkdirSync(directory, { recursive: true }); this.file = join(directory, 'devices.dpapi');
    powershell(`$path = [Console]::In.ReadToEnd();
      if ([IO.File]::GetAttributes($path) -band [IO.FileAttributes]::ReparsePoint) { throw 'Reparse point refused' };
      $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User;
      $acl = New-Object Security.AccessControl.DirectorySecurity;
      $acl.SetOwner($sid); $acl.SetAccessRuleProtection($true, $false);
      $rule = New-Object Security.AccessControl.FileSystemAccessRule($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow');
      $acl.AddAccessRule($rule); [IO.Directory]::SetAccessControl($path, $acl);`, directory);
  }
  load(): unknown {
    if (!existsSync(this.file)) return [];
    const encoded = readFileSync(this.file, 'utf8');
    const plaintext = powershell(`Add-Type -AssemblyName System.Security;
      $bytes = [Convert]::FromBase64String([Console]::In.ReadToEnd());
      [Convert]::ToBase64String([Security.Cryptography.ProtectedData]::Unprotect($bytes, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser));`, encoded);
    return JSON.parse(Buffer.from(plaintext, 'base64').toString('utf8'));
  }
  save(value: unknown): void {
    const ciphertext = powershell(`Add-Type -AssemblyName System.Security;
      $bytes = [Convert]::FromBase64String([Console]::In.ReadToEnd());
      [Convert]::ToBase64String([Security.Cryptography.ProtectedData]::Protect($bytes, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser));`, Buffer.from(JSON.stringify(value)).toString('base64'));
    writeFileSync(this.file + '.tmp', ciphertext, { mode: 0o600, flush: true }); atomicReplace(this.file + '.tmp', this.file);
  }
}
export class DeviceRegistry implements RemoteAccess {
  private devices: Device[];
  private invitation: { digest: string; expires: number } | null = null;
  private attempts: number[] = [];
  constructor(private readonly storage: RegistryStorage, private readonly now: () => number = Date.now) {
    const data = storage.load();
    if (!Array.isArray(data) || data.length > 1 || data.some(d => !d || typeof d !== 'object' ||
      !/^[a-f0-9-]{36}$/.test(d.id) || typeof d.name !== 'string' || d.name.length > 80 ||
      typeof d.identity !== 'string' || !d.identity || d.identity.length > 256 || !/^[a-f0-9]{64}$/.test(d.digest) || !Number.isFinite(Date.parse(d.createdAt)))) throw new Error('Invalid protected device registry');
    this.devices = data as Device[];
  }
  beginPairing(): { code: string; expiresAt: string } {
    if (this.devices.length) throw new Error('One device is supported; revoke it before pairing another');
    const code = randomBytes(8).toString('hex').toUpperCase();
    this.invitation = { digest: hash(code).toString('hex'), expires: this.now() + 120000 };
    return { code, expiresAt: new Date(this.invitation.expires).toISOString() };
  }
  private limit(): void {
    this.attempts = this.attempts.filter(t => t > this.now() - 60000);
    if (this.attempts.length >= 5) throw new ProtocolError('rate_limited');
    this.attempts.push(this.now());
  }
  pair(code: string, name: string, identity: string): { deviceId: string; token: string } {
    this.limit();
    if (!this.invitation || this.now() >= this.invitation.expires) { this.invitation = null; throw new ProtocolError('pairing_expired'); }
    if (!equal(code, this.invitation.digest)) throw new ProtocolError('incorrect_code');
    if (this.devices.length) throw new ProtocolError('device_limit');
    const deviceId = randomUUID(), token = randomBytes(32).toString('base64url');
    const candidate: Device = { id: deviceId, name: sanitize(name, 80), identity, digest: hash(token).toString('hex'), createdAt: new Date(this.now()).toISOString() };
    this.storage.save([candidate]); this.devices = [candidate]; this.invitation = null;
    return { deviceId, token };
  }
  authenticate(deviceId: string, token: string, identity: string): boolean {
    this.limit();
    const device = this.devices.find(d => d.id === deviceId && d.identity === identity);
    const accepted = !!device && equal(token, device.digest);
    if (accepted) this.attempts.pop();
    return accepted;
  }
  list(): { id: string; name: string; createdAt: string }[] { return this.devices.map(({ id, name, createdAt }) => ({ id, name, createdAt })); }
  revoke(id: string): void {
    if (!this.devices.some(d => d.id === id)) throw new Error('Unknown device');
    const next = this.devices.filter(d => d.id !== id); this.storage.save(next); this.devices = next;
  }
}
