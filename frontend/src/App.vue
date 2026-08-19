<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { api } from './api'
import type { AiAction, AiSettings, Attachment, ChatResponse, Container, Dashboard, HistoryEntry, Item, LifecycleEntry, Member, MqStatus, Summary } from './types'

type ViewName = 'inventory' | 'containers' | 'lifecycle' | 'assistant' | 'settings'
type ChatMessage = { id: number; role: 'user'|'assistant'; text: string; action?: AiAction|null; executed?: boolean; error?: string }
type QuickRow = { name: string; quantity: string; quantity_text: string }

const currentView = ref<ViewName>('inventory')
const summary = reactive<Summary>({ containers: 0, items: 0, quantity: 0, special: 0 })
const dashboard = ref<Dashboard | null>(null)
const members = ref<Member[]>([])
const containers = ref<Container[]>([])
const items = ref<Item[]>([])
const lifecycles = ref<LifecycleEntry[]>([])
const selectedOwnerId = ref(1)
const search = ref('')
const searchResults = ref<Item[]>([])
const searching = computed(() => Boolean(search.value.trim()))
const openContainerId = ref<number | null>(null)

const filteredContainers = computed(() => containers.value.filter(c => c.owner_id === selectedOwnerId.value))
const filteredItems = computed(() => items.value.filter(i => i.owner_id === selectedOwnerId.value))
const openContainer = computed(() => containers.value.find(c => c.id === openContainerId.value) ?? null)
const openContainerItems = computed(() => openContainerId.value == null ? [] : items.value.filter(i => i.container_id === openContainerId.value))
const visibleLifecycles = computed(() => lifecycles.value.filter(l => l.owner_id === selectedOwnerId.value))
const selectedMember = computed(() => members.value.find(m => m.id === selectedOwnerId.value))

const itemModalOpen = ref(false)
const containerModalOpen = ref(false)
const containerEditingId = ref<number | null>(null)
const quickModalOpen = ref(false)
const batchModalOpen = ref(false)
const historyModalOpen = ref(false)
const itemEditingId = ref<number | null>(null)
const itemAttachments = ref<Attachment[]>([])
const pendingFiles = ref<File[]>([])
const itemForm = reactive({
  name: '', container_id: 0, quantity: '1', quantity_text: '', condition: '正常', notes: '', tags: '',
  lifecycle_enabled: false, lifecycle_type: 'EXPIRY', start_date: '', expiry_date: '', remind_days: '7', lifecycle_notes: '',
})
const containerForm = reactive({ name: '', notes: '', owner_id: 1 })

const quickContainerId = ref(0)
const quickRows = ref<QuickRow[]>([{ name: '', quantity: '1', quantity_text: '' }, { name: '', quantity: '1', quantity_text: '' }])
const batchSelected = ref<number[]>([])
const batchTargetContainerId = ref(0)
const batchCondition = ref('')
const batchTags = ref('')

const containerHistory = ref<HistoryEntry[]>([])
const historyContainerName = ref('')
const lifecycleStatus = ref('ALL')

const aiSettings = reactive<AiSettings>({ base_url: '', model: 'auto', has_api_key: false })
const apiKeyInput = ref('')
const showApiKey = ref(false)
const settingsSaved = ref(false)
const mqStatus = reactive<MqStatus>({ enabled: false, connected: false, url: '—', exchange: '—', queue: '—', last_error: '', client: '' })
const aiOwnerScope = ref<number[]>([1])
const aiLifecycleOnly = ref(false)
const chatInput = ref('')
const chatBusy = ref(false)
let messageId = 1
const chatMessages = ref<ChatMessage[]>([
  { id: messageId++, role: 'assistant', text: '你好。我会先按你选择的“我 / 爸 / 妈 / 生命周期”范围在本地数据库检索，再回答或生成待确认操作。图片和附件暂时不会发送给大模型。' },
])
const chatSuggestions = ['HDMI 相关的东西在哪里？', '哪些东西快到期了？', '有哪些物品状态不正常？']

const toastText = ref('')
let toastTimer: number | undefined
let searchTimer: number | undefined
const deferredPrompt = ref<any>(null)
const pageTitle = computed(() => ({ inventory: '家', containers: '箱子管理', lifecycle: '生命周期', assistant: 'AI 助手', settings: '设置' }[currentView.value]))

function toast(text: string) { toastText.value = text; window.clearTimeout(toastTimer); toastTimer = window.setTimeout(() => { toastText.value = '' }, 2400) }
function firstChar(name: string) { return (name || '物').trim().charAt(0).toUpperCase() }
function quantityText(item: Item) { return item.quantity_text || (item.quantity == null ? '未记录' : `${item.quantity} 个`) }
function ownerName(id: number) { return members.value.find(m => m.id === id)?.name || `成员 #${id}` }
function formatTime(value?: string | null) { if (!value) return ''; return value.replace('T', ' ').replace(/\.\d+$/, '').slice(0, 16) }
function lifecycleLabel(type?: string | null) { return ({ EXPIRY:'有效期', WARRANTY:'保修', REPLACE:'建议更换', CHECK:'定期检查' } as Record<string,string>)[type || ''] || '生命周期' }
function lifecycleStatusLabel(status: string) { return ({ EXPIRED:'已到期', DUE:'即将到期', ACTIVE:'正常', NO_DATE:'未设日期' } as Record<string,string>)[status] || status }

