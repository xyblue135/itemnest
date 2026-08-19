<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { api } from './api'
import type { AiAction, AiSettings, ChatResponse, Container, Item, MqStatus, Summary } from './types'

type ViewName = 'inventory' | 'containers' | 'assistant' | 'settings'
type ChatMessage = { id: number; role: 'user' | 'assistant'; text: string; action?: AiAction | null; executed?: boolean; error?: string }

const currentView = ref<ViewName>('inventory')
const summary = reactive<Summary>({ containers: 0, items: 0, quantity: 0, special: 0 })
const containers = ref<Container[]>([])
const items = ref<Item[]>([])
const search = ref('')
const searchResults = ref<Item[]>([])
const searching = computed(() => Boolean(search.value.trim()))
const openContainerId = ref<number | null>(null)

const itemModalOpen = ref(false)
const containerModalOpen = ref(false)
const itemEditingId = ref<number | null>(null)
const itemForm = reactive({
  name: '',
  container_id: 0,
  quantity: '1',
  quantity_text: '',
  condition: '正常',
  notes: '',
  tags: '',
})
const containerForm = reactive({ name: '', notes: '' })

const aiSettings = reactive<AiSettings>({ base_url: '', model: 'auto', has_api_key: false })
const apiKeyInput = ref('')
const showApiKey = ref(false)
const settingsSaved = ref(false)
const mqStatus = reactive<MqStatus>({ enabled: false, connected: false, url: '—', exchange: '—', queue: '—', last_error: '', client: '' })

const chatInput = ref('')
const chatBusy = ref(false)
let messageId = 1
const chatMessages = ref<ChatMessage[]>([
  { id: messageId++, role: 'assistant', text: '你好，我可以帮你查物品，也可以生成待确认的数据库操作。试试问：\n“我的 HDMI 线都在哪里？”\n“把华为手环移动到蓝色箱子。”' },
])
const chatSuggestions = ['HDMI 相关的东西在哪里？', '哪些物品状态不正常？', '12V 电源适配器有哪些？']

const toastText = ref('')
let toastTimer: number | undefined
let searchTimer: number | undefined
const deferredPrompt = ref<any>(null)

const pageTitle = computed(() => ({
  inventory: '我的收纳',
  containers: '箱子管理',
  assistant: 'AI 助手',
  settings: '设置',
}[currentView.value]))

const openContainer = computed(() => containers.value.find(c => c.id === openContainerId.value) ?? null)
const openContainerItems = computed(() => openContainerId.value == null ? [] : items.value.filter(i => i.container_id === openContainerId.value))

function toast(text: string) {
  toastText.value = text
  window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => { toastText.value = '' }, 2200)
}

function quantityText(item: Item) {
  if (item.quantity_text) return item.quantity_text
  if (item.quantity == null) return '未记录'
  return `${item.quantity} 个`
}

function firstChar(name: string) {
  return (name || '物').trim().charAt(0).toUpperCase()
}

async function loadAll() {
  const [s, cs, xs, settings, mq] = await Promise.all([
    api<Summary>('/api/summary'),
    api<Container[]>('/api/containers'),
    api<Item[]>('/api/items'),
    api<AiSettings>('/api/settings'),
    api<MqStatus>('/api/mq/status'),
  ])
  Object.assign(summary, s)
  containers.value = cs
  items.value = xs
  Object.assign(aiSettings, settings)
  Object.assign(mqStatus, mq)
  if (!itemForm.container_id && cs.length) itemForm.container_id = cs[0].id
  if (searching.value) await runSearch()
}

async function refreshInventoryMeta() {
  const [s, cs] = await Promise.all([
    api<Summary>('/api/summary'),
    api<Container[]>('/api/containers'),
  ])
  Object.assign(summary, s)
  containers.value = cs
  if (!itemForm.container_id && cs.length) itemForm.container_id = cs[0].id
  if (searching.value) await runSearch()
}

