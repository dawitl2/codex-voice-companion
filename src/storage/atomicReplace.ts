import { renameSync } from 'node:fs';

// Windows scanners can briefly hold the destination without delete-sharing.
// Keep the prior file intact, and retry replacement only for sharing failures.
export function atomicReplace(source: string, destination: string, rename = renameSync): void {
  for (let attempt = 0; ; attempt++) {
    try { rename(source, destination); return; }
    catch (error) {
      if (process.platform !== 'win32' || !['EPERM','EACCES','EBUSY'].includes((error as NodeJS.ErrnoException).code ?? '') || attempt >= 5) throw error;
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 5 * 2 ** attempt);
    }
  }
}