async function loadAll() {
  const [ms, ds, cs, xs, ls, settings, mq] = await Promise.all([
    api<Member[]>('/api/members'), api<Dashboard>('/api/dashboard'), api<Container[]>('/api/containers'), api<Item[]>('/api/items'),
    api<LifecycleEntry[]>('/api/lifecycle'), api<AiSettings>('/api/settings'), api<MqStatus>('/api/mq/status'),
  ])
  members.value = ms; dashboard.value = ds; Object.assign(summary, ds.summary); containers.value = cs; items.value = xs; lifecycles.value = ls
  Object.assign(aiSettings, settings); Object.assign(mqStatus, mq)
  if (!members.value.some(m => m.id === selectedOwnerId.value)) selectedOwnerId.value = members.value[0]?.id ?? 1
  if (!itemForm.container_id) itemForm.container_id = filteredContainers.value[0]?.id ?? cs[0]?.id ?? 0
  if (!quickContainerId.value) quickContainerId.value = filteredContainers.value[0]?.id ?? 0
  if (searching.value) await runSearch()
}

async function refreshData() {
  const [ds, cs, xs, ls] = await Promise.all([api<Dashboard>('/api/dashboard'), api<Container[]>('/api/containers'), api<Item[]>('/api/items'), api<LifecycleEntry[]>('/api/lifecycle')])
  dashboard.value = ds; Object.assign(summary, ds.summary); containers.value = cs; items.value = xs; lifecycles.value = ls
  if (searching.value) await runSearch()
}

function setOwner(id: number) {
  selectedOwnerId.value = id; search.value = ''; searchResults.value = []
  itemForm.container_id = filteredContainers.value[0]?.id ?? 0
  quickContainerId.value = filteredContainers.value[0]?.id ?? 0
}
function switchView(view: ViewName) { currentView.value = view }
function openContainerDetail(id: number) { openContainerId.value = id; document.body.classList.add('modal-open') }
function closeContainerDetail() { openContainerId.value = null; document.body.classList.remove('modal-open') }

function resetItemForm(containerId?: number) {
  itemEditingId.value = null; itemAttachments.value = []; pendingFiles.value = []
  itemForm.name=''; itemForm.container_id=containerId ?? openContainerId.value ?? filteredContainers.value[0]?.id ?? 0; itemForm.quantity='1'; itemForm.quantity_text=''; itemForm.condition='正常'; itemForm.notes=''; itemForm.tags=''
  itemForm.lifecycle_enabled=false; itemForm.lifecycle_type='EXPIRY'; itemForm.start_date=''; itemForm.expiry_date=''; itemForm.remind_days='7'; itemForm.lifecycle_notes=''
}
function openAddItem(containerId?: number) { resetItemForm(containerId); if (!itemForm.container_id) { toast('请先给当前成员创建一个箱子'); return } itemModalOpen.value = true }
async function openEditItem(item: Item) {
  itemEditingId.value=item.id; itemForm.name=item.name; itemForm.container_id=item.container_id; itemForm.quantity=item.quantity==null?'':String(item.quantity); itemForm.quantity_text=item.quantity_text||''; itemForm.condition=item.condition||'正常'; itemForm.notes=item.notes||''; itemForm.tags=item.tags||''
  itemForm.lifecycle_enabled=Boolean(item.lifecycle_type || item.expiry_date); itemForm.lifecycle_type=item.lifecycle_type||'EXPIRY'; itemForm.start_date=item.lifecycle_start_date||''; itemForm.expiry_date=item.expiry_date||''; itemForm.remind_days=String(item.remind_days ?? 7); itemForm.lifecycle_notes=item.lifecycle_notes||''
  pendingFiles.value=[]; itemAttachments.value = await api<Attachment[]>(`/api/items/${item.id}/attachments`); itemModalOpen.value=true
}
function onFiles(event: Event) { pendingFiles.value = Array.from((event.target as HTMLInputElement).files || []) }
async function uploadPending(itemId: number) {
  for (const file of pendingFiles.value) { const form=new FormData(); form.append('file', file); await api(`/api/items/${itemId}/attachments`, { method:'POST', body:form }) }
}
async function saveItem() {
  const payload={ name:itemForm.name.trim(), container_id:Number(itemForm.container_id), quantity:itemForm.quantity===''?null:Number(itemForm.quantity), quantity_text:itemForm.quantity_text.trim(), condition:itemForm.condition.trim()||'正常', notes:itemForm.notes.trim(), tags:itemForm.tags.trim() }
  try {
    const saved=await api<Item>(itemEditingId.value?`/api/items/${itemEditingId.value}`:'/api/items',{method:itemEditingId.value?'PATCH':'POST',body:JSON.stringify(payload)})
    if(itemForm.lifecycle_enabled){ await api(`/api/items/${saved.id}/lifecycle`,{method:'PUT',body:JSON.stringify({lifecycle_type:itemForm.lifecycle_type,start_date:itemForm.start_date||null,expiry_date:itemForm.expiry_date||null,remind_days:Number(itemForm.remind_days||7),notes:itemForm.lifecycle_notes.trim()})}) }
    else if(itemEditingId.value && (saved.lifecycle_type || saved.expiry_date)){ await api(`/api/items/${saved.id}/lifecycle`,{method:'DELETE'}) }
    if(pendingFiles.value.length) await uploadPending(saved.id)
    itemModalOpen.value=false; toast(itemEditingId.value?'已保存修改':'已添加物品'); await refreshData()
  } catch(error){ toast((error as Error).message) }
}
async function deleteItem(item: Item) { if(!window.confirm(`确定删除“${item.name}”吗？操作会写入历史记录。`))return; try{await api(`/api/items/${item.id}`,{method:'DELETE'});toast('已删除，可从操作记录尝试撤销');await refreshData()}catch(error){toast((error as Error).message)} }
async function deleteAttachment(a: Attachment) { if(!window.confirm(`删除附件“${a.filename}”？`))return; try{await api(`/api/attachments/${a.id}`,{method:'DELETE'});itemAttachments.value=itemAttachments.value.filter(x=>x.id!==a.id);toast('附件已删除')}catch(error){toast((error as Error).message)} }