async function refreshInventoryData() {
  const [s, cs, xs] = await Promise.all([
    api<Summary>('/api/summary'),
    api<Container[]>('/api/containers'),
    api<Item[]>('/api/items'),
  ])
  Object.assign(summary, s)
  containers.value = cs
  items.value = xs
  if (searching.value) await runSearch()
}

function upsertItem(item: Item) {
  items.value = [item, ...items.value.filter(existing => existing.id !== item.id)]
}

function switchView(view: ViewName) {
  currentView.value = view
}

function openContainerDetail(id: number) {
  openContainerId.value = id
  document.body.classList.add('modal-open')
}

function closeContainerDetail() {
  openContainerId.value = null
  document.body.classList.remove('modal-open')
}

function resetItemForm(containerId?: number) {
  itemEditingId.value = null
  itemForm.name = ''
  itemForm.container_id = containerId ?? openContainerId.value ?? containers.value[0]?.id ?? 0
  itemForm.quantity = '1'
  itemForm.quantity_text = ''
  itemForm.condition = '正常'
  itemForm.notes = ''
  itemForm.tags = ''
}

function openAddItem(containerId?: number) {
  resetItemForm(containerId)
  itemModalOpen.value = true
}

function openEditItem(item: Item) {
  itemEditingId.value = item.id
  itemForm.name = item.name
  itemForm.container_id = item.container_id
  itemForm.quantity = item.quantity == null ? '' : String(item.quantity)
  itemForm.quantity_text = item.quantity_text || ''
  itemForm.condition = item.condition || '正常'
  itemForm.notes = item.notes || ''
  itemForm.tags = item.tags || ''
  itemModalOpen.value = true
}

async function saveItem() {
  const payload = {
    name: itemForm.name.trim(),
    container_id: Number(itemForm.container_id),
    quantity: itemForm.quantity === '' ? null : Number(itemForm.quantity),
    quantity_text: itemForm.quantity_text.trim(),
    condition: itemForm.condition.trim() || '正常',
    notes: itemForm.notes.trim(),
    tags: itemForm.tags.trim(),
  }
  try {
    const saved = await api<Item>(itemEditingId.value ? `/api/items/${itemEditingId.value}` : '/api/items', {
      method: itemEditingId.value ? 'PATCH' : 'POST',
      body: JSON.stringify(payload),
    })
    upsertItem(saved)
    itemModalOpen.value = false
    toast(itemEditingId.value ? '已保存修改' : '已添加物品')
    await refreshInventoryMeta()
  } catch (error) {
    toast((error as Error).message)
  }
}

async function deleteItem(item: Item) {
  if (!window.confirm(`确定删除“${item.name}”吗？`)) return
  try {
    await api(`/api/items/${item.id}`, { method: 'DELETE' })
    items.value = items.value.filter(existing => existing.id !== item.id)
    searchResults.value = searchResults.value.filter(existing => existing.id !== item.id)
    toast('已删除')
    await refreshInventoryMeta()
  } catch (error) {
    toast((error as Error).message)
  }
}

function openAddContainer() {
  containerForm.name = ''
  containerForm.notes = ''
  containerModalOpen.value = true
}

async function saveContainer() {
  try {
    await api('/api/containers', {
      method: 'POST',
      body: JSON.stringify({ name: containerForm.name.trim(), notes: containerForm.notes.trim() }),
    })
    containerModalOpen.value = false
    toast('箱子已创建')
    await refreshInventoryMeta()
  } catch (error) {
    toast((error as Error).message)
  }
}

async function runSearch() {
  const q = search.value.trim()
  if (!q) {
    searchResults.value = []
    return
  }
  try {
    searchResults.value = await api<Item[]>(`/api/items?q=${encodeURIComponent(q)}`)
  } catch (error) {
    toast((error as Error).message)
  }
}

function onSearchInput() {
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(runSearch, 180)
}

function clearSearch() {
  search.value = ''
  searchResults.value = []
}

