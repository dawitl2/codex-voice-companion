import { applicationDefault, deleteApp, initializeApp, type App } from 'firebase-admin/app';
import { getMessaging, type FidMessage } from 'firebase-admin/messaging';
import { lstatSync, readFileSync, realpathSync } from 'node:fs';
import { isAbsolute, relative } from 'node:path';
import { spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { PushFailure, type PushProvider } from './NotificationDispatcher.js';
import { validFid, type PushPayload } from './NotificationPolicy.js';

export function firebaseConfiguration(env: NodeJS.ProcessEnv): { projectId: string; credentialPath: string } {
  if (env.CODEX_VOICE_FIREBASE_SPARK_CONFIRMED !== '1') throw new Error('Confirm Spark with billing unlinked: CODEX_VOICE_FIREBASE_SPARK_CONFIRMED=1');
  const projectId = env.CODEX_VOICE_FIREBASE_PROJECT_ID, credentialPath = env.GOOGLE_APPLICATION_CREDENTIALS;
  if (!projectId || !/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(projectId)) throw new Error('Set CODEX_VOICE_FIREBASE_PROJECT_ID to the dedicated Firebase project ID');
  if (!credentialPath || !isAbsolute(credentialPath)) throw new Error('Set GOOGLE_APPLICATION_CREDENTIALS to an absolute service-account JSON path outside Git');
  return { projectId, credentialPath };
}
export function firebaseMessage(fid: string, payload: PushPayload, ttlMs: number): FidMessage {
  if (!validFid(fid) || !Number.isFinite(ttlMs) || ttlMs <= 0 || ttlMs > 1800000) throw new PushFailure('invalid_payload');
  return { fid, data: { ...payload }, android: { priority: 'high', ttl: Math.floor(ttlMs), restrictedPackageName: 'com.codexvoice.app' } };
}
export function firebaseFailure(error: unknown): PushFailure {
  const code = error && typeof error === 'object' && 'code' in error ? error.code : '';
  if (code === 'messaging/registration-token-not-registered' || code === 'messaging/installation-not-registered') return new PushFailure('unregistered');
  if (['messaging/server-unavailable','messaging/internal-error','messaging/unknown-error','messaging/message-rate-exceeded','app/network-error','app/network-timeout'].includes(String(code))) return new PushFailure('temporary');
  return new PushFailure('configuration');
}
export class FirebasePushProvider implements PushProvider {
  private readonly app: App;
  constructor(workspace: string) {
    const config = firebaseConfiguration(process.env);
    try {
      if (process.platform !== 'win32' || lstatSync(config.credentialPath).isSymbolicLink()) throw new Error();
      const path = realpathSync(config.credentialPath), location = relative(realpathSync(workspace), path);
      if (!location.startsWith('..') && !isAbsolute(location)) throw new Error();
      // Reject credentials readable by other principals, including inherited grants.
      const check = spawnSync('powershell.exe', ['-NoProfile','-NonInteractive','-Command', "$ErrorActionPreference='Stop'; $path=[Console]::In.ReadToEnd(); $acl=Get-Acl -LiteralPath $path; $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value; if (-not $acl.AreAccessRulesProtected) { exit 2 }; foreach($rule in $acl.Access) { if ($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -ne $sid) { exit 3 } }; if ($acl.Owner -ne [Security.Principal.WindowsIdentity]::GetCurrent().Name -and $acl.Owner -ne $sid) { exit 4 }"], { input: path, windowsHide: true, timeout: 10000, stdio: ['pipe','ignore','ignore'] });
      if (check.status !== 0 || check.error) throw new Error();
      const json = JSON.parse(readFileSync(path, 'utf8')) as Record<string, unknown>;
      if (json.type !== 'service_account' || json.project_id !== config.projectId || typeof json.private_key !== 'string' || typeof json.client_email !== 'string') throw new Error();
    } catch { throw new Error('Firebase credential validation failed. Use matching service-account JSON outside the repository and run scripts/protect-firebase-credentials.ps1.'); }
    this.app = initializeApp({ projectId: config.projectId, credential: applicationDefault() }, 'codex-voice-' + randomUUID());
  }
  async send(fid: string, payload: PushPayload, ttlMs: number): Promise<void> {
    try { await getMessaging(this.app).send(firebaseMessage(fid, payload, ttlMs)); }
    catch (error) { if (error instanceof PushFailure) throw error; throw firebaseFailure(error); }
  }
  async close(): Promise<void> { await deleteApp(this.app); }
}