function openAddContainer(){containerEditingId.value=null;containerForm.name='';containerForm.notes='';containerForm.owner_id=selectedOwnerId.value;containerModalOpen.value=true}
function openEditContainer(container:Container){containerEditingId.value=container.id;containerForm.name=container.name;containerForm.notes=container.notes||'';containerForm.owner_id=container.owner_id;containerModalOpen.value=true}
async function saveContainer(){try{await api(containerEditingId.value?`/api/containers/${containerEditingId.value}`:'/api/containers',{method:containerEditingId.value?'PATCH':'POST',body:JSON.stringify(containerForm)});containerModalOpen.value=false;toast(containerEditingId.value?'箱子已更新':'箱子已创建');await refreshData()}catch(error){toast((error as Error).message)}}
async function deleteContainer(){if(!containerEditingId.value)return;const c=containers.value.find(x=>x.id===containerEditingId.value);if(!c||!window.confirm(`删除空箱子“${c.name}”？`))return;try{await api(`/api/containers/${c.id}`,{method:'DELETE'});containerModalOpen.value=false;if(openContainerId.value===c.id)closeContainerDetail();toast('箱子已删除，可从历史记录尝试撤销');await refreshData()}catch(error){toast((error as Error).message)}}

async function runSearch(){const q=search.value.trim();if(!q){searchResults.value=[];return}try{searchResults.value=await api<Item[]>(`/api/items?q=${encodeURIComponent(q)}&owner_id=${selectedOwnerId.value}`)}catch(error){toast((error as Error).message)}}
function onSearchInput(){window.clearTimeout(searchTimer);searchTimer=window.setTimeout(runSearch,180)}
function clearSearch(){search.value='';searchResults.value=[]}

async function openHistory(container: Container){historyContainerName.value=container.name;containerHistory.value=await api<HistoryEntry[]>(`/api/history?container_id=${container.id}&limit=100`);historyModalOpen.value=true}
async function undoHistory(entry: HistoryEntry){if(!entry.can_undo||entry.undone_at)return;if(!window.confirm(`撤销：${entry.description}？`))return;try{await api(`/api/history/${entry.id}/undo`,{method:'POST'});toast('已撤销');await refreshData();if(openContainer.value)await openHistory(openContainer.value)}catch(error){toast((error as Error).message)}}

function addQuickRow(){quickRows.value.push({name:'',quantity:'1',quantity_text:''})}
function removeQuickRow(index:number){if(quickRows.value.length>1)quickRows.value.splice(index,1)}
function openQuickEntry(){quickContainerId.value=filteredContainers.value[0]?.id??0;quickRows.value=[{name:'',quantity:'1',quantity_text:''},{name:'',quantity:'1',quantity_text:''}];if(!quickContainerId.value){toast('请先创建箱子');return}quickModalOpen.value=true}
async function submitQuickEntry(){const rows=quickRows.value.filter(r=>r.name.trim()).map(r=>({name:r.name.trim(),quantity:r.quantity===''?null:Number(r.quantity),quantity_text:r.quantity_text.trim(),condition:'正常',notes:'',tags:''}));if(!rows.length){toast('至少输入一个物品');return}try{await api('/api/items/quick-entry',{method:'POST',body:JSON.stringify({container_id:quickContainerId.value,items:rows})});quickModalOpen.value=false;toast(`已快速录入 ${rows.length} 类物品`);await refreshData()}catch(error){toast((error as Error).message)}}

function openBatch(){batchSelected.value=[];batchTargetContainerId.value=0;batchCondition.value='';batchTags.value='';batchModalOpen.value=true}
function toggleBatch(id:number){batchSelected.value=batchSelected.value.includes(id)?batchSelected.value.filter(x=>x!==id):[...batchSelected.value,id]}
async function submitBatch(){if(!batchSelected.value.length){toast('请先选择物品');return}const data:Record<string,unknown>={};if(batchTargetContainerId.value)data.container_id=batchTargetContainerId.value;if(batchCondition.value.trim())data.condition=batchCondition.value.trim();if(batchTags.value.trim())data.tags=batchTags.value.trim();if(!Object.keys(data).length){toast('请选择批量修改内容');return}try{await api('/api/items/batch',{method:'POST',body:JSON.stringify({ids:batchSelected.value,data})});batchModalOpen.value=false;toast(`已批量修改 ${batchSelected.value.length} 类物品`);await refreshData()}catch(error){toast((error as Error).message)}}

async function loadLifecycle(){lifecycles.value=await api<LifecycleEntry[]>(`/api/lifecycle?status=${lifecycleStatus.value}`)}
function setLifecycleStatus(status:string){lifecycleStatus.value=status;loadLifecycle().catch(e=>toast(e.message))}
function editLifecycle(entry: LifecycleEntry){const item=items.value.find(i=>i.id===entry.item_id);if(item)openEditItem(item)}