async function saveSettings() {
  const payload: Record<string, string> = {
    base_url: aiSettings.base_url.trim(),
    model: aiSettings.model.trim() || 'auto',
  }
  if (apiKeyInput.value.trim()) payload.api_key = apiKeyInput.value.trim()
  try {
    const saved = await api<AiSettings>('/api/settings', { method: 'POST', body: JSON.stringify(payload) })
    Object.assign(aiSettings, saved)
    apiKeyInput.value = ''
    settingsSaved.value = true
    toast('AI 设置已保存')
    window.setTimeout(() => { settingsSaved.value = false }, 1800)
  } catch (error) {
    toast((error as Error).message)
  }
}

async function refreshMqStatus() {
  try {
    Object.assign(mqStatus, await api<MqStatus>('/api/mq/status'))
  } catch (error) {
    toast((error as Error).message)
  }
}

function describeAction(action: AiAction) {
  if (action.type === 'add_item') return `新增“${String(action.data?.name || '物品')}”`
  if (action.type === 'update_item') return `修改物品 #${action.item_id}`
  if (action.type === 'move_item') return `移动物品 #${action.item_id} 到箱子 #${action.container_id}`
  if (action.type === 'delete_item') return `删除物品 #${action.item_id}`
  if (action.type === 'add_container') return `新增箱子“${String(action.data?.name || '')}”`
  return action.type || '未知操作'
}

async function executeAction(message: ChatMessage) {
  if (!message.action || message.executed) return
  message.error = ''
  try {
    const result = await api<{ message: string }>('/api/ai/execute', {
      method: 'POST',
      body: JSON.stringify({ action: message.action }),
    })
    message.text = `${message.text}\n\n${result.message}`
    message.executed = true
    await refreshInventoryData()
  } catch (error) {
    message.error = (error as Error).message
  }
}

async function sendChat(text = chatInput.value) {
  const value = text.trim()
  if (!value || chatBusy.value) return
  chatMessages.value.push({ id: messageId++, role: 'user', text: value })
  chatInput.value = ''
  chatBusy.value = true
  await nextTick()
  scrollChat()
  try {
    const response = await api<ChatResponse>('/api/chat', {
      method: 'POST',
      body: JSON.stringify({ message: value }),
    })
    chatMessages.value.push({ id: messageId++, role: 'assistant', text: response.reply || '已处理。', action: response.action })
  } catch (error) {
    chatMessages.value.push({ id: messageId++, role: 'assistant', text: `请求失败：${(error as Error).message}` })
  } finally {
    chatBusy.value = false
    await nextTick()
    scrollChat()
  }
}

function scrollChat() {
  const box = document.querySelector<HTMLElement>('.chat-messages')
  if (box) box.scrollTop = box.scrollHeight
}

async function installApp() {
  if (!deferredPrompt.value) return
  deferredPrompt.value.prompt()
  await deferredPrompt.value.userChoice
  deferredPrompt.value = null
}

function onKeydown(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    currentView.value = 'inventory'
    nextTick(() => document.querySelector<HTMLInputElement>('.search-box input')?.focus())
  }
  if (event.key === 'Escape') {
    if (itemModalOpen.value) itemModalOpen.value = false
    else if (containerModalOpen.value) containerModalOpen.value = false
    else if (openContainerId.value != null) closeContainerDetail()
  }
}

function onBeforeInstallPrompt(event: Event) {
  event.preventDefault()
  deferredPrompt.value = event
}

