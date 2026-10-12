import test, { after, type TestContext } from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { Bridge } from '../src/core/Bridge.js';
import { MockTransport } from '../src/core/MockTransport.js';
import { AppServerClient } from '../src/core/AppServerClient.js';
import { JsonStore } from '../src/storage/JsonStore.js';
import { safeChildEnvironment, projectDirectory } from '../src/config/settings.js';
import { sanitize, identifier } from '../src/utils/safe.js';
import { EventBus } from '../src/events/EventBus.js';
import type { BridgeEvent } from '../src/types/index.js';

const root = resolve(fileURLToPath(new URL('../..', import.meta.url)));
const base = join(root, '.runtime', 'tests'); mkdirSync(base, { recursive: true });
const temporaryDirectories: string[] = [];
after(() => { for (const path of temporaryDirectories) { assert.ok(path.startsWith(base + '\\') || path.startsWith(base + '/')); rmSync(path, { recursive: true, force: true }); } });
function directory(_t: TestContext): string {
  const path = mkdtempSync(join(base, 'case-'));
  temporaryDirectories.push(path); return path;
}
async function fixture(t: TestContext, approvalTimeout = 1000) {
  const dir = directory(t); const transport = new MockTransport(undefined, 5); const bridge = new Bridge('mock', transport, join(dir, 'state'), 'read-only', 1000, approvalTimeout);
  t.after(() => bridge.close()); await bridge.connect(); return { dir, transport, bridge };
}
async function until(predicate: () => boolean, timeout = 2000): Promise<void> {
  const deadline = Date.now() + timeout; while (!predicate()) { if (Date.now() >= deadline) throw new Error('Test wait timed out'); await new Promise(resolveWait => setTimeout(resolveWait, 5)); }
}

