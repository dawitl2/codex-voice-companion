const { app, BrowserWindow, ipcMain, dialog, session, Tray, Menu, nativeImage, shell } = require('electron');
const { spawn, execFile } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
let window, worker, latest = { running: false, error: '', connectedPhones: 0 }, nextId = 0, pending = new Map(), stopping;
let config, settingsPath, backend;
let tray, quitting=false, trayStatus='', setupBusy=false;
const setupChildren = new Set();
const execute = (file, args, options = {}) => new Promise((resolve, reject) => {
  const child = execFile(file, args, { shell: false, windowsHide: true, timeout: 15000, ...options }, (error, stdout, stderr) => error ? reject(error) : resolve({ stdout, stderr }));
  setupChildren.add(child);
  child.once('close', () => setupChildren.delete(child));
});
const tailscalePath = () => path.join(process.env.ProgramFiles || 'C:\\Program Files', 'Tailscale/tailscale.exe');
const codexPath = () => path.join(backend, '.cache/codex-runtime/node_modules/@openai/codex-win32-x64/vendor/x86_64-pc-windows-msvc/bin/codex.exe');
function restore(){window?.show();window?.restore();window?.focus();}
function updateTray(){
  if(!tray)return;
  const status=stopping?'Stopping':latest.starting?'Starting':latest.running?'Running':'Stopped';
  if(status===trayStatus)return;trayStatus=status;tray.setToolTip('Codex Voice · '+status);
  const imagePath=path.join(__dirname,latest.running?'tray-running.png':'tray-stopped.png');if(fs.existsSync(imagePath))tray.setImage(nativeImage.createFromPath(imagePath));
  tray.setContextMenu(Menu.buildFromTemplate([
    {label:'Open Codex Voice',click:restore},{label:status,enabled:false},{type:'separator'},
    {label:'Start Companion',enabled:!worker&&!stopping&&!latest.starting,click:()=>{void start({writable:config.writable,notifications:config.notifications,sparkConfirmed:config.sparkConfirmed}).catch(e=>{latest.error=e.message;restore();});}},
    {label:'STOP ALL',enabled:!!worker&&!stopping,click:()=>{void stop(true);}},
    {type:'separator'},{label:'Quit Completely',click:()=>{void quitCompletely();}}
  ]));
}
async function quitCompletely(){if(await stop(true)===false)return;for(const child of setupChildren)child.kill();quitting=true;tray?.destroy();tray=undefined;window?.destroy();app.quit();}
const permitted = new Map(Object.entries({ state: [], start: ['writable','notifications','sparkConfirmed'], stop: [], quit: [], project: [], create: ['label'], select: ['threadId'],
  send: ['threadId','text'], interrupt: ['threadId'], pair: [], revoke: ['deviceId'], nominate: ['approvalId'], decision: ['approvalId','accept'], trust: ['deviceId','fingerprint'],credentials:[],login:[],privateNetwork:[],openTailscale:[],discover:[],resumeStored:['threadId'],latest:[],openOriginal:['threadId'] }));
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
  if (worker || setupBusy) throw Error('Companion is already running or setup is in progress');
  config.writable = payload.writable === true; config.notifications = payload.notifications === true;
  config.sparkConfirmed = payload.sparkConfirmed === true;
  if (!fs.statSync(config.project).isDirectory()) throw Error('Choose an existing project folder');
  save();
  const runConfig = { ...config, assets: backend, codex: codexPath() };
  const runPath = path.join(path.dirname(settingsPath), 'active-config.json'); fs.writeFileSync(runPath, JSON.stringify(runConfig));
  const node = app.isPackaged ? path.join(backend, 'node.exe') : process.env.CODEX_VOICE_NODE_PATH ||
    (process.env.PATH || '').split(path.delimiter).map(folder => path.join(folder, 'node.exe')).find(file => fs.existsSync(file)) ||
    path.join(app.getPath('home'), '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe');
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
    project: app.getPath('documents'), writable: false, notifications: false, sparkConfirmed: false,
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
    if (type === 'state') return { ...latest, configuration: { project: config.project, writable: config.writable, notifications: config.notifications, sparkConfirmed: config.sparkConfirmed, credentialConfigured: fs.existsSync(config.credentialPath), setupBusy } };
    if (type === 'project') {
      if (worker) throw Error('Stop Companion before changing its project or execution policy');
      const { canceled, filePaths } = await dialog.showOpenDialog(window, { title: 'Choose an authorized Codex project', properties: ['openDirectory'] });
      if (!canceled && filePaths[0]) { config.project = fs.realpathSync(filePaths[0]); config.writable = false; save(); }
      return { project: config.project };
    }
    if (type === 'openTailscale') {
      const executable = path.join(path.dirname(tailscalePath()), 'tailscale-ipn.exe');
      if (!fs.existsSync(executable)) { await shell.openExternal('https://tailscale.com/download/windows'); return {}; }
      const error = await shell.openPath(executable);
      if (error) throw Error('Could not open Tailscale. Open it from the Windows Start menu.');
      return {};
    }
    if (type === 'login' || type === 'privateNetwork') {
      if (worker || setupBusy) throw Error('Stop Companion before changing connection setup');
      setupBusy = true;
      try {
        if (type === 'login') {
          const { safeChildEnvironment } = await import(pathToFileURL(path.join(backend, 'dist/src/config/settings.js')).href);
          const environment = safeChildEnvironment();
          const status = await execute(codexPath(), ['login', 'status'], { env: environment }).catch(() => null);
          if (status && /Logged in using ChatGPT/i.test(status.stdout + status.stderr)) return { message: 'Your existing ChatGPT login is ready.' };
          await execute(codexPath(), ['login'], { env: environment, timeout: 180000 }).catch(() => { throw Error('Sign-in did not complete. Retry and finish the official browser sign-in.'); });
          const verified = await execute(codexPath(), ['login', 'status'], { env: environment });
          if (!/Logged in using ChatGPT/i.test(verified.stdout + verified.stderr)) throw Error('A ChatGPT login is required; API-key login is not supported.');
          return { message: 'ChatGPT sign-in saved by Codex on this PC.' };
        }
        let tail, serve;
        try {
          tail = JSON.parse((await execute(tailscalePath(), ['status', '--json'])).stdout);
          serve = JSON.parse((await execute(tailscalePath(), ['serve', 'status', '--json'])).stdout);
        } catch { throw Error('Install Tailscale and sign in, then set up the private connection again.'); }
        if (tail.BackendState !== 'Running' || !tail.Self?.DNSName) throw Error('Open Tailscale and sign in on this PC first.');
        const dns = tail.Self.DNSName.replace(/\.$/, ''), host = dns + ':443';
        const tcp = serve.TCP || {}, web = serve.Web || {}, funnel = serve.AllowFunnel || {};
        const handlers = web[host]?.Handlers || {};
        if (Object.keys(funnel).length) throw Error('Public Funnel is configured. Resolve it in Tailscale before using this private companion.');
        const configured = Object.keys(tcp).length === 1 && tcp['443']?.HTTPS === true && Object.keys(web).length === 1 &&
          Object.keys(handlers).length === 1 && handlers['/']?.Proxy === 'http://127.0.0.1:8787';
        if (configured) return { message: 'Private connection is configured and retained by Tailscale.' };
        if (Object.keys(tcp).length || Object.keys(web).length || Object.keys(serve.Services || {}).length) throw Error('This PC has other Tailscale Serve routes. They were left unchanged; use a dedicated node or resolve the conflict first.');
        await execute(tailscalePath(), ['serve', '--bg', 'http://127.0.0.1:8787']).catch(() => { throw Error('Tailscale could not enable the private route. Review its Windows access settings and retry.'); });
        return { message: 'Private HTTPS configured. Start Companion, then pair your phone.' };
      } finally { setupBusy = false; }
    }
    if (type === 'credentials') {
      if (worker || setupBusy) throw Error('Stop Companion and finish setup before changing notifications');
      setupBusy = true;
      try {
      const { canceled, filePaths } = await dialog.showOpenDialog(window, { title: 'Choose your Firebase service-account JSON', properties: ['openFile'], filters: [{ name: 'JSON', extensions: ['json'] }] });
      if (!canceled && filePaths[0]) {
        const candidate = fs.realpathSync(filePaths[0]);
        const relative = path.relative(backend, candidate);
        if (!relative.startsWith('..' + path.sep) && !path.isAbsolute(relative)) throw Error('Store credentials outside the application folder');
        if (fs.statSync(candidate).size > 65536) throw Error('Choose a service-account JSON smaller than 64 KB');
        let account;
        try { account = JSON.parse(fs.readFileSync(candidate, 'utf8')); } catch { throw Error('The selected file is not valid service-account JSON'); }
        if (account.type !== 'service_account' || typeof account.project_id !== 'string' || typeof account.private_key !== 'string') throw Error('Choose a Firebase service-account JSON, not the Android client file');
        const directory = path.join(app.getPath('home'), '.codex-voice/firebase');
        const protector = path.join(backend, 'scripts/protect-firebase-credentials.ps1');
        const protect = () => new Promise((resolve, reject) => execFile(path.join(process.env.SystemRoot || 'C:\\Windows', 'System32/WindowsPowerShell/v1.0/powershell.exe'),
          ['-NoProfile', '-NonInteractive', '-File', protector, '-Directory', directory], { windowsHide: true, timeout: 15000 },
          error => error ? reject(Error('Could not protect the local credential folder')) : resolve()));
        await protect();
        const destination = path.join(directory, 'service-account.json');
        if (candidate.toLowerCase() !== destination.toLowerCase()) {
          if (fs.existsSync(destination)) {
            const { response } = await dialog.showMessageBox(window, { type: 'question', title: 'Replace notification account?', message: 'Replace the Firebase account saved on this PC? Your phone must use the matching Android client configuration.', buttons: ['Cancel', 'Replace account'], defaultId: 0, cancelId: 0 });
            if (response !== 1) return { configured: true };
          }
          const staged = path.join(directory, 'service-account-import-' + require('node:crypto').randomUUID() + '.json');
          let created = false;
          try {
            fs.writeFileSync(staged, JSON.stringify(account), { flag: 'wx' });
            created = true;
            await protect();
            fs.renameSync(staged, destination);
          } finally { if (created && fs.existsSync(staged)) fs.unlinkSync(staged); }
        }
        await protect();
        config.credentialPath = destination; save();
      }
      return { configured: fs.existsSync(config.credentialPath) };
      } finally { setupBusy = false; }
    }
    if (type === 'start') return start(payload);
    if (type === 'stop') return { stopped: await stop(true) };
    if (type === 'quit') { await quitCompletely(); return {}; }
    if(type==='openOriginal'){
      if(typeof payload.threadId!=='string'||!/^[a-f0-9-]{36}$/.test(payload.threadId)||!latest.threads?.some(t=>t.id===payload.threadId))throw Error('Select a known original Codex chat');
      await shell.openExternal('codex://threads/'+payload.threadId);return {};
    }
    return request(type, payload);
  });
  tray=new Tray(nativeImage.createFromPath(path.join(__dirname,'icon.ico')));tray.on('click',restore);tray.on('double-click',restore);updateTray();
  window.on('close', e => { if(!quitting){e.preventDefault();window.hide();} });
  window.loadFile(rendererPath);
  if(app.commandLine.hasSwitch('start-companion'))void start({writable:config.writable,notifications:config.notifications,sparkConfirmed:config.sparkConfirmed}).catch(e=>{latest.error=e.message;});
});
app.on('window-all-closed',()=>{if(quitting)app.quit();});
app.on('before-quit',e=>{if(!quitting){e.preventDefault();void quitCompletely();}});