onMounted(async () => {
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('beforeinstallprompt', onBeforeInstallPrompt)
  if (import.meta.env.PROD && 'serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js').catch(() => undefined)
  }
  try {
    await loadAll()
  } catch (error) {
    toast((error as Error).message)
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('beforeinstallprompt', onBeforeInstallPrompt)
  window.clearTimeout(toastTimer)
  window.clearTimeout(searchTimer)
})
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark">物</div>
        <div><strong>物栈</strong><span>ItemNest</span></div>
      </div>
      <nav class="nav-list">
        <button class="nav-btn" :class="{ active: currentView === 'inventory' }" @click="switchView('inventory')"><span>⌂</span><em>收纳</em></button>
        <button class="nav-btn" :class="{ active: currentView === 'containers' }" @click="switchView('containers')"><span>▦</span><em>管理</em></button>
        <button class="nav-btn" :class="{ active: currentView === 'assistant' }" @click="switchView('assistant')"><span>✦</span><em>AI 助手</em></button>
        <button class="nav-btn" :class="{ active: currentView === 'settings' }" @click="switchView('settings')"><span>⚙</span><em>设置</em></button>
      </nav>
      <div class="sidebar-foot"><div class="status-dot"></div><span>SQLite 本地数据库</span></div>
    </aside>

    <main class="main">
      <header class="topbar">
        <div><p class="eyebrow">PRIVATE INVENTORY</p><h1>{{ pageTitle }}</h1></div>
        <div class="top-actions">
          <button v-if="deferredPrompt" class="ghost-btn" @click="installApp">安装 App</button>
          <button v-if="currentView !== 'settings'" class="primary-btn" @click="openAddItem()">＋ 添加物品</button>
        </div>
      </header>

      <section class="view" :class="{ active: currentView === 'inventory' }">
        <div class="hero-card">
          <div class="hero-copy">
            <span class="pill">快速查找</span>
            <h2>“我的 HDMI 线放在哪？”</h2>
            <p>可以直接搜索，也可以交给 AI 用自然语言帮你查找、移动、添加和修改物品。</p>
          </div>
          <button class="hero-ai" @click="switchView('assistant')">问 AI <span>→</span></button>
        </div>

        <div class="stats-grid">
          <article class="stat"><span>箱子</span><strong>{{ summary.containers }}</strong><small>个收纳位置</small></article>
          <article class="stat"><span>物品</span><strong>{{ summary.items }}</strong><small>类已登记</small></article>
          <article class="stat"><span>数量</span><strong>{{ summary.quantity }}</strong><small>件/组（可计数）</small></article>
          <article class="stat"><span>状态提醒</span><strong>{{ summary.special }}</strong><small>项非“正常”</small></article>
        </div>

        <div class="toolbar storage-toolbar">
          <label class="search-box"><span>⌕</span><input v-model="search" placeholder="搜索物品、标签、状态或箱子…" autocomplete="off" @input="onSearchInput" /><kbd>Ctrl K</kbd></label>
          <button class="outline-btn" @click="clearSearch">清除搜索</button>
        </div>

        <div v-if="!searching">
          <div class="section-head">
            <div><h3>我的箱子</h3><p>点击箱子打开后，才显示里面的物品。</p></div>
            <span class="privacy-hint">▣ 箱内清单默认隐藏</span>
          </div>
          <div class="containers-grid">
            <article v-for="c in containers" :key="c.id" class="container-card" role="button" tabindex="0" @click="openContainerDetail(c.id)" @keydown.enter="openContainerDetail(c.id)" @keydown.space.prevent="openContainerDetail(c.id)">
              <div class="container-card-top"><div class="box-mark">▦</div><span class="box-closed">已收起</span></div>
              <h4>{{ c.name }}</h4><p>{{ c.notes || '收纳位置' }}</p>
              <div class="container-bottom"><span class="container-count">{{ c.item_count }} 类 · 约 {{ c.quantity_sum }} 件/组</span><span class="container-open">打开箱子 →</span></div>
            </article>
          </div>
        </div>

        <div v-else>
          <div class="section-head">
            <div><h3>搜索结果</h3><p>共 {{ searchResults.length }} 类物品</p></div>
            <button class="text-btn" @click="clearSearch">← 返回箱子</button>
          </div>
          <div class="items-grid">
            <article v-for="item in searchResults" :key="item.id" class="item-card">
              <div class="item-top">
                <div class="item-icon">{{ firstChar(item.name) }}</div>
                <div class="item-actions"><button class="icon-btn" title="编辑" @click="openEditItem(item)">✎</button><button class="icon-btn danger" title="删除" @click="deleteItem(item)">×</button></div>
              </div>
              <h4>{{ item.name }}</h4><div class="location">▣ {{ item.container_name }}</div>
              <p v-if="item.notes" class="item-notes" :title="item.notes">{{ item.notes }}</p>
              <div class="item-meta">
                <span class="chip">{{ quantityText(item) }}</span>
                <span v-if="item.condition && item.condition !== '正常'" class="chip bad">{{ item.condition }}</span>
                <span v-for="tag in item.tags.split(',').filter(Boolean).slice(0, 2)" :key="tag" class="chip">#{{ tag.trim() }}</span>
              </div>
            </article>
          </div>
          <div v-if="searchResults.length === 0" class="empty"><div class="empty-icon">⌕</div><h3>没找到匹配物品</h3><p>换个关键词，或者让 AI 帮你找。</p></div>
        </div>
      </section>

      <section class="view" :class="{ active: currentView === 'containers' }">
        <div class="section-head row-mobile">
          <div><h3>箱子管理</h3><p>这里只管理收纳位置；点击箱子才打开箱内清单。</p></div>
          <button class="primary-btn" @click="openAddContainer">＋ 新建箱子</button>
        </div>
        <div class="containers-grid">
          <article v-for="c in containers" :key="c.id" class="container-card" role="button" tabindex="0" @click="openContainerDetail(c.id)" @keydown.enter="openContainerDetail(c.id)" @keydown.space.prevent="openContainerDetail(c.id)">
            <div class="container-card-top"><div class="box-mark">▦</div><span class="box-closed">已收起</span></div>
            <h4>{{ c.name }}</h4><p>{{ c.notes || '收纳位置' }}</p>
            <div class="container-bottom"><span class="container-count">{{ c.item_count }} 类 · 约 {{ c.quantity_sum }} 件/组</span><span class="container-open">打开箱子 →</span></div>
          </article>
        </div>
      </section>

      <section class="view assistant-layout" :class="{ active: currentView === 'assistant' }">
        <div class="chat-panel">
          <div class="chat-head">
            <div class="ai-avatar">✦</div>
            <div><h3>物栈 AI</h3><p><span class="mini-dot" :style="{ background: aiSettings.has_api_key ? '#22c55e' : '#f59e0b' }"></span>{{ aiSettings.has_api_key ? 'AI 接口已配置' : '本地检索模式' }}</p></div>
          </div>
          <div class="chat-messages">
            <div v-for="message in chatMessages" :key="message.id" class="message" :class="message.role">
              <div class="bubble">
                {{ message.text }}
                <div v-if="message.action && !message.executed" class="action-card">
                  <p>待确认操作：{{ describeAction(message.action) }}</p>
                  <p v-if="message.error" class="action-error">{{ message.error }}</p>
                  <button @click="executeAction(message)">确认执行</button>
                </div>
              </div>
            </div>
            <div v-if="chatBusy" class="message assistant"><div class="bubble">正在查数据库…</div></div>
          </div>
          <div class="suggestions">
            <button v-for="suggestion in chatSuggestions" :key="suggestion" @click="sendChat(suggestion)">{{ suggestion }}</button>
          </div>
          <form class="chat-input-wrap" @submit.prevent="sendChat()">
            <textarea v-model="chatInput" rows="1" placeholder="问物品在哪里，或让 AI 添加 / 移动 / 修改…" @keydown.enter.exact.prevent="sendChat()"></textarea>
            <button type="submit" class="send-btn">↑</button>
          </form>
          <p class="chat-hint">修改数据库前会要求你确认。AI 不可用时自动降级为本地搜索。</p>
        </div>
        <aside class="assistant-side">
          <h4>你可以这样问</h4>
          <div class="prompt-card"><span>⌕</span><div><b>查找</b><p>“网线钳放在哪里？”</p></div></div>
          <div class="prompt-card"><span>⇄</span><div><b>移动</b><p>“把镊子移到银色箱子。”</p></div></div>
          <div class="prompt-card"><span>＋</span><div><b>添加</b><p>“在蓝色箱子加一个 65W 充电器。”</p></div></div>
          <div class="prompt-card"><span>✎</span><div><b>修改</b><p>“把小黄刀备注改成剥线专用。”</p></div></div>
          <div class="side-note">AI 作为数据库操作规划器，真正执行前仍由你确认。</div>
        </aside>
      </section>

      <section class="view" :class="{ active: currentView === 'settings' }">
        <div class="settings-card">
          <div class="settings-title"><div class="settings-icon">AI</div><div><h3>AI 接口</h3><p>兼容 OpenAI Chat Completions 的代理接口。</p></div></div>
          <form class="settings-form" @submit.prevent="saveSettings">
            <label>Base URL<input v-model="aiSettings.base_url" placeholder="https://api.openai.com/v1" /></label>
            <label>API Key<div class="password-wrap"><input v-model="apiKeyInput" :type="showApiKey ? 'text' : 'password'" placeholder="已保存时无需重复填写" /><button type="button" @click="showApiKey = !showApiKey">{{ showApiKey ? '隐藏' : '显示' }}</button></div><small :style="{ color: aiSettings.has_api_key ? '#16a34a' : '#98a2b3' }">{{ aiSettings.has_api_key ? 'API Key 已保存在本机服务器' : '尚未保存 API Key' }}</small></label>
            <label>模型<input v-model="aiSettings.model" placeholder="auto" /><small>如果你的代理不支持 auto，请填写实际聊天模型名。</small></label>
            <div class="settings-actions"><button type="submit" class="primary-btn">保存设置</button><span v-if="settingsSaved">已保存</span></div>
          </form>
        </div>

        <div class="settings-card">
          <div class="settings-title"><div class="settings-icon">MQ</div><div><h3>RabbitMQ 消息队列</h3><p>库存写操作成功后异步发布事件，不阻塞 SQLite 主流程。</p></div></div>
          <div class="lan-help mq-status-box">
            <p><span class="mini-dot" :style="{ background: !mqStatus.enabled ? '#98a2b3' : mqStatus.connected ? '#22c55e' : '#f59e0b' }"></span><b>{{ !mqStatus.enabled ? '已禁用' : mqStatus.connected ? 'RabbitMQ 已连接' : 'RabbitMQ 未连接（主功能不受影响）' }}</b></p>
            <p>连接：<code>{{ mqStatus.url || '—' }}</code></p>
            <p>Exchange：<code>{{ mqStatus.exchange || '—' }}</code>　Queue：<code>{{ mqStatus.queue || '—' }}</code></p>
            <p v-if="mqStatus.last_error">最近错误：{{ mqStatus.last_error }}</p>
            <button type="button" class="outline-btn" @click="refreshMqStatus">刷新状态</button>
          </div>
        </div>

        <div class="settings-card">
          <div class="settings-title"><div class="settings-icon">LAN</div><div><h3>手机访问</h3><p>生产版由 8765 端口提供前后端；默认只允许本机访问。</p></div></div>
          <div class="lan-help"><p>需要手机访问时，先设置 <code>ITEMNEST_BIND_ADDRESS=0.0.0.0</code> 再运行 <code>start.bat</code>，然后访问：</p><code>http://电脑局域网IP:8765</code><p>开发模式 Vite 使用 <code>15473</code>，默认同样只监听本机。</p></div>
        </div>

        <div class="settings-card warning-card">
          <div class="settings-title"><div class="settings-icon">!</div><div><h3>局域网安全</h3><p>当前未加入账号登录。不要把 8765 端口直接映射到公网。</p></div></div>
        </div>
      </section>
    </main>
  </div>

  <div v-if="itemModalOpen" class="modal-backdrop" @click.self="itemModalOpen = false">
    <div class="modal" role="dialog" aria-modal="true">
      <div class="modal-head"><div><p class="eyebrow">ITEM</p><h3>{{ itemEditingId ? '编辑物品' : '添加物品' }}</h3></div><button class="icon-btn" @click="itemModalOpen = false">×</button></div>
      <form class="modal-form" @submit.prevent="saveItem">
        <label class="span-2">物品名称<input v-model="itemForm.name" required placeholder="例如：HDMI 线" /></label>
        <label>所在箱子<select v-model.number="itemForm.container_id" required><option v-for="c in containers" :key="c.id" :value="c.id">{{ c.name }}</option></select></label>
        <label>数量<input v-model="itemForm.quantity" type="number" min="0" placeholder="1" /></label>
        <label>数量描述<input v-model="itemForm.quantity_text" placeholder="例如：一些 / 很多" /></label>
        <label>状态<input v-model="itemForm.condition" placeholder="正常 / 损坏 / 没电" /></label>
        <label class="span-2">标签<input v-model="itemForm.tags" placeholder="电脑, HDMI, 线材" /></label>
        <label class="span-2">备注<textarea v-model="itemForm.notes" rows="3" placeholder="记录更具体的位置、用途或状态…"></textarea></label>
        <div class="modal-actions span-2"><button type="button" class="outline-btn" @click="itemModalOpen = false">取消</button><button type="submit" class="primary-btn">保存</button></div>
      </form>
    </div>
  </div>

  <div v-if="containerModalOpen" class="modal-backdrop" @click.self="containerModalOpen = false">
    <div class="modal small" role="dialog" aria-modal="true">
      <div class="modal-head"><div><p class="eyebrow">CONTAINER</p><h3>新建箱子</h3></div><button class="icon-btn" @click="containerModalOpen = false">×</button></div>
      <form class="modal-form one-col" @submit.prevent="saveContainer">
        <label>名称<input v-model="containerForm.name" required placeholder="例如：黑色工具箱" /></label>
        <label>备注<textarea v-model="containerForm.notes" rows="3" placeholder="可选"></textarea></label>
        <div class="modal-actions"><button type="button" class="outline-btn" @click="containerModalOpen = false">取消</button><button type="submit" class="primary-btn">创建</button></div>
      </form>
    </div>
  </div>

  <div v-if="openContainer" class="modal-backdrop container-detail-backdrop" @click.self="closeContainerDetail">
    <div class="modal container-detail-modal" role="dialog" aria-modal="true">
      <div class="container-detail-head">
        <div class="container-detail-title-wrap"><div class="box-mark large">▦</div><div><p class="eyebrow">OPEN CONTAINER</p><h3>{{ openContainer.name }}</h3><p class="container-detail-note">{{ openContainer.notes || '收纳位置' }}</p></div></div>
        <button class="icon-btn" @click="closeContainerDetail">×</button>
      </div>
      <div class="container-detail-toolbar"><span class="container-count">{{ openContainer.item_count }} 类 · 约 {{ openContainer.quantity_sum }} 件/组</span><button class="primary-btn compact" @click="openAddItem(openContainer.id)">＋ 放入物品</button></div>
      <div v-if="openContainerItems.length" class="container-detail-items">
        <article v-for="item in openContainerItems" :key="item.id" class="container-item-row">
          <div class="container-item-icon">{{ firstChar(item.name) }}</div>
          <div class="container-item-main"><h4>{{ item.name }}</h4><p v-if="item.notes">{{ item.notes }}</p><div class="item-meta"><span class="chip">{{ quantityText(item) }}</span><span v-if="item.condition && item.condition !== '正常'" class="chip bad">{{ item.condition }}</span></div></div>
          <div class="container-item-actions"><button class="icon-btn" title="编辑" @click="openEditItem(item)">✎</button><button class="icon-btn danger" title="删除" @click="deleteItem(item)">×</button></div>
        </article>
      </div>
      <div v-else class="container-detail-empty"><div class="empty-icon">□</div><h3>没有逐项登记的物品</h3><p>{{ openContainer.notes || '这个箱子目前没有物品记录。' }}</p></div>
    </div>
  </div>

  <div class="toast" :class="{ show: toastText }">{{ toastText }}</div>
</template>
