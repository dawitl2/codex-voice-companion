export interface SpeechSegment { pcm: Buffer; startedAt: number; endedAt: number }
/** Preliminary adaptive energy VAD, with pre-roll and explicit Finish Speaking fallback. */
export class UtteranceSegmenter {
  private pre: Buffer[] = []; private frames: Buffer[] = []; private onset = 0; private voiced = 0;
  private noise = .002; private began = 0; private lastVoice = 0; private silent = 0;
  get detecting() { return this.frames.length > 0; }
  snapshot():SpeechSegment|null{return this.frames.length>=50&&this.voiced>=15?{pcm:Buffer.concat(this.frames),startedAt:this.began,endedAt:this.lastVoice}:null;}
  accept(pcm: Buffer, level: number, now: number): SpeechSegment | null {
    if (pcm.length !== 640 || !Number.isFinite(level) || level < 0 || level > 1) throw new Error('invalid_pcm');
    const speech = level >= Math.max(.008, this.noise * 2.5);
    if (!this.detecting) {
      if (!speech) this.noise = Math.min(.02, this.noise * .98 + level * .02);
      this.pre.push(Buffer.from(pcm)); if (this.pre.length > 8) this.pre.shift()!.fill(0);
      this.onset = speech ? this.onset + 1 : 0;
      if (this.onset >= 3) { this.frames = this.pre; this.pre = []; this.began = now - this.frames.length * 20; this.lastVoice = now; this.voiced = 3; this.silent = 0; }
      return null;
    }
    this.frames.push(Buffer.from(pcm)); if (speech) { this.lastVoice = now; this.silent = 0; this.voiced++; } else this.silent++;
    return this.silent >= 60 || this.frames.length >= 1000 ? this.finish() : null;
  }
  finish(): SpeechSegment | null {
    const result = this.frames.length && this.voiced >= 15 ? { pcm: Buffer.concat(this.frames), startedAt: this.began, endedAt: this.lastVoice } : null;
    this.clear(); return result;
  }
  clear() { for (const frame of [...this.pre, ...this.frames]) frame.fill(0); this.pre = []; this.frames = []; this.onset = 0; this.voiced = 0; this.silent = 0; this.began = 0; this.lastVoice = 0; }
}
