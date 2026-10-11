import test from 'node:test';
import assert from 'node:assert/strict';
import { atomicReplace } from '../src/storage/atomicReplace.js';
test('Windows atomic replacement retries transient sharing failures and bounds persistent failure', { skip: process.platform !== 'win32' }, () => {
  let tries = 0;
  atomicReplace('source', 'target', () => { if (++tries < 3) throw Object.assign(new Error('sharing'), { code: 'EPERM' }); });
  assert.equal(tries, 3); tries = 0;
  assert.throws(() => atomicReplace('source', 'target', () => { tries++; throw Object.assign(new Error('sharing'), { code: 'EACCES' }); }), /sharing/);
  assert.equal(tries, 6);
});
test('atomic replacement never retries non-sharing failures', () => {
  let tries = 0;
  assert.throws(() => atomicReplace('source', 'target', () => { tries++; throw Object.assign(new Error('full'), { code: 'ENOSPC' }); }), /full/);
  assert.equal(tries, 1);
});
