const { app, BrowserWindow, ipcMain, dialog, session, Tray, Menu, nativeImage } = require('electron');
const { spawn, execFile } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
let window, worker, latest = { running: false, error: '', connectedPhones: 0 }, nextId = 0, pending = new Map(), stopping;
let config, settingsPath, backend;
let tray, quitting=false, trayStatus='';
function restore(){window?.show();window?.restore();window?.focus();}
function updateTray(){
  if(!tray)return;
  const status=stopping?'Stopping':latest.starting?'Starting':latest.running?'Running':'Stopped';
  if(status===trayStatus)return;trayStatus=status;tray.setToolTip('Codex Voice · '+status);
  const imagePath=path.join(__dirname,latest.running?'tray-running.png':'tray-stopped.png');if(fs.existsSync(imagePath))tray.setImage(nativeImage.createFromPath(imagePath));
  tray.setContextMenu(Menu.buildFromTemplate([
    {label:'Open Codex Voice',click:restore},{label:status,enabled:false},{type:'separator'},
    {label:'Start Companion',enabled:!worker&&!stopping,click:()=>{void start({writable:config.writable,notifications:config.notifications,sparkConfirmed:config.sparkConfirmed}).catch(e=>{latest.error=e.message;restore();});}},
    {label:'STOP ALL',enabled:!!worker&&!stopping,click:()=>{void stop(true);}},
    {type:'separator'},{label:'Quit Completely',click:()=>{void quitCompletely();}}
  ]));
}
async function quitCompletely(){if(await stop(true)===false)return;quitting=true;tray?.destroy();tray=undefined;window?.destroy();app.quit();}
const permitted = new Map(Object.entries({ state: [], start: ['writable','notifications','sparkConfirmed'], stop: [], quit: [], project: [], create: ['label'], select: ['threadId'],
  send: ['threadId','text'], interrupt: ['threadId'], pair: [], revoke: ['deviceId'], nominate: ['approvalId'], decision: ['approvalId','accept'], trust: ['deviceId','fingerprint'] }));