function toggleAiOwner(id:number){aiOwnerScope.value=aiOwnerScope.value.includes(id)?aiOwnerScope.value.filter(x=>x!==id):[...aiOwnerScope.value,id];if(!aiOwnerScope.value.length)aiOwnerScope.value=[id]}
function describeAction(action:AiAction){if(action.type==='add_item')return `新增“${String(action.data?.name||'物品')}”`;if(action.type==='update_item')return `修改物品 #${action.item_id}`;if(action.type==='move_item')return `移动物品 #${action.item_id} 到箱子 #${action.container_id}`;if(action.type==='delete_item')return `删除物品 #${action.item_id}`;if(action.type==='add_container')return `新增箱子“${String(action.data?.name||'')}”`;if(action.type==='set_lifecycle')return `设置物品 #${action.item_id} 生命周期`;return action.type||'未知操作'}
async function executeAction(message:ChatMessage){if(!message.action||message.executed)return;message.error='';try{const result=await api<{message:string}>('/api/ai/execute',{method:'POST',body:JSON.stringify({action:message.action,owner_ids:aiOwnerScope.value})});message.text=`${message.text}\n\n${result.message}`;message.executed=true;await refreshData()}catch(error){message.error=(error as Error).message}}
async function sendChat(text=chatInput.value){const value=text.trim();if(!value||chatBusy.value)return;chatMessages.value.push({id:messageId++,role:'user',text:value});chatInput.value='';chatBusy.value=true;await nextTick();scrollChat();try{const response=await api<ChatResponse>('/api/chat',{method:'POST',body:JSON.stringify({message:value,owner_ids:aiOwnerScope.value,lifecycle_only:aiLifecycleOnly.value})});chatMessages.value.push({id:messageId++,role:'assistant',text:response.reply||'已处理。',action:response.action})}catch(error){chatMessages.value.push({id:messageId++,role:'assistant',text:`请求失败：${(error as Error).message}`})}finally{chatBusy.value=false;await nextTick();scrollChat()}}
function scrollChat(){const box=document.querySelector<HTMLElement>('.chat-messages');if(box)box.scrollTop=box.scrollHeight}

async function saveSettings(){const payload:Record<string,string>={base_url:aiSettings.base_url.trim(),model:aiSettings.model.trim()||'auto'};if(apiKeyInput.value.trim())payload.api_key=apiKeyInput.value.trim();try{Object.assign(aiSettings,await api<AiSettings>('/api/settings',{method:'POST',body:JSON.stringify(payload)}));apiKeyInput.value='';settingsSaved.value=true;toast('AI 设置已保存');window.setTimeout(()=>{settingsSaved.value=false},1800)}catch(error){toast((error as Error).message)}}
async function refreshMqStatus(){try{Object.assign(mqStatus,await api<MqStatus>('/api/mq/status'))}catch(error){toast((error as Error).message)}}
async function installApp(){if(!deferredPrompt.value)return;deferredPrompt.value.prompt();await deferredPrompt.value.userChoice;deferredPrompt.value=null}

