import type { RegistryStorage } from '../remote/DeviceRegistry.js';
export const SUBMISSION_STATES=['submitting','accepted','running','awaitingApproval','completed','failed','interrupted','unknown','rejected'] as const;
export type SubmissionState=typeof SUBMISSION_STATES[number];
export interface Submission {
  id:string; deviceId:string; callId:string; threadId:string; turnId:string|null;
  state:SubmissionState; submittedAt:string; acceptedAt:string|null; firstOutputAt:string|null; finishedAt:string|null;
}
/** DPAPI metadata only: no recognized/proposed instruction, result text or microphone data. */
export class SubmissionLedger {
  private rows:Submission[];
  constructor(private readonly storage:RegistryStorage) {
    const raw=storage.load();
    if(!Array.isArray(raw) || raw.length>200) throw new Error('invalid_submission_store');
    const fields=['id','deviceId','callId','threadId','turnId','state','submittedAt','acceptedAt','firstOutputAt','finishedAt'];
    for(const value of raw) {
      if(!value || typeof value!=='object' || Object.keys(value).length!==fields.length || fields.some(k=>!Object.hasOwn(value,k)) ||
        ![value.id,value.deviceId,value.callId].every(v=>typeof v==='string' && /^[a-f0-9-]{36}$/.test(v)) ||
        ![value.threadId,...(value.turnId===null?[]:[value.turnId])].every(v=>typeof v==='string' && /^[A-Za-z0-9_-]{1,128}$/.test(v)) ||
        !SUBMISSION_STATES.includes(value.state) || !Number.isFinite(Date.parse(value.submittedAt)) ||
        [value.acceptedAt,value.firstOutputAt,value.finishedAt].some(v=>v!==null && (typeof v!=='string' || !Number.isFinite(Date.parse(v))))) throw new Error('invalid_submission_store');
    }
    if(new Set(raw.map(v=>v.id)).size!==raw.length) throw new Error('invalid_submission_store');
    this.rows=raw as Submission[];
    // A process restart is never permission to replay a possibly dispatched request.
    for(const row of this.rows) if(row.state==='submitting') row.state='unknown';
    if(this.rows.length) this.storage.save(this.rows);
  }
  get(id:string) { return this.rows.find(r=>r.id===id); }
  list() { return structuredClone(this.rows); }
  uncertain(threadId:string) { return this.rows.some(r=>r.threadId===threadId && ['submitting','unknown'].includes(r.state)); }
  add(row:Submission) {
    if(this.get(row.id)) throw new Error('duplicate_submission');
    const next=[...this.rows,row];
    while(next.length>200) {
      const index=next.findIndex(r=>['completed','failed','interrupted','rejected'].includes(r.state));
      if(index<0) throw new Error('submission_store_full'); next.splice(index,1);
    }
    this.storage.save(next); this.rows=next;
  }
  update(id:string,patch:Partial<Pick<Submission,'turnId'|'state'|'acceptedAt'|'firstOutputAt'|'finishedAt'>>) {
    const next=this.rows.map(r=>r.id===id?{...r,...patch}:r); this.storage.save(next); this.rows=next;
  }
}
