import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { createReadStream } from 'node:fs';
import { access, stat } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve } from 'node:path';
import { performance } from 'node:perf_hooks';

export interface Recognition { text: string; language: 'en'; processingMs: number; loadMs: number | null }
export interface SpeechRecognitionEngine {
  prepare(): Promise<void>;
  recognize(pcm: Buffer, signal: AbortSignal): Promise<Recognition>;
  close(): void;
}
export class SpeechError extends Error { constructor(readonly code: string) { super(code); } }
export const TINY_SHA256 = '921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f';
export function pcmWav(pcm: Buffer): Buffer {
  if (!pcm.length || pcm.length % 640 !== 0 || pcm.length > 640 * 1000) throw new SpeechError('invalid_speech_segment');
  const wav = Buffer.alloc(44 + pcm.length); wav.write('RIFF'); wav.writeUInt32LE(36 + pcm.length, 4); wav.write('WAVEfmt ', 8);
  wav.writeUInt32LE(16, 16); wav.writeUInt16LE(1, 20); wav.writeUInt16LE(1, 22); wav.writeUInt32LE(16000, 24);
  wav.writeUInt32LE(32000, 28); wav.writeUInt16LE(2, 32); wav.writeUInt16LE(16, 34); wav.write('data', 36); wav.writeUInt32LE(pcm.length, 40); pcm.copy(wav, 44); return wav;
}
/** One bounded CPU inference job per utterance, shell-free; WAV stays on stdin, never disk. */
export class WhisperCppEngine implements SpeechRecognitionEngine {
  private child: ChildProcessWithoutNullStreams | undefined;
  private available = false; private closed = false;
  readonly executable: string; readonly model: string;
  constructor(root: string, private readonly timeoutMs = 30000) {
    this.executable = resolve(root, '.cache/speech/whisper-b5454/Release/whisper-cli.exe');
    this.model = resolve(root, '.cache/speech/models/ggml-tiny.en.bin');
  }
  async prepare() {
    if (this.closed) throw new SpeechError('speech_closed');
    try { await access(this.executable); } catch { throw new SpeechError('asr_engine_missing'); }
    try { if ((await stat(this.model)).size !== 77704715) throw new SpeechError('asr_model_invalid'); }
    catch (error) { if (error instanceof SpeechError) throw error; throw new SpeechError('asr_model_missing'); }
    const hash = createHash('sha256'); for await (const chunk of createReadStream(this.model)) hash.update(chunk);
    if (hash.digest('hex') !== TINY_SHA256) throw new SpeechError('asr_model_invalid');
    this.available = true;
  }
  async recognize(pcm: Buffer, signal: AbortSignal): Promise<Recognition> {
    if (!this.available || this.closed) throw new SpeechError('asr_unavailable');
    if (this.child) throw new SpeechError('asr_busy');
    if (signal.aborted) throw new SpeechError('recognition_cancelled');
    const wav = pcmWav(pcm); const started = performance.now();
    return new Promise((resolveResult, reject) => {
      const child = spawn(this.executable, ['-m', this.model, '-f', '-', '-l', 'en', '-t', '4', '-of', 'memory-only-callback', '-nt', '-np', '-ng', '-bo', '1', '-bs', '1'],
        { shell: false, windowsHide: true, stdio: ['pipe','pipe','pipe'], env: { SystemRoot: process.env.SystemRoot, TEMP: process.env.TEMP, PATH: process.env.PATH } });
      child.stdout.setEncoding('utf8'); child.stderr.setEncoding('utf8');
      this.child = child; let output = ''; let diagnostic = ''; let failure: string | null = null;
      const cancel = () => { failure = 'recognition_cancelled'; child.kill(); };
      signal.addEventListener('abort', cancel, { once: true });
      const timer = setTimeout(() => { failure = 'recognition_timeout'; child.kill(); }, this.timeoutMs);
      child.stdout.on('data', chunk => { if (output.length + chunk.length > 8192) { failure = 'recognition_output_limit'; child.kill(); } else output += chunk.toString('utf8'); });
      child.stderr.on('data', chunk => { if (diagnostic.length < 16384) diagnostic += chunk.toString('utf8').slice(0, 16384 - diagnostic.length); });
      child.stdin.on('error', () => { /* Child error/close determines the fixed failure code. */ });
      const cleanup = () => { clearTimeout(timer); signal.removeEventListener('abort', cancel); wav.fill(0); if (this.child === child) this.child = undefined; };
      child.once('error', () => { cleanup(); reject(new SpeechError('asr_worker_failed')); });
      child.once('close', code => {
        cleanup(); if (failure || code !== 0 || this.closed || signal.aborted) { reject(new SpeechError(failure ?? 'asr_worker_failed')); return; }
        const text = output.replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim();
        if (!text || text.length > 2000) { reject(new SpeechError(text ? 'recognition_output_limit' : 'empty_transcription')); return; }
        const load = /load time\s*=\s*([\d.]+)\s*ms/.exec(diagnostic);
        resolveResult({ text, language: 'en', processingMs: Math.round(performance.now() - started), loadMs: load ? Number(load[1]) : null });
        output = ''; diagnostic = '';
      });
      child.stdin.end(wav);
    });
  }
  close() { this.closed = true; this.available = false; this.child?.kill(); }
}