function onKeydown(event:KeyboardEvent){if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==='k'){event.preventDefault();currentView.value='inventory';nextTick(()=>document.querySelector<HTMLInputElement>('.search-box input')?.focus())}if(event.key==='Escape'){itemModalOpen.value=false;containerModalOpen.value=false;quickModalOpen.value=false;batchModalOpen.value=false;historyModalOpen.value=false;if(openContainerId.value!=null)closeContainerDetail()}}
function onBeforeInstallPrompt(event:Event){event.preventDefault();deferredPrompt.value=event}
onMounted(async()=>{window.addEventListener('keydown',onKeydown);window.addEventListener('beforeinstallprompt',onBeforeInstallPrompt);if(import.meta.env.PROD&&'serviceWorker'in navigator)navigator.serviceWorker.register('/sw.js').catch(()=>undefined);try{await loadAll()}catch(error){toast((error as Error).message)}})
onBeforeUnmount(()=>{window.removeEventListener('keydown',onKeydown);window.removeEventListener('beforeinstallprompt',onBeforeInstallPrompt);window.clearTimeout(toastTimer);window.clearTimeout(searchTimer)})
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand"><div class="brand-mark">物</div><div><strong>物栈</strong><span>ItemNest v0.8</span></div></div>
      <nav class="nav-list">
        <button class="nav-btn" :class="{active:currentView==='inventory'}" @click="switchView('inventory')"><span>⌂</span><em>家</em></button>
        <button class="nav-btn" :class="{active:currentView==='containers'}" @click="switchView('containers')"><span>▦</span><em>箱子</em></button>
        <button class="nav-btn" :class="{active:currentView==='lifecycle'}" @click="switchView('lifecycle')"><span>◷</span><em>生命周期</em></button>
        <button class="nav-btn" :class="{active:currentView==='assistant'}" @click="switchView('assistant')"><span>✦</span><em>AI 助手</em></button>
        <button class="nav-btn" :class="{active:currentView==='settings'}" @click="switchView('settings')"><span>⚙</span><em>设置</em></button>
      </nav>
      <div class="sidebar-foot"><div class="status-dot"></div><span>SQLite + FTS5 本地数据</span></div>
    </aside>

    <main class="main">
      <header class="topbar">
        <div><p class="eyebrow">HOUSEHOLD INVENTORY</p><h1>{{ pageTitle }}</h1></div>
        <div class="top-actions"><button v-if="deferredPrompt" class="ghost-btn" @click="installApp">安装 App</button><button v-if="currentView==='inventory'||currentView==='containers'" class="ghost-btn" @click="openQuickEntry">快速录入</button><button v-if="currentView==='inventory'||currentView==='containers'" class="primary-btn" @click="openAddItem()">＋ 添加物品</button></div>
      </header>

      <div v-if="currentView!=='settings' && currentView!=='assistant'" class="member-tabs">
        <span>家</span><button v-for="m in members" :key="m.id" :class="{active:selectedOwnerId===m.id}" @click="setOwner(m.id)">{{ m.name }}</button>
      </div>

      <section class="view" :class="{active:currentView==='inventory'}">
        <div class="hero-card home-hero"><div class="hero-copy"><span class="pill">{{ selectedMember?.name }}的物品</span><h2>家里的东西，按人分清楚。</h2><p>当前版本只区分“我 / 爸 / 妈”，暂不加入具体房间层级。现有数据默认全部归到“我”。</p></div><button class="hero-ai" @click="switchView('assistant')">问 AI <span>→</span></button></div>

        <div class="stats-grid">
          <article class="stat"><span>家庭总物品</span><strong>{{ dashboard?.summary.items ?? 0 }}</strong><small>类</small></article>
          <article class="stat"><span>{{ selectedMember?.name }}的箱子</span><strong>{{ filteredContainers.length }}</strong><small>个</small></article>
          <article class="stat warning"><span>30 天内到期</span><strong>{{ dashboard?.lifecycle.due30 ?? 0 }}</strong><small>项</small></article>
          <article class="stat danger"><span>已到期</span><strong>{{ dashboard?.lifecycle.expired ?? 0 }}</strong><small>项</small></article>
        </div>

        <div class="member-summary-grid">
          <article v-for="m in dashboard?.members || []" :key="m.id" class="member-summary" @click="setOwner(m.id)"><b>{{ m.name }}</b><strong>{{ m.items }}</strong><span>类物品 · {{ m.containers }} 个箱子</span></article>
        </div>

        <div class="toolbar storage-toolbar"><label class="search-box"><span>⌕</span><input v-model="search" :placeholder="`搜索${selectedMember?.name || ''}的物品、标签、箱子…`" @input="onSearchInput" /><kbd>Ctrl K</kbd></label><button class="outline-btn" @click="clearSearch">清除</button><button class="outline-btn" @click="openBatch">批量操作</button></div>

        <div v-if="searching">
          <div class="section-head"><div><h3>FTS5 搜索结果</h3><p>{{ searchResults.length }} 条 · 已按“{{ selectedMember?.name }}”范围过滤</p></div></div>
          <div class="items-grid"><article v-for="item in searchResults" :key="item.id" class="item-card"><div class="item-top"><div class="item-icon">{{ firstChar(item.name) }}</div><div class="item-actions"><button class="icon-btn" @click="openEditItem(item)">✎</button><button class="icon-btn danger" @click="deleteItem(item)">×</button></div></div><h4>{{ item.name }}</h4><div class="location">{{ item.owner_name }} · {{ item.container_name }}</div><p v-if="item.notes" class="item-notes">{{ item.notes }}</p><div class="item-meta"><span class="chip">{{ quantityText(item) }}</span><span v-if="item.expiry_date" class="chip life">{{ lifecycleLabel(item.lifecycle_type) }} {{ item.expiry_date }}</span><span v-if="item.attachment_count" class="chip">附件 {{ item.attachment_count }}</span></div></article></div>
          <div v-if="!searchResults.length" class="empty"><div class="empty-icon">⌕</div><h3>没有匹配</h3><p>可以换关键词，或让 AI 在选定 Scope 中检索。</p></div>
        </div>
        <div v-else>
          <div class="section-head"><div><h3>{{ selectedMember?.name }}的箱子</h3><p>点击箱子查看里面的物品；箱子详情里可以打开操作记录。</p></div><button class="outline-btn" @click="openAddContainer">＋ 新建箱子</button></div>
          <div class="containers-grid"><article v-for="c in filteredContainers" :key="c.id" class="container-card" @click="openContainerDetail(c.id)"><div class="container-card-top"><div class="box-mark">▦</div><span class="owner-badge">{{ c.owner_name }}</span></div><h4>{{ c.name }}</h4><p>{{ c.notes || '收纳位置' }}</p><div class="container-bottom"><span>{{ c.item_count }} 类 · 约 {{ c.quantity_sum }} 件/组</span><span>打开 →</span></div></article></div>
          <div v-if="!filteredContainers.length" class="empty"><h3>{{ selectedMember?.name }}还没有箱子</h3><p>先创建箱子，再添加物品。</p><button class="primary-btn" @click="openAddContainer">创建第一个箱子</button></div>
        </div>

        <div class="dashboard-section"><div class="section-head"><div><h3>最近操作</h3><p>手动、AI、批量操作都会进入历史。</p></div></div><div class="history-list compact"><article v-for="h in dashboard?.recent_history || []" :key="h.id"><div><b>{{ h.description }}</b><span>{{ h.source }} · {{ formatTime(h.created_at) }}</span></div><div class="inline-actions"><span v-if="h.undone_at" class="undo-state">已撤销</span><button v-else-if="h.can_undo" class="text-btn" @click="undoHistory(h)">撤销</button></div></article><p v-if="!dashboard?.recent_history?.length" class="muted">暂无记录。</p></div></div>
      </section>

      <section class="view" :class="{active:currentView==='containers'}">
        <div class="section-head row-mobile"><div><h3>{{ selectedMember?.name }}的箱子管理</h3><p>当前不加入房间层级，箱子直接归属家庭成员。</p></div><div class="inline-actions"><button class="outline-btn" @click="openBatch">批量操作</button><button class="primary-btn" @click="openAddContainer">＋ 新建箱子</button></div></div>
        <div class="containers-grid"><article v-for="c in filteredContainers" :key="c.id" class="container-card" @click="openContainerDetail(c.id)"><div class="container-card-top"><div class="box-mark">▦</div><span class="owner-badge">{{ c.owner_name }}</span></div><h4>{{ c.name }}</h4><p>{{ c.notes || '收纳位置' }}</p><div class="container-bottom"><span>{{ c.item_count }} 类 · {{ c.quantity_sum }} 件/组</span><span>打开 →</span></div></article></div>
      </section>

      <section class="view" :class="{active:currentView==='lifecycle'}">
        <div class="lifecycle-summary"><article><span>全部</span><strong>{{ dashboard?.lifecycle.total ?? 0 }}</strong></article><article><span>7 天内</span><strong>{{ dashboard?.lifecycle.due7 ?? 0 }}</strong></article><article><span>30 天内</span><strong>{{ dashboard?.lifecycle.due30 ?? 0 }}</strong></article><article class="danger"><span>已到期</span><strong>{{ dashboard?.lifecycle.expired ?? 0 }}</strong></article></div>
        <div class="filter-tabs"><button v-for="s in [['ALL','全部'],['DUE','即将到期'],['EXPIRED','已到期'],['ACTIVE','正常']]" :key="s[0]" :class="{active:lifecycleStatus===s[0]}" @click="setLifecycleStatus(s[0])">{{ s[1] }}</button></div>
        <div class="section-head"><div><h3>生命周期物品</h3><p>统一管理食品/调料有效期、电池更换、保修和定期检查。</p></div></div>
        <div class="lifecycle-list"><article v-for="l in visibleLifecycles" :key="l.id" :class="['lifecycle-row',l.lifecycle_status.toLowerCase()]" @click="editLifecycle(l)"><div class="life-date"><strong>{{ l.days_left == null ? '—' : l.days_left }}</strong><span>{{ l.days_left == null ? '无日期' : l.days_left < 0 ? '天前' : '天' }}</span></div><div class="life-main"><h4>{{ l.item_name }}</h4><p>{{ l.owner_name }} · {{ l.container_name }} · {{ lifecycleLabel(l.lifecycle_type) }}</p><small>{{ l.expiry_date || '未设置日期' }}<template v-if="l.notes"> · {{ l.notes }}</template></small></div><span class="life-status">{{ lifecycleStatusLabel(l.lifecycle_status) }}</span></article><div v-if="!visibleLifecycles.length" class="empty"><h3>没有生命周期记录</h3><p>编辑任意物品，打开“生命周期”开关即可添加。</p></div></div>
      </section>

      <section class="view assistant-layout" :class="{active:currentView==='assistant'}">
        <div class="chat-panel">
          <div class="chat-head"><div class="ai-avatar">✦</div><div><h3>物栈 AI</h3><p>{{ aiSettings.has_api_key ? '大模型 + 本地 FTS5' : '本地 FTS5 模式' }}</p></div></div>
          <div class="ai-scope"><b>查询 / 操作范围</b><div class="scope-buttons"><button v-for="m in members" :key="m.id" :class="{active:aiOwnerScope.includes(m.id)}" @click="toggleAiOwner(m.id)">{{ m.name }}</button><button :class="{active:aiLifecycleOnly}" @click="aiLifecycleOnly=!aiLifecycleOnly">生命周期物品</button></div><small>范围先在数据库层过滤，再把最多 24 条相关文本候选交给大模型。图片和附件不会进入 AI Context。</small></div>
          <div class="chat-messages"><div v-for="message in chatMessages" :key="message.id" class="message" :class="message.role"><div class="bubble">{{ message.text }}<div v-if="message.action&&!message.executed" class="action-card"><p>待确认：{{ describeAction(message.action) }}</p><p v-if="message.error" class="action-error">{{ message.error }}</p><button @click="executeAction(message)">确认执行</button></div></div></div><div v-if="chatBusy" class="message assistant"><div class="bubble">正在本地检索并组织回答…</div></div></div>
          <div class="suggestions"><button v-for="s in chatSuggestions" :key="s" @click="sendChat(s)">{{ s }}</button></div>
          <form class="chat-input-wrap" @submit.prevent="sendChat()"><textarea v-model="chatInput" rows="1" placeholder="例如：爸的 CR2032 电池什么时候该换？" @keydown.enter.exact.prevent="sendChat()"></textarea><button type="submit" class="send-btn">↑</button></form>
          <p class="chat-hint">语音输入暂未实现。后续方案：本地 Whisper / faster-whisper → 文本 → 当前 Agent，不改变数据库工具层。</p>
        </div>
        <aside class="assistant-side"><h4>当前 Agent 能做</h4><div class="prompt-card"><span>⌕</span><div><b>本地检索</b><p>FTS5 先查，再交给模型。</p></div></div><div class="prompt-card"><span>⇄</span><div><b>数据库操作</b><p>添加、修改、移动、删除、生命周期。</p></div></div><div class="prompt-card"><span>◉</span><div><b>Scope 隔离</b><p>我 / 爸 / 妈 / 生命周期可组合。</p></div></div><div class="side-note">附件只保存在本地，不喂给当前大语言模型。</div></aside>
      </section>

      <section class="view" :class="{active:currentView==='settings'}">
        <div class="settings-card"><div class="settings-title"><div class="settings-icon">AI</div><div><h3>AI 接口</h3><p>兼容 OpenAI Chat Completions。</p></div></div><form class="settings-form" @submit.prevent="saveSettings"><label>Base URL<input v-model="aiSettings.base_url" /></label><label>API Key<div class="password-wrap"><input v-model="apiKeyInput" :type="showApiKey?'text':'password'" placeholder="已保存时无需重复填写"/><button type="button" @click="showApiKey=!showApiKey">{{ showApiKey?'隐藏':'显示' }}</button></div></label><label>模型<input v-model="aiSettings.model" /></label><div class="settings-actions"><button class="primary-btn">保存设置</button><span v-if="settingsSaved">已保存</span></div></form></div>
        <div class="settings-card"><div class="settings-title"><div class="settings-icon">AI</div><div><h3>AI 数据边界</h3><p>当前版本只发送文本字段：名称、数量、成员、箱子、状态、备注、标签和生命周期。图片、附件内容、Base64、文件路径均不会发送给大模型。</p></div></div></div>
        <div class="settings-card"><div class="settings-title"><div class="settings-icon">MQ</div><div><h3>RabbitMQ</h3><p>{{ !mqStatus.enabled?'默认禁用':mqStatus.connected?'已连接':'未连接，主功能不受影响' }}</p></div></div><button class="outline-btn" @click="refreshMqStatus">刷新状态</button></div>
        <div class="settings-card"><div class="settings-title"><div class="settings-icon">LAN</div><div><h3>网络</h3><p>生产版 8765 默认只监听 127.0.0.1。需要局域网时显式设置 ITEMNEST_BIND_ADDRESS=0.0.0.0。</p></div></div></div>
      </section>
    </main>
  </div>

  <div v-if="itemModalOpen" class="modal-backdrop" @click.self="itemModalOpen=false"><div class="modal wide"><div class="modal-head"><div><p class="eyebrow">ITEM</p><h3>{{ itemEditingId?'编辑物品':'添加物品' }}</h3></div><button class="icon-btn" @click="itemModalOpen=false">×</button></div><form class="modal-form" @submit.prevent="saveItem"><label class="span-2">物品名称<input v-model="itemForm.name" required /></label><label>所在箱子<select v-model.number="itemForm.container_id" required><option v-for="c in containers" :key="c.id" :value="c.id">{{ c.owner_name }} / {{ c.name }}</option></select></label><label>数量<input v-model="itemForm.quantity" type="number" min="0" /></label><label>数量描述<input v-model="itemForm.quantity_text" placeholder="一些 / 一盒" /></label><label>状态<input v-model="itemForm.condition" /></label><label class="span-2">标签<input v-model="itemForm.tags" /></label><label class="span-2">备注<textarea v-model="itemForm.notes" rows="2"></textarea></label>
    <div class="span-2 option-panel"><label class="switch-line"><input v-model="itemForm.lifecycle_enabled" type="checkbox"/><b>生命周期（可选）</b><span>食品、电池、保修、更换、检查统一放这里</span></label><div v-if="itemForm.lifecycle_enabled" class="life-form"><label>类型<select v-model="itemForm.lifecycle_type"><option value="EXPIRY">有效期 / 保质期</option><option value="WARRANTY">保修</option><option value="REPLACE">建议更换</option><option value="CHECK">定期检查</option></select></label><label>开始日期<input v-model="itemForm.start_date" type="date"/></label><label>目标 / 到期日期<input v-model="itemForm.expiry_date" type="date"/></label><label>提前提醒（天）<input v-model="itemForm.remind_days" type="number" min="0"/></label><label class="span-2">生命周期备注<input v-model="itemForm.lifecycle_notes" placeholder="如：开封后冷藏 / 3 年更换一次"/></label></div></div>
    <div class="span-2 option-panel"><b>图片与附件（可选）</b><p class="muted">单文件 ≤20MB。附件保存在本地，不会发送给当前大语言模型。</p><input type="file" multiple @change="onFiles"/><div v-if="pendingFiles.length" class="file-chips"><span v-for="f in pendingFiles" :key="f.name">{{ f.name }}</span></div><div v-if="itemAttachments.length" class="attachment-list"><div v-for="a in itemAttachments" :key="a.id"><a :href="`/api/attachments/${a.id}/content`" target="_blank">{{ a.kind==='image'?'🖼':'📎' }} {{ a.filename }}</a><button type="button" @click="deleteAttachment(a)">删除</button></div></div></div>
    <div class="modal-actions span-2"><button type="button" class="outline-btn" @click="itemModalOpen=false">取消</button><button class="primary-btn">保存</button></div></form></div></div>

  <div v-if="containerModalOpen" class="modal-backdrop" @click.self="containerModalOpen=false"><div class="modal small"><div class="modal-head"><h3>{{ containerEditingId ? '编辑箱子' : '新建箱子' }}</h3><button class="icon-btn" @click="containerModalOpen=false">×</button></div><form class="modal-form one-col" @submit.prevent="saveContainer"><label>归属<select v-model.number="containerForm.owner_id"><option v-for="m in members" :key="m.id" :value="m.id">{{ m.name }}</option></select></label><label>名称<input v-model="containerForm.name" required /></label><label>备注<textarea v-model="containerForm.notes" rows="3"></textarea></label><div class="modal-actions"><button v-if="containerEditingId" type="button" class="outline-btn danger-outline" @click="deleteContainer">删除空箱子</button><span class="grow"></span><button type="button" class="outline-btn" @click="containerModalOpen=false">取消</button><button class="primary-btn">{{ containerEditingId ? '保存' : '创建' }}</button></div></form></div></div>

  <div v-if="openContainer" class="modal-backdrop container-detail-backdrop" @click.self="closeContainerDetail"><div class="modal container-detail-modal"><div class="container-detail-head"><div class="container-detail-title-wrap"><div class="box-mark large">▦</div><div><p class="eyebrow">{{ openContainer.owner_name }} / CONTAINER</p><h3>{{ openContainer.name }}</h3><p class="container-detail-note">{{ openContainer.notes || '收纳位置' }}</p></div></div><button class="icon-btn" @click="closeContainerDetail">×</button></div><div class="container-detail-toolbar"><span>{{ openContainer.item_count }} 类 · {{ openContainer.quantity_sum }} 件/组</span><div class="inline-actions"><button class="outline-btn compact" @click="openHistory(openContainer)">操作记录</button><button class="outline-btn compact" @click="openEditContainer(openContainer)">编辑箱子</button><button class="primary-btn compact" @click="openAddItem(openContainer.id)">＋ 放入物品</button></div></div><div v-if="openContainerItems.length" class="container-detail-items"><article v-for="item in openContainerItems" :key="item.id" class="container-item-row"><div class="container-item-icon">{{ firstChar(item.name) }}</div><div class="container-item-main"><h4>{{ item.name }}</h4><p>{{ item.notes }}</p><div class="item-meta"><span class="chip">{{ quantityText(item) }}</span><span v-if="item.expiry_date" class="chip life">{{ item.expiry_date }}</span><span v-if="item.attachment_count" class="chip">附件 {{ item.attachment_count }}</span></div></div><div class="container-item-actions"><button class="icon-btn" @click="openEditItem(item)">✎</button><button class="icon-btn danger" @click="deleteItem(item)">×</button></div></article></div><div v-else class="container-detail-empty"><h3>这个箱子还没有物品</h3></div></div></div>

  <div v-if="historyModalOpen" class="modal-backdrop" @click.self="historyModalOpen=false"><div class="modal history-modal"><div class="modal-head"><div><p class="eyebrow">HISTORY</p><h3>{{ historyContainerName }} · 操作记录</h3></div><button class="icon-btn" @click="historyModalOpen=false">×</button></div><div class="history-list"><article v-for="h in containerHistory" :key="h.id"><div><b>{{ h.description }}</b><span>{{ h.source }} · {{ formatTime(h.created_at) }}</span></div><div class="inline-actions"><span v-if="h.undone_at" class="undo-state">已撤销</span><button v-else-if="h.can_undo" class="text-btn" @click="undoHistory(h)">撤销</button></div></article><div v-if="!containerHistory.length" class="empty">暂无操作记录</div></div></div></div>

  <div v-if="quickModalOpen" class="modal-backdrop" @click.self="quickModalOpen=false"><div class="modal wide"><div class="modal-head"><div><p class="eyebrow">QUICK ENTRY</p><h3>快速录入</h3></div><button class="icon-btn" @click="quickModalOpen=false">×</button></div><form @submit.prevent="submitQuickEntry"><div class="quick-head"><label>统一放入箱子<select v-model.number="quickContainerId"><option v-for="c in filteredContainers" :key="c.id" :value="c.id">{{ c.name }}</option></select></label></div><div class="quick-table"><div class="quick-row head"><span>名称</span><span>数量</span><span>数量描述</span><span></span></div><div v-for="(r,index) in quickRows" :key="index" class="quick-row"><input v-model="r.name" placeholder="物品名称"/><input v-model="r.quantity" type="number" min="0"/><input v-model="r.quantity_text" placeholder="一盒 / 一些"/><button type="button" class="icon-btn" @click="removeQuickRow(index)">×</button></div></div><button type="button" class="text-btn" @click="addQuickRow">＋ 再加一行</button><div class="modal-actions"><button type="button" class="outline-btn" @click="quickModalOpen=false">取消</button><button class="primary-btn">一次录入</button></div></form></div></div>

  <div v-if="batchModalOpen" class="modal-backdrop" @click.self="batchModalOpen=false"><div class="modal wide"><div class="modal-head"><div><p class="eyebrow">BATCH</p><h3>批量操作 · {{ selectedMember?.name }}</h3></div><button class="icon-btn" @click="batchModalOpen=false">×</button></div><div class="batch-list"><label v-for="item in filteredItems" :key="item.id" :class="{selected:batchSelected.includes(item.id)}"><input type="checkbox" :checked="batchSelected.includes(item.id)" @change="toggleBatch(item.id)"/><span><b>{{ item.name }}</b><small>{{ item.container_name }}</small></span></label></div><div class="batch-controls"><label>移动到箱子<select v-model.number="batchTargetContainerId"><option :value="0">不修改位置</option><option v-for="c in filteredContainers" :key="c.id" :value="c.id">{{ c.name }}</option></select></label><label>状态<input v-model="batchCondition" placeholder="留空不修改"/></label><label>标签<input v-model="batchTags" placeholder="留空不修改"/></label></div><div class="modal-actions"><span class="muted">已选择 {{ batchSelected.length }} 类</span><button class="primary-btn" @click="submitBatch">执行批量修改</button></div></div></div>

  <div class="toast" :class="{show:toastText}">{{ toastText }}</div>
</template>