test('offline handshake sends required metadata and initialized only after success', async t => {
  const { bridge, transport } = await fixture(t);
  assert.equal(bridge.client.ready, true); assert.equal(bridge.authentication, 'Mock only');
  assert.equal(transport.sent[0]?.method, 'initialize'); assert.equal(transport.sent[1]?.method, 'initialized');
  assert.deepEqual((transport.sent[0]?.params as { capabilities: unknown }).capabilities, { experimentalApi: false });
});
test('create, stream, complete and persist genuine mock adapter IDs', async t => {
  const { dir, bridge, transport } = await fixture(t); const threadId = await bridge.create(dir, 'Example'); const turnId = await bridge.send(threadId, 'hello');
  await until(() => bridge.turns.get(threadId, turnId)?.status === 'completed');
  assert.equal(bridge.turns.get(threadId, turnId)?.finalResponse, 'MOCK response: hello');
  assert.ok(transport.sent.some(m => m.method === 'turn/start' && (m.params as { threadId: string }).threadId === threadId));
  const stored = JSON.parse(readFileSync(join(dir, 'state/state.json'), 'utf8')) as { turns: { id: string }[] };
  assert.equal(stored.turns[0]?.id, turnId);
  const turnParams = transport.sent.find(m => m.method === 'turn/start')!.params as { serviceTierForTurn: string }; assert.equal(turnParams.serviceTierForTurn, 'default');
});
test('failed turn remains failed with actual error', async t => {
  const { dir, bridge } = await fixture(t); const id = await bridge.create(dir, 'Failure'); const turn = await bridge.send(id, '[mock:fail]');
  await until(() => bridge.turns.get(id, turn)?.status === 'failed'); assert.equal(bridge.history().filter(e => e.type === 'turn.failed').length, 1);
  assert.equal(bridge.turns.get(id, turn)?.error, 'MOCK failure');
});
test('interruption requires terminal notification and remains distinct', async t => {
  const { dir, bridge } = await fixture(t); const id = await bridge.create(dir, 'Stop'); const turn = await bridge.send(id, '[mock:wait]');
  await bridge.turns.interrupt(id, turn); assert.equal(bridge.turns.get(id, turn)?.status, 'running');
  await until(() => bridge.turns.get(id, turn)?.status === 'interrupted');
});
test('duplicate completion and late started notifications produce one terminal event', async t => {
  const { dir, bridge, transport } = await fixture(t); const id = await bridge.create(dir, 'Duplicate'); const turn = await bridge.send(id, '[mock:duplicate]');
  await until(() => bridge.turns.get(id, turn)?.status === 'completed');
  transport.emit({ method: 'turn/started', params: { threadId: id, turn: { id: turn, status: 'inProgress' } } });
  assert.equal(bridge.history().filter(e => e.type === 'turn.completed').length, 1); assert.equal(bridge.turns.get(id, turn)?.status, 'completed');
});
test('two threads complete independently without selected-thread misrouting', async t => {
  const { dir, bridge } = await fixture(t); const a = await bridge.create(dir, 'A'); const b = await bridge.create(dir, 'B');
  const [ta, tb] = await Promise.all([bridge.send(a, 'one'), bridge.send(b, 'two')]);
  await until(() => bridge.turns.get(a, ta)?.status === 'completed' && bridge.turns.get(b, tb)?.status === 'completed');
  assert.equal(bridge.turns.get(a, ta)?.finalResponse, 'MOCK response: one'); assert.equal(bridge.turns.get(b, tb)?.finalResponse, 'MOCK response: two');
});
test('same thread refuses simultaneous turns and invalid routing', async t => {
  const { dir, bridge } = await fixture(t); const id = await bridge.create(dir, 'Busy'); await bridge.send(id, '[mock:wait]');
  await assert.rejects(bridge.send(id, 'another'), /active or unreconciled/); await assert.rejects(bridge.send(randomUUID(), 'oops'), /not companion-managed/);
});
test('explicit approval acceptance is scoped to one server request', async t => {
  const { dir, bridge, transport } = await fixture(t); const id = await bridge.create(dir, 'Approval'); const turn = await bridge.send(id, '[mock:approval]');
  await until(() => bridge.approvals.list().length === 1); const record = bridge.approvals.list()[0]!;
  assert.equal(bridge.turns.get(id, turn)?.status, 'awaitingApproval'); bridge.approvals.resolve(record.id, true);
  await until(() => bridge.turns.get(id, turn)?.status === 'completed');
  assert.deepEqual(transport.sent.find(m => m.id === record.requestId && !m.method)?.result, { decision: 'accept' });
  assert.throws(() => bridge.approvals.resolve(record.id, true), /already resolved/);
});
test('approval rejection interrupts mock turn safely', async t => {
  const { dir, bridge } = await fixture(t); const id = await bridge.create(dir, 'Deny'); const turn = await bridge.send(id, '[mock:approval]');
  await until(() => bridge.approvals.list().length === 1); bridge.approvals.resolve(bridge.approvals.list()[0]!.id, false);
  await until(() => bridge.turns.get(id, turn)?.status === 'interrupted'); assert.equal(bridge.store.state.approvals[0]?.status, 'declined');
});
test('unattended approval expires to cancel, never automatic acceptance', async t => {
  const { dir, bridge, transport } = await fixture(t, 30); const id = await bridge.create(dir, 'Expire'); await bridge.send(id, '[mock:approval]');
  await until(() => bridge.store.state.approvals[0]?.status === 'expired');
  const record = bridge.store.state.approvals[0]!; assert.deepEqual(transport.sent.find(m => m.id === record.requestId && !m.method)?.result, { decision: 'cancel' });
});
test('unsupported permission expansion returns empty grant and interrupts', async t => {
  const { dir, bridge, transport } = await fixture(t); const id = await bridge.create(dir, 'Permissions'); const turn = await bridge.send(id, '[mock:wait]');
  transport.emit({ id: 'permissions-1', method: 'item/permissions/requestApproval', params: { threadId: id, turnId: turn, permissions: { network: { enabled: true } } } });
  assert.deepEqual(transport.sent.find(m => m.id === 'permissions-1')?.result, { permissions: {}, scope: 'turn' });
  await until(() => bridge.turns.get(id, turn)?.status === 'interrupted');
});
test('unknown-thread approval cannot be accepted', async t => {
  const { transport, bridge } = await fixture(t);
  transport.emit({ id: 'wrong-approval', method: 'item/fileChange/requestApproval', params: { threadId: randomUUID(), turnId: randomUUID(), itemId: randomUUID() } });
  assert.equal(bridge.approvals.list().length, 0); assert.deepEqual(transport.sent.find(m => m.id === 'wrong-approval')?.result, { decision: 'cancel' });
});
test('server-resolved approvals remove stale reviewer prompts', async t => {
  const { dir, bridge, transport } = await fixture(t); const id = await bridge.create(dir, 'Resolved'); await bridge.send(id, '[mock:approval]');
  await until(() => bridge.approvals.list().length === 1); const record = bridge.approvals.list()[0]!;
  transport.emit({ method: 'serverRequest/resolved', params: { threadId: id, requestId: record.requestId } }); assert.equal(bridge.approvals.list().length, 0);
});
test('crash makes outcome unknown, rejects pending promises and never replays inference', async t => {
  const { dir, bridge, transport } = await fixture(t); const id = await bridge.create(dir, 'Crash'); const turn = await bridge.send(id, '[mock:crash]');
  await until(() => !bridge.client.ready); assert.equal(bridge.turns.get(id, turn)?.status, 'unknown'); assert.equal(bridge.client.pendingCount, 0);
  assert.equal(transport.sent.filter(m => m.method === 'turn/start').length, 1); await assert.rejects(bridge.send(id, 'retry'), /disconnected/);
});
test('restart resumes stored mock conversation and reconciles lost turn without inference', async t => {
  const dir = directory(t); const transport = new MockTransport(undefined, 5); const bridge = new Bridge('mock', transport, join(dir, 'state'));
  await bridge.connect(); const id = await bridge.create(dir, 'Resume'); const turn = await bridge.send(id, '[mock:wait]'); transport.crash(); await bridge.close();
  const next = new MockTransport(); const resumed = new Bridge('mock', next, join(dir, 'state')); next.hydrate(resumed.store.state); t.after(() => resumed.close());
  await resumed.connect(); await resumed.select(id);
  assert.equal(resumed.turns.get(id, turn)?.status, 'interrupted'); assert.equal(next.sent.filter(m => m.method === 'turn/start').length, 0);
  const followup = await resumed.send(id, 'follow-up'); await until(() => resumed.turns.get(id, followup)?.status === 'completed');
});
test('failed resumption preserves unavailable state and blocks sending', async t => {
  const { dir, bridge, transport } = await fixture(t); const id = await bridge.create(dir, 'Missing');
  transport.crash(); await bridge.connect(); bridge.threads.loaded.clear();
  const original = transport.write.bind(transport); transport.write = data => { const m = JSON.parse(data) as { id: number; method: string }; if (m.method === 'thread/resume') transport.emit({ id: m.id, error: { code: -32000, message: 'Thread unavailable' } }); else original(data); };
  await assert.rejects(bridge.select(id), /unavailable/); await assert.rejects(bridge.send(id, 'no'), /Resume/);
});
test('structured command outcomes are kept without inventing test success', async t => {
  const { dir, bridge, transport } = await fixture(t); const id = await bridge.create(dir, 'Commands'); const turn = await bridge.send(id, '[mock:wait]');
  transport.emit({ method: 'item/completed', params: { threadId: id, turnId: turn, item: { id: randomUUID(), type: 'commandExecution', status: 'completed', exitCode: 1 } } });
  transport.emit({ method: 'turn/completed', params: { threadId: id, turn: { id: turn, status: 'completed', items: [], error: null } } });
  assert.equal(bridge.turns.get(id, turn)?.commandResults[0]?.exitCode, 1); assert.match(bridge.history().at(-1)!.summary, /not independently verified test/);
});
test('malformed notification closes safely rather than accepting a fabricated outcome', async t => {
  const { dir, bridge, transport } = await fixture(t); const id = await bridge.create(dir, 'Malformed'); const turn = await bridge.send(id, '[mock:wait]');
  transport.emit({ method: 'turn/completed', params: { threadId: id, turn: { id: turn, status: 'banana' } } });
  await until(() => !bridge.client.ready); assert.equal(bridge.turns.get(id, turn)?.status, 'unknown');
});
test('partial and concatenated JSON protocol frames preserve ordering', async t => {
  const transport = new MockTransport(); const client = new AppServerClient(transport); t.after(() => client.close()); await client.connect();
  const methods: string[] = []; client.onNotification = c => methods.push(c.method);
  transport.emitRaw('{"method":"one","params":'); transport.emitRaw('{}}\n{"method":"two","params":{}}\n');
  assert.deepEqual(methods, ['one','two']);
});
test('request timeout removes pending entry and ignores late responses', async t => {
  const transport = new MockTransport(); const client = new AppServerClient(transport, 20); t.after(() => client.close()); await client.connect();
  transport.write = () => {}; await assert.rejects(client.request('slow', {}), /timeout/); assert.equal(client.pendingCount, 0);
  transport.emit({ id: 2, result: {} }); assert.equal(client.ready, true);
});
test('malformed RPC errors reject all pending requests without promise leaks', async t => {
  const transport = new MockTransport(); const client = new AppServerClient(transport, 200); t.after(() => client.close()); await client.connect();
  transport.write = () => {}; const request = client.request('broken', {}); transport.emit({ id: 2, error: null });
  await assert.rejects(request, /Malformed/); assert.equal(client.pendingCount, 0); assert.equal(client.ready, false);
});
test('ordinary stdout logging is rejected as protocol corruption', async t => {
  const transport = new MockTransport(); const client = new AppServerClient(transport); t.after(() => client.close()); await client.connect();
  transport.emitRaw('Not a JSON message\n'); assert.equal(client.ready, false);
});
test('transport death rejects a pending request immediately', async t => {
  const transport = new MockTransport(); const client = new AppServerClient(transport); t.after(() => client.close()); await client.connect();
  transport.write = () => {}; const request = client.request('pending', {}); transport.crash(); await assert.rejects(request, /crashed/); assert.equal(client.pendingCount, 0);
});
test('state lock prevents concurrent bridge writers', t => {
  const dir = directory(t); const store = new JsonStore(dir, 'mock'); t.after(() => store.close()); assert.throws(() => new JsonStore(dir, 'mock'), /Another bridge/);
});
test('abandoned acquisition gate fails closed without stealing another startup', t => {
  const dir = directory(t); writeFileSync(join(dir, 'bridge.lock.acquiring'), JSON.stringify({ pid: process.pid }));
  assert.throws(() => new JsonStore(dir, 'mock'), /acquisition is busy or abandoned/);
  assert.equal(JSON.parse(readFileSync(join(dir, 'bridge.lock.acquiring'), 'utf8')).pid, process.pid);
});
test('atomic storage recovers valid backup after corrupt primary', t => {
  const dir = directory(t); const store = new JsonStore(dir, 'mock'); store.save(); store.close();
  writeFileSync(join(dir, 'state.json'), '{broken'); const recovered = new JsonStore(dir, 'mock'); t.after(() => recovered.close()); assert.equal(recovered.state.version, 1);
});
test('storage refuses incompatible mock/real modes', t => {
  const dir = directory(t); const store = new JsonStore(dir, 'mock'); store.close(); assert.throws(() => new JsonStore(dir, 'real'), /incompatible/);
});
test('billing guard refuses API keys and omits unrelated environment values', () => {
  assert.throws(() => safeChildEnvironment({ OPENAI_API_KEY: 'example-value' }), /Paid\/API/);
  const env = safeChildEnvironment({ PATH: 'path', USERPROFILE: 'profile', PRIVATE_VALUE: 'hidden' }); assert.equal(env.PRIVATE_VALUE, undefined); assert.equal(env.PATH, 'path');
});
test('real-mode account gate rejects API authentication before any thread or inference (mock transport)', async t => {
  const dir = directory(t); const transport = new MockTransport(); const original = transport.write.bind(transport);
  transport.write = data => { const m = JSON.parse(data) as { method: string; id: number }; if (m.method === 'account/read') transport.emit({ id: m.id, result: { account: { type: 'apiKey' } } }); else original(data); };
  const bridge = new Bridge('real', transport, join(dir, 'state')); t.after(() => bridge.close()); await assert.rejects(bridge.connect(), /ChatGPT/);
  assert.equal(transport.sent.some(m => m.method === 'turn/start' || m.method === 'thread/start'), false);
  await bridge.close();
  const missing = new MockTransport(); const originalMissing = missing.write.bind(missing);
  missing.write = data => { const m = JSON.parse(data) as { method: string; id: number }; if (m.method === 'account/read') missing.emit({ id: m.id, result: { account: null } }); else originalMissing(data); };
  const missingBridge = new Bridge('real', missing, join(dir, 'missing-state')); t.after(() => missingBridge.close()); await assert.rejects(missingBridge.connect(), /no authenticated account/);
});
test('secret-like output and terminal escapes are removed by best-effort sanitizer', () => {
  const value = sanitize('api_key=example-secret sk-abcdefghijk \u001b[31m'); assert.ok(!value.includes('example-secret')); assert.ok(!value.includes('sk-abcdefghijk')); assert.ok(!value.includes('\u001b')); assert.ok(!value.includes('[31m'));
});
test('identifiers and missing directories fail safely', () => {
  assert.throws(() => identifier('../wrong'), /Invalid/); assert.throws(() => identifier('__proto__'), /Invalid/); assert.throws(() => projectDirectory(join(base, randomUUID())));
});
test('event subscribers are isolated and unsubscribe works', () => {
  const bus = new EventBus(); let count = 0; bus.subscribe(() => { throw new Error('listener'); }); const off = bus.subscribe(() => count++);
  const event: BridgeEvent = { id: randomUUID(), timestamp: new Date().toISOString(), type: 'bridge.connected', summary: 'test' }; bus.emit(event); off(); bus.emit(event); assert.equal(count, 1);
});
test('unknown turn absent from resumed history stays blocked', async t => {
  const { dir, bridge, transport } = await fixture(t); const id = await bridge.create(dir, 'Unknown'); const turn = await bridge.send(id, '[mock:wait]');
  transport.crash(); await bridge.connect();
  const original = transport.write.bind(transport); transport.write = data => {
    const m = JSON.parse(data) as { id: number; method: string };
    if (m.method === 'thread/resume') transport.emit({ id: m.id, result: { thread: { id, cwd: dir, turns: [] }, cwd: dir, model: 'mock-model', modelProvider: 'openai', approvalPolicy: 'on-request', approvalsReviewer: 'user', sandbox: { type: 'readOnly' } } }); else original(data);
  };
  await bridge.select(id); assert.equal(bridge.turns.get(id, turn)?.status, 'unknown'); await assert.rejects(bridge.send(id, 'no replay'), /unreconciled/);
});
test('missing resumed history unloads thread before another instruction', async t => {
  const { dir, bridge, transport } = await fixture(t); const id = await bridge.create(dir, 'No history'); transport.crash(); await bridge.connect();
  const original = transport.write.bind(transport); transport.write = data => {
    const m = JSON.parse(data) as { id: number; method: string };
    if (m.method === 'thread/resume') transport.emit({ id: m.id, result: { thread: { id, cwd: dir }, cwd: dir, model: 'mock-model', modelProvider: 'openai', approvalPolicy: 'on-request', approvalsReviewer: 'user', sandbox: { type: 'readOnly' } } }); else original(data);
  };
  await assert.rejects(bridge.select(id), /omitted turn history/); assert.equal(bridge.threads.loaded.has(id), false);
});
test('late final response enriches existing completion record without a second event', async t => {
  const { dir, bridge, transport } = await fixture(t); const id = await bridge.create(dir, 'Late final'); const turn = await bridge.send(id, '[mock:wait]');
  transport.emit({ method: 'turn/completed', params: { threadId: id, turn: { id: turn, status: 'completed', items: [] } } });
  const completionId = bridge.history().find(e => e.type === 'turn.completed')!.id;
  transport.emit({ method: 'item/completed', params: { threadId: id, turnId: turn, item: { id: randomUUID(), type: 'agentMessage', phase: 'final_answer', text: 'Final verified response' } } });
  const events = bridge.history().filter(e => e.type === 'turn.completed'); assert.equal(events.length, 1); assert.equal(events[0]?.id, completionId); assert.match(events[0]!.summary, /Final verified response/);
});
test('assistant commentary is excluded from final response when phase is present', async t => {
  const { dir, bridge, transport } = await fixture(t); const id = await bridge.create(dir, 'Final phase'); const turn = await bridge.send(id, '[mock:wait]'); const itemId = randomUUID();
  transport.emit({ method: 'item/agentMessage/delta', params: { threadId: id, turnId: turn, itemId, delta: 'Thinking commentary' } });
  transport.emit({ method: 'item/completed', params: { threadId: id, turnId: turn, item: { id: itemId, type: 'agentMessage', phase: 'commentary', text: 'Thinking commentary' } } });
  transport.emit({ method: 'item/completed', params: { threadId: id, turnId: turn, item: { id: randomUUID(), type: 'agentMessage', phase: 'final_answer', text: 'Only final' } } });
  transport.emit({ method: 'turn/completed', params: { threadId: id, turn: { id: turn, status: 'completed', items: [] } } });
  assert.equal(bridge.turns.get(id, turn)?.finalResponse, 'Only final');
});
test('fragmented Unicode string frames survive without double decoding', async t => {
  const transport = new MockTransport(); const client = new AppServerClient(transport); t.after(() => client.close()); await client.connect(); let value = '';
  client.onNotification = c => { value = (c.params as { text: string }).text; };
  const message = '{"method":"unicode","params":{"text":"🎤"}}\n'; const split = message.indexOf('🎤') + 1;
  transport.emitRaw(message.slice(0, split)); transport.emitRaw(message.slice(split)); assert.equal(value, '🎤');
});
test('mutating RPC timeout unloads thread and requires resumption instead of retry', async t => {
  const dir = directory(t); const transport = new MockTransport(); const bridge = new Bridge('mock', transport, join(dir, 'state'), 'read-only', 25); t.after(() => bridge.close()); await bridge.connect(); const id = await bridge.create(dir, 'Timeout');
  const original = transport.write.bind(transport); transport.write = data => { const m = JSON.parse(data) as { method: string }; if (m.method !== 'turn/start') original(data); };
  await assert.rejects(bridge.send(id, 'unknown outcome'), /timeout/); assert.equal(bridge.threads.loaded.has(id), false); await assert.rejects(bridge.send(id, 'retry'), /Resume/);
});
test('workspace-write sandbox keeps network off and rejects extra writable roots', async t => {
  const dir = directory(t); const transport = new MockTransport(); const bridge = new Bridge('mock', transport, join(dir, 'state'), 'workspace-write'); t.after(() => bridge.close()); await bridge.connect(); const id = await bridge.create(dir, 'Write'); await bridge.send(id, '[mock:wait]');
  const params = transport.sent.find(m => m.method === 'turn/start')!.params as { sandboxPolicy: { writableRoots: string[]; networkAccess: boolean } };
  assert.deepEqual(params.sandboxPolicy.writableRoots, [dir]); assert.equal(params.sandboxPolicy.networkAccess, false);
  transport.crash(); await bridge.connect(); const original = transport.write.bind(transport); transport.write = data => {
    const m = JSON.parse(data) as { method: string; id: number }; if (m.method === 'thread/resume') transport.emit({ id: m.id, result: { thread: { id, cwd: dir, turns: [] }, cwd: dir, model: 'mock-model', modelProvider: 'openai', approvalPolicy: 'on-request', approvalsReviewer: 'user', sandbox: { type: 'workspaceWrite', networkAccess: false, writableRoots: [root] } } }); else original(data);
  };
  await assert.rejects(bridge.select(id), /Additional writable roots/);
});
test('compiled CLI starts offline, shows status and exits cleanly', async t => {
  // The CLI resolves state relative to its compiled installation. Isolate that installation
  // so regression tests never acquire or modify a user's running bridge state.
  const installation = directory(t);
  cpSync(join(root, 'dist/src'), join(installation, 'dist/src'), { recursive: true });
  const child = spawn(process.execPath, [join(installation, 'dist/src/index.js'), '--mock'], { cwd: installation, windowsHide: true, stdio: ['pipe','pipe','pipe'] });
  let output = ''; child.stdout.setEncoding('utf8'); child.stdout.on('data', (chunk: string) => { output += chunk; }); child.stderr.on('data', chunk => { output += String(chunk); });
  const exited = new Promise<number | null>((resolveExit, reject) => { child.once('error', reject); child.once('close', resolveExit); });
  child.stdin.end('status\nhelp\nexit\n'); const code = await exited; assert.equal(code, 0, output); assert.match(output, /Mock only/); assert.match(output, /"connection": "ready"/);
});
test('failed initialization never marks protocol ready', async t => {
  const transport = new MockTransport(); const client = new AppServerClient(transport); t.after(() => client.close());
  transport.write = data => { const m = JSON.parse(data) as { id: number }; transport.emit({ id: m.id, error: { code: -32000, message: 'Initialization rejected' } }); };
  await assert.rejects(client.connect(), /Initialization rejected/); assert.equal(client.ready, false); assert.equal(client.pendingCount, 0);
});
test('concurrent RPC responses correlate correctly when delivered in reverse order', async t => {
  const transport = new MockTransport(); const client = new AppServerClient(transport); t.after(() => client.close()); await client.connect();
  const messages: { id: number }[] = []; transport.write = data => { messages.push(JSON.parse(data) as { id: number }); };
  const first = client.request('first', {}); const second = client.request('second', {});
  transport.emitRaw(JSON.stringify({ id: messages[1]!.id, result: 'second' }) + '\n' + JSON.stringify({ id: messages[0]!.id, result: 'first' }) + '\n');
  assert.equal(await first, 'first'); assert.equal(await second, 'second'); assert.equal(client.pendingCount, 0);
});
test('split credential-like deltas are not reconstructed in stored progress logs', async t => {
  const { dir, bridge, transport } = await fixture(t); const id = await bridge.create(dir, 'Redaction'); const turn = await bridge.send(id, '[mock:wait]'); const itemId = randomUUID();
  for (const delta of ['api_','key=','example','SecretValue']) transport.emit({ method: 'item/agentMessage/delta', params: { threadId: id, turnId: turn, itemId, delta } });
  transport.emit({ method: 'turn/completed', params: { threadId: id, turn: { id: turn, status: 'completed', items: [] } } });
  const stored = readFileSync(join(dir, 'state/state.json'), 'utf8'); assert.ok(!stored.includes('SecretValue')); assert.match(bridge.turns.get(id, turn)!.finalResponse, /REDACTED/);
});
