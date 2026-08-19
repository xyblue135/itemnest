const state = {items:[], containers:[], currentView:'inventory', search:'', containerId:'', openContainerId:null, deferredPrompt:null};
const $ = s => document.querySelector(s);
const $$ = s => [...document.querySelectorAll(s)];

async function api(path, options={}) {
  const res = await fetch(path, {headers:{'Content-Type':'application/json', ...(options.headers||{})}, ...options});
  if (!res.ok) { let msg=`请求失败 ${res.status}`; try {const b=await res.json(); msg=b.detail||msg;} catch{} throw new Error(msg); }
  return res.status===204 ? null : res.json();
}
function escapeHtml(v=''){return String(v).replace(/[&<>'"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c]));}
function toast(text){const el=$('#toast');el.textContent=text;el.classList.add('show');clearTimeout(toast.t);toast.t=setTimeout(()=>el.classList.remove('show'),2200);}

async function loadAll(){
  const [summary, containers, items, settings, mqStatus] = await Promise.all([api('/api/summary'), api('/api/containers'), api('/api/items'), api('/api/settings'), api('/api/mq/status')]);
  state.containers=containers; state.items=items;
  renderSummary(summary); renderContainerOptions(); renderContainers(); applySettings(settings); applyMqStatus(mqStatus); renderSearchState();
  if(state.openContainerId) renderContainerDetail(state.openContainerId);
}
function renderSummary(s){$('#statContainers').textContent=s.containers;$('#statItems').textContent=s.items;$('#statQuantity').textContent=s.quantity;$('#statSpecial').textContent=s.special;}
function renderContainerOptions(){
  const opts=state.containers.map(c=>`<option value="${c.id}">${escapeHtml(c.name)}</option>`).join('');
  $('#itemContainer').innerHTML=opts;
}
function quantityText(i){if(i.quantity_text)return i.quantity_text; if(i.quantity===null||i.quantity===undefined)return '未记录'; return `${i.quantity} 个`;}
function renderItems(items){
  $('#resultMeta').textContent=`共 ${items.length} 类物品`;
  $('#itemsEmpty').hidden=items.length>0;
  $('#itemsGrid').innerHTML=items.map(i=>`<article class="item-card">
    <div class="item-top"><div class="item-icon">${escapeHtml((i.name||'物').trim().charAt(0).toUpperCase())}</div><div class="item-actions">
      <button class="icon-btn" title="编辑" onclick="openEditItem(${i.id})">✎</button><button class="icon-btn danger" title="删除" onclick="deleteItem(${i.id})">×</button></div></div>
    <h4>${escapeHtml(i.name)}</h4><div class="location">▣ ${escapeHtml(i.container_name)}</div>
    ${i.notes?`<p class="item-notes" title="${escapeHtml(i.notes)}">${escapeHtml(i.notes)}</p>`:''}
    <div class="item-meta"><span class="chip">${escapeHtml(quantityText(i))}</span>${i.condition&&i.condition!=='正常'?`<span class="chip bad">${escapeHtml(i.condition)}</span>`:''}${(i.tags||'').split(',').filter(Boolean).slice(0,2).map(t=>`<span class="chip">#${escapeHtml(t.trim())}</span>`).join('')}</div>
  </article>`).join('');
}
function containerCard(c){
  const note=c.notes||'收纳位置';
  return `<article class="container-card" role="button" tabindex="0" onclick="openContainer(${c.id})" onkeydown="if(event.key==='Enter'||event.key===' '){event.preventDefault();openContainer(${c.id})}">
    <div class="container-card-top"><div class="box-mark">▦</div><span class="box-closed">已收起</span></div>
    <h4>${escapeHtml(c.name)}</h4><p>${escapeHtml(note)}</p>
    <div class="container-bottom"><span class="container-count">${c.item_count} 类 · 约 ${c.quantity_sum} 件/组</span><span class="container-open">打开箱子 →</span></div>
  </article>`;
}
function renderContainers(){
  const html=state.containers.map(containerCard).join('');
  $('#inventoryContainersGrid').innerHTML=html;
  $('#containersGrid').innerHTML=html;
}
function renderSearchState(){
  const searching=Boolean(state.search.trim());
  $('#storageBrowser').hidden=searching;
  $('#searchResults').hidden=!searching;
  if(!searching){$('#itemsGrid').innerHTML='';$('#itemsEmpty').hidden=true;$('#resultMeta').textContent='输入关键词开始查找';return;}
  filterItems();
}
async function filterItems(){
  if(!state.search.trim())return;
  const params=new URLSearchParams({q:state.search.trim()});
  const items=await api(`/api/items?${params}`); renderItems(items);
}
function switchView(view){
  state.currentView=view; $$('.view').forEach(v=>v.classList.remove('active')); $(`#${view}View`).classList.add('active');
  $$('.nav-btn').forEach(b=>b.classList.toggle('active',b.dataset.view===view));
  $('#pageTitle').textContent={inventory:'我的收纳',containers:'箱子管理',assistant:'AI 助手',settings:'设置'}[view];
  $('#quickAddBtn').style.display=view==='settings'?'none':'';
}

function openItemModal(item=null){
  $('#itemModalTitle').textContent=item?'编辑物品':'添加物品'; $('#itemId').value=item?.id||''; $('#itemName').value=item?.name||''; $('#itemContainer').value=item?.container_id||state.containerId||state.openContainerId||state.containers[0]?.id||''; $('#itemQuantity').value=item?.quantity??1; $('#itemQuantityText').value=item?.quantity_text||''; $('#itemCondition').value=item?.condition||'正常'; $('#itemTags').value=item?.tags||''; $('#itemNotes').value=item?.notes||''; $('#modalBackdrop').hidden=false; setTimeout(()=>$('#itemName').focus(),20);
}
function detailItemRow(i){
  return `<article class="container-item-row">
    <div class="container-item-icon">${escapeHtml((i.name||'物').trim().charAt(0).toUpperCase())}</div>
    <div class="container-item-main"><h4>${escapeHtml(i.name)}</h4>${i.notes?`<p>${escapeHtml(i.notes)}</p>`:''}<div class="item-meta"><span class="chip">${escapeHtml(quantityText(i))}</span>${i.condition&&i.condition!=='正常'?`<span class="chip bad">${escapeHtml(i.condition)}</span>`:''}</div></div>
    <div class="container-item-actions"><button class="icon-btn" title="编辑" onclick="openEditItem(${i.id})">✎</button><button class="icon-btn danger" title="删除" onclick="deleteItem(${i.id})">×</button></div>
  </article>`;
}
function renderContainerDetail(id){
  const cid=Number(id); const c=state.containers.find(x=>x.id===cid); if(!c)return;
  const items=state.items.filter(i=>i.container_id===cid);
  state.openContainerId=cid; state.containerId=String(cid);
  $('#containerDetailTitle').textContent=c.name;
  $('#containerDetailNote').textContent=c.notes||'收纳位置';
  $('#containerDetailCount').textContent=`${c.item_count} 类 · 约 ${c.quantity_sum} 件/组`;
  $('#containerDetailItems').innerHTML=items.map(detailItemRow).join('');
  $('#containerDetailEmpty').hidden=items.length>0;
  $('#containerDetailEmptyText').textContent=c.notes||'这个箱子目前没有逐项登记的物品记录。';
}
window.openContainer=id=>{renderContainerDetail(id);$('#containerDetailBackdrop').hidden=false;document.body.classList.add('modal-open');};
function closeContainerDetail(){state.openContainerId=null;state.containerId='';$('#containerDetailBackdrop').hidden=true;document.body.classList.remove('modal-open');}
window.openEditItem=id=>{const item=state.items.find(x=>x.id===id); if(item)openItemModal(item);};
window.deleteItem=async id=>{const item=state.items.find(x=>x.id===id);if(!item)return;if(!confirm(`确定删除“${item.name}”吗？`))return;try{await api(`/api/items/${id}`,{method:'DELETE'});toast('已删除');await loadAll();}catch(e){toast(e.message);}};

$('#itemForm').addEventListener('submit',async e=>{e.preventDefault();const id=$('#itemId').value;const quantityRaw=$('#itemQuantity').value;const data={name:$('#itemName').value.trim(),container_id:Number($('#itemContainer').value),quantity:quantityRaw===''?null:Number(quantityRaw),quantity_text:$('#itemQuantityText').value.trim(),condition:$('#itemCondition').value.trim()||'正常',tags:$('#itemTags').value.trim(),notes:$('#itemNotes').value.trim()};try{await api(id?`/api/items/${id}`:'/api/items',{method:id?'PATCH':'POST',body:JSON.stringify(data)});$('#modalBackdrop').hidden=true;toast(id?'已保存修改':'已添加物品');await loadAll();}catch(e){toast(e.message);}});
$('#containerForm').addEventListener('submit',async e=>{e.preventDefault();try{await api('/api/containers',{method:'POST',body:JSON.stringify({name:$('#containerName').value.trim(),notes:$('#containerNotes').value.trim()})});$('#containerModalBackdrop').hidden=true;$('#containerForm').reset();toast('箱子已创建');await loadAll();}catch(e){toast(e.message);}});
$$('[data-close-modal]').forEach(b=>b.addEventListener('click',()=>$('#modalBackdrop').hidden=true));$$('[data-close-container-modal]').forEach(b=>b.addEventListener('click',()=>$('#containerModalBackdrop').hidden=true));
$('#closeContainerDetail').addEventListener('click',closeContainerDetail);
$('#containerDetailBackdrop').addEventListener('click',e=>{if(e.target===$('#containerDetailBackdrop'))closeContainerDetail();});
$('#addItemToContainerBtn').addEventListener('click',()=>openItemModal());
$('#quickAddBtn').addEventListener('click',()=>openItemModal());$('#addContainerBtn').addEventListener('click',()=>{$('#containerModalBackdrop').hidden=false;setTimeout(()=>$('#containerName').focus(),20);});

$('#searchInput').addEventListener('input',e=>{state.search=e.target.value;clearTimeout(filterItems.t);renderSearchState();});
$('#clearFilterBtn').addEventListener('click',()=>{state.search='';$('#searchInput').value='';renderSearchState();});
$('#backToBoxesBtn').addEventListener('click',()=>{state.search='';$('#searchInput').value='';renderSearchState();});
$$('.nav-btn').forEach(b=>b.addEventListener('click',()=>switchView(b.dataset.view)));$$('[data-open-ai]').forEach(b=>b.addEventListener('click',()=>switchView('assistant')));
document.addEventListener('keydown',e=>{if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='k'){e.preventDefault();switchView('inventory');$('#searchInput').focus();}if(e.key==='Escape'){if(!$('#modalBackdrop').hidden)$('#modalBackdrop').hidden=true;else if(!$('#containerModalBackdrop').hidden)$('#containerModalBackdrop').hidden=true;else if(!$('#containerDetailBackdrop').hidden)closeContainerDetail();}});

function applySettings(s){$('#baseUrlInput').value=s.base_url||'';$('#modelInput').value=s.model||'auto';$('#keyState').textContent=s.has_api_key?'API Key 已保存在本机服务器':'尚未保存 API Key';$('#keyState').style.color=s.has_api_key?'#16a34a':'#98a2b3';$('#aiStatusText').textContent=s.has_api_key?'AI 接口已配置':'本地检索模式';$('#aiStatusDot').style.background=s.has_api_key?'#22c55e':'#f59e0b';}
function applyMqStatus(s){const ok=s.enabled&&s.connected;$('#mqStatusText').textContent=!s.enabled?'已禁用':ok?'RabbitMQ 已连接':'RabbitMQ 未连接（主功能不受影响）';$('#mqStatusDot').style.background=!s.enabled?'#98a2b3':ok?'#22c55e':'#f59e0b';$('#mqUrl').textContent=s.url||'—';$('#mqExchange').textContent=s.exchange||'—';$('#mqQueue').textContent=s.queue||'—';$('#mqError').hidden=!s.last_error;$('#mqError').textContent=s.last_error?`最近错误：${s.last_error}`:'';}
async function refreshMqStatus(){try{applyMqStatus(await api('/api/mq/status'));}catch(e){toast(e.message);}}
$('#refreshMqBtn').addEventListener('click',refreshMqStatus);
$('#settingsForm').addEventListener('submit',async e=>{e.preventDefault();const data={base_url:$('#baseUrlInput').value.trim(),model:$('#modelInput').value.trim()||'auto'};if($('#apiKeyInput').value.trim())data.api_key=$('#apiKeyInput').value.trim();try{const s=await api('/api/settings',{method:'POST',body:JSON.stringify(data)});applySettings(s);$('#apiKeyInput').value='';$('#settingsSaved').textContent='已保存';toast('AI 设置已保存');setTimeout(()=>$('#settingsSaved').textContent='',1800);}catch(e){toast(e.message);}});
$('#toggleKeyBtn').addEventListener('click',()=>{const input=$('#apiKeyInput');input.type=input.type==='password'?'text':'password';$('#toggleKeyBtn').textContent=input.type==='password'?'显示':'隐藏';});

function addMessage(role,text,action=null){const wrap=document.createElement('div');wrap.className=`message ${role}`;const bubble=document.createElement('div');bubble.className='bubble';bubble.textContent=text;wrap.appendChild(bubble);if(action){const card=document.createElement('div');card.className='action-card';const p=document.createElement('p');p.textContent=`待确认操作：${describeAction(action)}`;const btn=document.createElement('button');btn.textContent='确认执行';btn.addEventListener('click',async()=>{btn.disabled=true;btn.textContent='执行中…';try{const result=await api('/api/ai/execute',{method:'POST',body:JSON.stringify({action})});btn.textContent='✓ 已执行';p.textContent=result.message;await loadAll();}catch(e){btn.disabled=false;btn.textContent='重试';p.textContent=e.message;}});card.append(p,btn);bubble.appendChild(card);}$('#chatMessages').appendChild(wrap);$('#chatMessages').scrollTop=$('#chatMessages').scrollHeight;}
function describeAction(a){if(a.type==='add_item')return `新增“${a.data?.name||'物品'}”`;if(a.type==='update_item')return `修改物品 #${a.item_id}`;if(a.type==='move_item')return `移动物品 #${a.item_id} 到箱子 #${a.container_id}`;if(a.type==='delete_item')return `删除物品 #${a.item_id}`;if(a.type==='add_container')return `新增箱子“${a.data?.name||''}”`;return a.type||'未知操作';}
async function sendChat(text){text=text.trim();if(!text)return;addMessage('user',text);$('#chatInput').value='';$('#chatSuggestions').style.display='none';const thinking=document.createElement('div');thinking.className='message assistant';thinking.innerHTML='<div class="bubble">正在查数据库…</div>';$('#chatMessages').appendChild(thinking);$('#chatMessages').scrollTop=$('#chatMessages').scrollHeight;try{const r=await api('/api/chat',{method:'POST',body:JSON.stringify({message:text})});thinking.remove();addMessage('assistant',r.reply||'已处理。',r.action||null);}catch(e){thinking.remove();addMessage('assistant',`请求失败：${e.message}`);}}
$('#chatForm').addEventListener('submit',e=>{e.preventDefault();sendChat($('#chatInput').value);});$('#chatInput').addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();$('#chatForm').requestSubmit();}});$$('#chatSuggestions button').forEach(b=>b.addEventListener('click',()=>sendChat(b.textContent)));

window.addEventListener('beforeinstallprompt',e=>{e.preventDefault();state.deferredPrompt=e;$('#installBtn').hidden=false;});$('#installBtn').addEventListener('click',async()=>{if(!state.deferredPrompt)return;state.deferredPrompt.prompt();await state.deferredPrompt.userChoice;state.deferredPrompt=null;$('#installBtn').hidden=true;});
if('serviceWorker' in navigator)window.addEventListener('load',()=>navigator.serviceWorker.register('/sw.js').catch(()=>{}));
loadAll().catch(e=>toast(e.message));
