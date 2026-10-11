import { existsSync, readFileSync, realpathSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { homedir } from 'node:os';
import { parse } from 'smol-toml';
import { object } from '../utils/safe.js';

export type Sandbox = 'read-only' | 'workspace-write';
export function projectDirectory(path: string): string {
  const cwd = realpathSync(resolve(path)); if (!statSync(cwd).isDirectory()) throw new Error('Project path is not a directory'); return cwd;
}
export function samePath(a: string, b: string): boolean { return process.platform === 'win32' ? a.toLowerCase() === b.toLowerCase() : a === b; }
export function disabledIntegrations(cwd: string): Record<'mcp_servers' | 'plugins', Record<string, { enabled: false }>> {
  const configs = new Set<string>([join(process.env.CODEX_HOME || join(homedir(), '.codex'), 'config.toml')]);
  let ancestor = cwd;
  while (true) { configs.add(join(ancestor, '.codex', 'config.toml')); const parent = dirname(ancestor); if (parent === ancestor) break; ancestor = parent; }
  const disabled: Record<'mcp_servers' | 'plugins', Record<string, { enabled: false }>> = { mcp_servers: {}, plugins: {} };
  for (const file of configs) {
    if (!existsSync(file)) continue;
    const config = object(parse(readFileSync(file, 'utf8').replace(/^\uFEFF/, '')));
    if (config.openai_base_url || config.chatgpt_base_url || config.model_provider && config.model_provider !== 'openai' || config.forced_login_method && config.forced_login_method !== 'chatgpt') throw new Error('Custom provider/auth routing is incompatible with the no-API-billing guard');
    if (config.model_providers && object(config.model_providers).openai) throw new Error('Customized OpenAI provider is incompatible with the no-API-billing guard');
    for (const section of ['mcp_servers','plugins'] as const) if (config[section]) for (const key of Object.keys(object(config[section]))) {
      if (['__proto__','constructor','prototype'].includes(key)) throw new Error('Invalid integration name'); disabled[section][key] = { enabled: false };
    }
  }
  return disabled;
}
export function isolatedOverrides(cwd: string): string[] {
  const disabled = disabledIntegrations(cwd);
  return [
    'model_provider="openai"', 'forced_login_method="chatgpt"', 'approval_policy="on-request"',
    'approvals_reviewer="user"', 'sandbox_mode="read-only"', 'service_tier="default"', 'notify=[]',
    'features.apps=false', 'features.hooks=false', 'features.remote_plugin=false',
    'apps._default.enabled=false', 'sandbox_workspace_write.network_access=false',
    ...(['mcp_servers','plugins'] as const).map(section => `${section}={${Object.keys(disabled[section]).map(key => `${JSON.stringify(key)}={enabled=false}`).join(',')}}`)
  ];
}
export function safeChildEnvironment(env: NodeJS.ProcessEnv = process.env): NodeJS.ProcessEnv {
  const forbidden = /^(OPENAI_API_KEY|CODEX_API_KEY|CODEX_ACCESS_TOKEN|OPENAI_BASE_URL|OPENAI_API_BASE|OPENAI_API_BASE_URL|CODEX_OPENAI_BASE_URL|CODEX_MODEL_PROVIDER|CODEX_WIF_.*|CODEX_AUTH_.*)$/i;
  if (Object.entries(env).some(([key, value]) => value && forbidden.test(key))) throw new Error('Paid/API/custom-auth environment detected. Remove it from this terminal; real mode will not fall back to it. Mock mode remains available.');
  const keep = /^(PATH|PATHEXT|SYSTEMROOT|WINDIR|COMSPEC|TEMP|TMP|USERPROFILE|HOME|HOMEDRIVE|HOMEPATH|APPDATA|LOCALAPPDATA|PROGRAMDATA|PROGRAMFILES|PROGRAMFILES\(X86\)|CODEX_HOME|HTTP_PROXY|HTTPS_PROXY|ALL_PROXY|NO_PROXY|SSL_CERT_FILE|SSL_CERT_DIR|NODE_EXTRA_CA_CERTS|LANG|LC_ALL)$/i;
  return Object.fromEntries(Object.entries(env).filter(([key]) => keep.test(key)));
}
