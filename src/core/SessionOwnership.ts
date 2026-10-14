import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {resolve,relative,isAbsolute} from 'node:path';
import {homedir} from 'node:os';
import {existsSync,realpathSync} from 'node:fs';
const execute=promisify(execFile);
/** Read-only Windows Restart Manager query. Unknown ownership fails closed. */
export class SessionOwnership {
  private readonly abort=new AbortController();
  constructor(private readonly assets:string,private readonly ownedPid:()=>number|undefined){}
  async check(path:string){
    if(process.platform!=='win32')throw Error('Stored-session ownership checks require Windows');
    const home=realpathSync(resolve(process.env.CODEX_HOME||resolve(homedir(),'.codex'))),file=realpathSync(path),r=relative(home,file);
    if(!r||r.startsWith('..')||isAbsolute(r)||!existsSync(file))throw Error('Stored session path is unavailable or outside Codex home');
    const result=await execute('powershell.exe',['-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',resolve(this.assets,'scripts/session-owners.ps1'),'-Path',file],{windowsHide:true,timeout:8000,maxBuffer:2048,signal:this.abort.signal});
    const pids:unknown=JSON.parse(result.stdout.trim());if(!Array.isArray(pids)||pids.some(p=>!Number.isSafeInteger(p)||p<1))throw Error('Stored-session ownership could not be verified');
    if(pids.some(p=>p!==this.ownedPid()))throw Error('This conversation is owned by another process. Close its original Codex session before resuming it here.');
  }
  close(){this.abort.abort();}
}