function validAction(type, value) {
  const keys = permitted.get(type);
  if (!keys || !value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).some(k => !keys.includes(k))) throw Error('Unsupported action');
  for (const [k,v] of Object.entries(value)) {
    if (['writable','notifications','sparkConfirmed','accept'].includes(k)) { if (typeof v !== 'boolean') throw Error('Explicit choice required'); }
    else if (typeof v !== 'string' || v.length > (k === 'text' ? 2000 : k === 'label' ? 80 : 128) || /[\x00-\x08\x0b-\x1f]/.test(v)) throw Error('Invalid input');
  }
}
function request(type, payload = {}) {
  if (!worker || worker.exitCode !== null) return Promise.reject(Error('Start Companion first'));
  const id = ++nextId;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(Error('Request timed out. Inspect the current task; do not resend uncertain work.')); }, 45000);
    pending.set(id, { resolve, reject, timer });
    worker.stdin.write(JSON.stringify({ id, type, payload }) + '\n');
  });
}
function save() { fs.mkdirSync(path.dirname(settingsPath), { recursive: true }); fs.writeFileSync(settingsPath, JSON.stringify(config)); }
async function start(payload) {
  if (worker) throw Error('Companion is already running or stopping');
  config.writable = payload.writable === true; config.notifications = payload.notifications === true;
  config.sparkConfirmed = payload.sparkConfirmed === true;
  if (!fs.statSync(config.project).isDirectory()) throw Error('Choose an existing project folder');
  save();
  const runConfig = { ...config, assets: backend, codex: path.join(backend, '.cache/codex-runtime/node_modules/@openai/codex-win32-x64/vendor/x86_64-pc-windows-msvc/bin/codex.exe') };
  const runPath = path.join(path.dirname(settingsPath), 'active-config.json'); fs.writeFileSync(runPath, JSON.stringify(runConfig));
  const node = app.isPackaged ? path.join(backend, 'node.exe') : path.join(process.env.USERPROFILE, '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe');
  const script = path.join(backend, 'dist/src/desktop/worker.js');
  latest = { running: false, starting: true, project: config.project, error: '' };
  updateTray();
  worker = spawn(node, [script, runPath], { cwd: backend, shell: false, windowsHide: true, stdio: ['pipe','pipe','pipe'] });
  let buffer = '';
  worker.stdout.setEncoding('utf8'); worker.stderr.resume(); // no raw subprocess diagnostic log or credentials
  worker.stdout.on('data', chunk => {
    buffer += chunk; if (buffer.length > 1048576) { worker?.kill(); return; }
    let end; while ((end = buffer.indexOf('\n')) >= 0) {
      const raw = buffer.slice(0, end); buffer = buffer.slice(end+1);
      try { const m = JSON.parse(raw);
        if (m.type === 'state') { latest = m.state; updateTray(); if (latest.selectedThreadId && config.selectedThreadId !== latest.selectedThreadId) { config.selectedThreadId = latest.selectedThreadId; save(); } }
        else { const p = pending.get(m.id); if (p) { clearTimeout(p.timer); pending.delete(m.id); m.error ? p.reject(Error(m.error)) : p.resolve(m.result); } }
      } catch { latest.error = 'Companion returned invalid state'; }
    }
  });
  worker.once('error', () => { latest.error = 'Could not launch the bundled Node runtime'; });
  worker.once('close', () => {
    worker = undefined; latest = { running: false, connectedPhones: 0, mediaSessions: 0, error: latest.error || '', project: config.project };
    updateTray();
    for (const p of pending.values()) { clearTimeout(p.timer); p.reject(Error('Companion stopped; verify any uncertain task on PC')); } pending.clear();
  });
  try { latest = await request('start'); updateTray(); return latest; }
  catch (e) { latest.error = e.message; await stop(false); throw e; }
}
async function stop(confirm = true) {
  if (!worker) return;
  if (confirm && latest.turns?.some(t => !['completed','failed','interrupted'].includes(t.status))) {
    restore();
    const { response } = await dialog.showMessageBox(window, { type: 'warning', title: 'Stop owned Codex work?',
      message: 'STOP ALL will end calls and stop this companion’s Codex process. Its active work may be interrupted.', buttons: ['Cancel','Stop All'], defaultId: 0, cancelId: 0 });
    if (response !== 1) return false;
  }
  if (stopping) return stopping;
  stopping = (async () => {
    const child = worker;
    try { await request('stop'); } catch { child?.stdin.end(); }
    if (child && child.exitCode === null) await new Promise(resolve => {
      let forcing=false;
      const timer = setTimeout(() => {
        forcing=true;
        // Fixed executable and our still-live child PID only; never kill by image name.
        execFile(path.join(process.env.SystemRoot||'C:\\Windows','System32/taskkill.exe'),['/PID',String(child.pid),'/T','/F'],{windowsHide:true,timeout:10000},()=>{child.kill();resolve();});
      }, 10000);
      child.once('close', () => { clearTimeout(timer); if(!forcing)resolve(); });
    });
    latest.running = false;
  })();
  updateTray();
  try { await stopping; } finally { stopping = undefined; updateTray(); }
  return true;
}
if (!app.requestSingleInstanceLock()) app.exit(0);
app.on('second-instance', restore);
app.whenReady().then(() => {
  app.setAppUserModelId('com.codexvoice.desktop');
  backend = app.isPackaged ? path.join(__dirname, '../backend') : path.join(__dirname, '..');
  settingsPath = path.join(app.getPath('home'), '.codex-voice/desktop/settings.json');
  config = fs.existsSync(settingsPath) ? JSON.parse(fs.readFileSync(settingsPath, 'utf8')) : {
    project: app.getPath('documents'), writable: false, notifications: true, sparkConfirmed: false,
    credentialPath: path.join(app.getPath('home'), '.codex-voice/firebase/service-account.json'), data: path.join(app.getPath('home'), '.codex-voice/desktop/data') };
  const rendererPath = path.join(__dirname, 'index.html');
  window = new BrowserWindow({ width: 1120, height: 800, minWidth: 820, minHeight: 640, title: 'Codex Voice',
    icon: path.join(__dirname, 'icon.ico'), backgroundColor: '#f5f5f7', autoHideMenuBar: true,
    webPreferences: { preload: path.join(__dirname, 'preload.cjs'), contextIsolation: true, sandbox: true, nodeIntegration: false, webSecurity: true } });
  window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  window.webContents.on('will-navigate', e => e.preventDefault());
  window.webContents.on('will-attach-webview', e => e.preventDefault());
  session.defaultSession.setPermissionRequestHandler((_w, _p, done) => done(false));
  session.defaultSession.setPermissionCheckHandler(() => false);
  ipcMain.handle('companion-action', async (event, type, payload = {}) => {
    if (event.sender !== window.webContents || event.senderFrame?.url !== pathToFileURL(rendererPath).href) throw Error('Untrusted caller');
    validAction(type, payload);
    if (type === 'state') return { ...latest, configuration: { project: config.project, writable: config.writable, notifications: config.notifications, sparkConfirmed: config.sparkConfirmed } };
    if (type === 'project') {
      if (worker) throw Error('Stop Companion before changing its project or execution policy');
      const { canceled, filePaths } = await dialog.showOpenDialog(window, { title: 'Choose an authorized Codex project', properties: ['openDirectory'] });
      if (!canceled && filePaths[0]) { config.project = fs.realpathSync(filePaths[0]); config.writable = false; save(); }
      return { project: config.project };
    }
    if (type === 'start') return start(payload);
    if (type === 'stop') return { stopped: await stop(true) };
    if (type === 'quit') { await quitCompletely(); return {}; }
    return request(type, payload);
  });
  tray=new Tray(nativeImage.createFromPath(path.join(__dirname,'icon.ico')));tray.on('click',restore);tray.on('double-click',restore);updateTray();
  window.on('close', e => { if(!quitting){e.preventDefault();window.hide();} });
  window.loadFile(rendererPath);
});
app.on('window-all-closed',()=>{if(quitting)app.quit();});
app.on('before-quit',e=>{if(!quitting){e.preventDefault();void quitCompletely();}});
