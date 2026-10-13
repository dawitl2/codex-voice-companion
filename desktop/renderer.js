const $ = id => document.getElementById(id);
const esc = v => String(v ?? '').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let state = {}, initialized = false, tab = 'dashboard', busy = false;
async function action(type, payload = {}) { $('error').textContent = ''; try { return await window.companion.action(type,payload); } catch(e) { $('error').textContent=e.message; throw e; } }
function row(label, yes, text) { return `<div class="row"><span>${esc(label)}</span><span class="${yes===null?'':yes?'good':'bad'}">${esc(text)}</span></div>`; }
function render(s) {
  state = s; const cfg=s.configuration||{};
  if (!initialized) { $('writable').checked=cfg.writable===true; $('notifications').checked=cfg.notifications!==false; $('spark').checked=cfg.sparkConfirmed===true; initialized=true; }
  $('project').textContent=s.project||cfg.project||'Choose a project'; $('policy').textContent=s.policy||'Stopped · project permissions apply at Start';
  $('footer-state').textContent=s.starting?'Starting…':s.running?'Companion running':'Stopped';
  $('start').disabled=busy||s.running||s.starting; $('stop').disabled=busy||(!s.running&&!s.starting); $('choose-project').disabled=!!s.running||!!s.starting;
  $('writable').disabled=!!s.running||!!s.starting; $('notifications').disabled=!!s.running||!!s.starting; $('spark').disabled=!!s.running||!!s.starting;
  $('health').innerHTML=row('Companion',s.running,s.running?'Running':'Stopped')+row('Codex',s.codexConnected,s.codexConnected?'Connected':'Disconnected')+
    row('Authentication',s.codexConnected,s.authentication||'Not checked')+row('Tailscale',s.tailscale,s.tailscale?'Available':'Not checked')+
    row('Firebase',s.firebase,s.firebase?'Ready':s.firebaseDiagnostic||'Unavailable')+row('Local speech',s.speech?.engine==='ready',s.speech?.engine||'Not checked')+
    row('Phone',s.connectedPhones>0,s.connectedPhones?`${s.connectedPhones} authenticated`:'Offline');
  $('events').innerHTML=(s.events||[]).slice(-6).reverse().map(e=>row(new Date(e.timestamp).toLocaleTimeString(),null,e.summary)).join('')||'<p>No recent activity.</p>';
  $('threads').innerHTML=(s.threads||[]).map(t=>`<button class="thread ${t.id===s.selectedThreadId?'chosen':''}" data-select="${esc(t.id)}">${esc(t.label)}<small><br>${esc(t.model)}</small></button>`).join('')||'<p>Create your first managed conversation.</p>';
  const t=s.threads?.find(t=>t.id===s.selectedThreadId), turns=(s.turns||[]).filter(x=>x.threadId===s.selectedThreadId);
  $('chat-heading').textContent=t?.label||'Choose a conversation'; $('task-state').textContent=turns.at(-1)?.status||'Ready';
  $('messages').innerHTML=turns.map(x=>`<div class="message"><small>You · ${esc(x.status)}</small>${esc(x.instruction||'Earlier instruction unavailable in local history')}</div><div class="message response"><small>Codex</small>${esc(x.finalResponse||'Waiting for the actual response…')}</div>`).join('');
  $('send').disabled=!s.codexConnected||!t||busy||['running','starting','awaitingApproval','unknown'].includes(turns.at(-1)?.status);
  $('create').disabled=!s.codexConnected||busy; $('pair').disabled=!s.running||busy; $('interrupt').disabled=!s.codexConnected||!['running','starting','awaitingApproval'].includes(turns.at(-1)?.status);
  $('phones').innerHTML=(s.paired||[]).map(d=>`<div class="row"><div><strong>${esc(d.name)}</strong><p>${d.connected?'Authenticated · connected':'Paired · offline'} · ${d.registered?'Notifications registered':'Registration pending'}</p><small>Last connection ${d.lastConnectedAt?esc(new Date(d.lastConnectedAt).toLocaleString()):'not observed this run'}</small></div><button data-revoke="${esc(d.id)}">Revoke…</button></div>`).join('')||(s.running?'<p>No phone paired.</p>':'<p>Start Companion to load paired phones.</p>');
  $('operations').innerHTML=(s.operations||[]).map(o=>row(o.device+' · '+o.kind,null,o.state+(o.text?' · '+o.text:''))).join('')||'<p>No pending follow-up.</p>';
  $('calls').innerHTML=row('Media sessions',s.mediaSessions>0,s.mediaSessions||0)+(s.calls||[]).slice(-4).map(c=>row(c.kind||'Call',c.state==='active',c.state)).join('');
  $('approvals-list').innerHTML=(s.approvals||[]).map(a=>`<div class="message"><strong>${esc(a.method)}</strong><pre>${esc(a.preview)}</pre><p>${esc(a.scope)} · ${esc(a.reason)}</p><small>Deadline ${esc(new Date(a.expiresAt).toLocaleTimeString())}</small><div class="actions"><button data-approve="${esc(a.id)}">Approve this action…</button><button data-deny="${esc(a.id)}">Deny</button><button data-nominate="${esc(a.id)}">Nominate for phone review</button></div></div>`).join('')||'<p>No pending approvals.</p>';
  $('reviewers').innerHTML=(s.reviewers||[]).map(r=>`<pre>${esc(r.fingerprint)}</pre><button data-trust="${esc(r.deviceId)}" data-fingerprint="${esc(r.fingerprint)}">${r.trusted?'Trusted':'Trust compared fingerprint…'}</button>`).join('')||'<p>Enable secure review on the paired phone to register its public key.</p>';
  if(s.error)$('error').textContent=s.error;
}
async function perform(f) { if(busy)return;busy=true;try{await f();}catch{}finally{busy=false;await refresh();} }
async function refresh(){try{render(await window.companion.action('state'));}catch(e){$('error').textContent=e.message;}}
document.querySelector('nav').addEventListener('click',e=>{const button=e.target.closest('[data-tab]');if(!button)return;tab=button.dataset.tab;document.querySelectorAll('main>section').forEach(s=>s.hidden=s.id!==tab);document.querySelectorAll('nav button').forEach(b=>b.classList.toggle('selected',b===button));$('heading').textContent=button.textContent;});
$('start').onclick=()=>perform(()=>action('start',{writable:$('writable').checked,notifications:$('notifications').checked,sparkConfirmed:$('spark').checked}));
$('stop').onclick=()=>perform(()=>action('stop')); $('quit').onclick=()=>action('quit').catch(()=>{});
$('choose-project').onclick=()=>perform(async()=>{await action('project');initialized=false;});
$('create').onclick=()=>perform(async()=>{await action('create',{label:$('thread-label').value.trim()||'New conversation'});$('thread-label').value='';});
$('send').onclick=()=>perform(async()=>{await action('send',{threadId:state.selectedThreadId,text:$('instruction').value});$('instruction').value='';});
$('interrupt').onclick=()=>{if(confirm('Request interruption of this actual task? Wait for the authoritative outcome.'))perform(()=>action('interrupt',{threadId:state.selectedThreadId}));};
$('pair').onclick=()=>perform(async()=>{const p=await action('pair');$('invitation').hidden=false;$('invitation').innerHTML=`<p>Enter this private address and one-time code in Android Settings → PC monitor.</p><code>${esc(p.endpoint)}</code><strong>${esc(p.code)}</strong><small>Expires ${esc(new Date(p.expiresAt).toLocaleTimeString())}. Never share this invitation.</small>`;setTimeout(()=>{$('invitation').textContent='Invitation expired. Request a fresh one.';},Math.max(0,Date.parse(p.expiresAt)-Date.now()));});
document.addEventListener('click',e=>{const b=e.target.closest('button');if(!b)return;
  if(b.dataset.select)perform(()=>action('select',{threadId:b.dataset.select}));
  if(b.dataset.revoke&&confirm('Revoke this phone pairing and end its active calls?'))perform(()=>action('revoke',{deviceId:b.dataset.revoke}));
  if(b.dataset.approve&&confirm('Approve this exact action once? Review the complete operation and scope above.'))perform(()=>action('decision',{approvalId:b.dataset.approve,accept:true}));
  if(b.dataset.deny)perform(()=>action('decision',{approvalId:b.dataset.deny,accept:false}));
  if(b.dataset.nominate&&confirm('Have you reviewed this exact local action and its effects?'))perform(()=>action('nominate',{approvalId:b.dataset.nominate}));
  if(b.dataset.trust&&confirm('Does the full fingerprint match the paired phone screen?'))perform(()=>action('trust',{deviceId:b.dataset.trust,fingerprint:b.dataset.fingerprint}));
});
refresh();setInterval(refresh,1200);
