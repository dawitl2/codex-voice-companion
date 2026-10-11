export function sanitize(value: unknown, max = 4000): string {
  return String(value).replace(/\u001b\[[0-?]*[ -/]*[@-~]/g, '')
    .replace(/\u001b\][^\u0007]*(?:\u0007|\u001b\\)/g, '')
    .replace(/-----BEGIN [\s\S]*?-----END [^-]+-----/g, '[REDACTED KEY]')
    .replace(/\b(?:sk-[\w-]{8,}|eyJ[\w-]{12,}\.[\w-]+\.[\w-]+)\b/g, '[REDACTED]')
    .replace(/((?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|authorization)\s*[=:]\s*)(?:"[^"]*"|'[^']*'|[^\s,;]+)/gi, '$1[REDACTED]')
    .replace(/[\u0000-\u0008\u000b-\u001f\u007f]/g, '').slice(0, max);
}
export function errorText(error: unknown): string { return sanitize(error instanceof Error ? error.message : String(error)); }
export function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid protocol object');
  return value as Record<string, unknown>;
}
export function identifier(value: unknown): string {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(value) || ['__proto__','constructor','prototype'].includes(value)) throw new Error('Invalid thread, turn, or item identifier');
  return value;
}
export function requiredString(value: unknown): string {
  if (typeof value !== 'string') throw new Error('Invalid protocol string');
  return value;
}
export function terminal(status: string): boolean { return ['completed','failed','interrupted'].includes(status); }
