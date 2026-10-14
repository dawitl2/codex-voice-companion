export type LocalIntent = 'status' | 'result' | 'project' | 'repeat' | 'stop' | 'cancel' | 'confirm' | 'reject' | 'help' | 'hangup' | 'interrupt' | 'approvals' | 'deny' | 'queue' | 'queued' | 'cancelqueue' | 'ambiguous' | 'approve';
const grammar: Record<LocalIntent, string[]> = {
  status: ['status', 'what is the current task', "what's the current task", 'is codex still working', 'what happened with the last task', 'what happened last time', 'what is codex doing'],
  result: ['what was the last result', 'what did codex say', 'read the result', 'read the last result'],
  project: ['which project am i connected to', 'which project', 'what project is selected'],
  repeat: ['repeat', 'repeat that', 'repeat the summary', 'repeat the response'],
  stop: ['stop speaking', 'stop talking'],
  cancel: ['cancel', 'cancel that instruction', 'cancel the instruction', 'start over'],
  confirm: ['yes', 'yes send it', 'send it', 'confirm', 'yes please send it'],
  reject: ['no', 'no do not send it', "no don't send it"],
  help: ['help', 'what can i say', 'what can you do'],
  hangup: ['hang up', 'end the call', 'goodbye'],
  interrupt:['stop the current task','interrupt the current task','stop the running task'],
  approvals:['are there any approvals waiting','read the approval summary','show the approval details','open the approval details'],
  deny:['deny the pending approval'],
  queue:['queue this after the current task'],
  queued:["what's queued",'what is queued','read the queued instruction'],
  cancelqueue:['cancel the queued instruction'],
  ambiguous:['stop','stop it','cancel the task'],
  approve:['approve','approve it','approve the pending approval','yes approve it'],
};
/** Whole normalized utterances only; keywords inside programming requests are not controls. */
export function voiceIntent(text: string): LocalIntent | 'instruction' {
  const normalized=text.toLowerCase().trim().replace(/[.,!?;:]+/g,' ').replace(/\s+/g,' ').trim().replace(/^(?:hey )?codex\s+/,'');
  for (const [intent, phrases] of Object.entries(grammar)) if (phrases.includes(normalized)) return intent as LocalIntent;
  return 'instruction';
}
