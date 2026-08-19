import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { api } from './api';
const currentView = ref('inventory');
const summary = reactive({ containers: 0, items: 0, quantity: 0, special: 0 });
const containers = ref([]);
const items = ref([]);
const search = ref('');
const searchResults = ref([]);
const searching = computed(() => Boolean(search.value.trim()));
const openContainerId = ref(null);
const itemModalOpen = ref(false);
const containerModalOpen = ref(false);
const itemEditingId = ref(null);
const itemForm = reactive({
    name: '',
    container_id: 0,
    quantity: '1',
    quantity_text: '',
    condition: '正常',
    notes: '',
    tags: '',
});
const containerForm = reactive({ name: '', notes: '' });
const aiSettings = reactive({ base_url: '', model: 'auto', has_api_key: false });
const apiKeyInput = ref('');
const showApiKey = ref(false);
const settingsSaved = ref(false);
const mqStatus = reactive({ enabled: true, connected: false, url: '—', exchange: '—', queue: '—', last_error: '', client: '' });
const chatInput = ref('');
const chatBusy = ref(false);
let messageId = 1;
const chatMessages = ref([
    { id: messageId++, role: 'assistant', text: '你好，我可以帮你查物品，也可以生成待确认的数据库操作。试试问：\n“我的 HDMI 线都在哪里？”\n“把华为手环移动到蓝色箱子。”' },
]);
const chatSuggestions = ['HDMI 相关的东西在哪里？', '哪些物品状态不正常？', '12V 电源适配器有哪些？'];
const toastText = ref('');
let toastTimer;
let searchTimer;
const deferredPrompt = ref(null);
const pageTitle = computed(() => ({
    inventory: '我的收纳',
    containers: '箱子管理',
    assistant: 'AI 助手',
    settings: '设置',
}[currentView.value]));
const openContainer = computed(() => containers.value.find(c => c.id === openContainerId.value) ?? null);
const openContainerItems = computed(() => openContainerId.value == null ? [] : items.value.filter(i => i.container_id === openContainerId.value));
function toast(text) {
    toastText.value = text;
    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(() => { toastText.value = ''; }, 2200);
}
function quantityText(item) {
    if (item.quantity_text)
        return item.quantity_text;
    if (item.quantity == null)
        return '未记录';
    return `${item.quantity} 个`;
}
function firstChar(name) {
    return (name || '物').trim().charAt(0).toUpperCase();
}
async function loadAll() {
    const [s, cs, xs, settings, mq] = await Promise.all([
        api('/api/summary'),
        api('/api/containers'),
        api('/api/items'),
        api('/api/settings'),
        api('/api/mq/status'),
    ]);
    Object.assign(summary, s);
    containers.value = cs;
    items.value = xs;
    Object.assign(aiSettings, settings);
    Object.assign(mqStatus, mq);
    if (!itemForm.container_id && cs.length)
        itemForm.container_id = cs[0].id;
    if (searching.value)
        await runSearch();
}
function switchView(view) {
    currentView.value = view;
}
function openContainerDetail(id) {
    openContainerId.value = id;
    document.body.classList.add('modal-open');
}
function closeContainerDetail() {
    openContainerId.value = null;
    document.body.classList.remove('modal-open');
}
function resetItemForm(containerId) {
    itemEditingId.value = null;
    itemForm.name = '';
    itemForm.container_id = containerId ?? openContainerId.value ?? containers.value[0]?.id ?? 0;
    itemForm.quantity = '1';
    itemForm.quantity_text = '';
    itemForm.condition = '正常';
    itemForm.notes = '';
    itemForm.tags = '';
}
function openAddItem(containerId) {
    resetItemForm(containerId);
    itemModalOpen.value = true;
}
function openEditItem(item) {
    itemEditingId.value = item.id;
    itemForm.name = item.name;
    itemForm.container_id = item.container_id;
    itemForm.quantity = item.quantity == null ? '' : String(item.quantity);
    itemForm.quantity_text = item.quantity_text || '';
    itemForm.condition = item.condition || '正常';
    itemForm.notes = item.notes || '';
    itemForm.tags = item.tags || '';
    itemModalOpen.value = true;
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
    };
    try {
        await api(itemEditingId.value ? `/api/items/${itemEditingId.value}` : '/api/items', {
            method: itemEditingId.value ? 'PATCH' : 'POST',
            body: JSON.stringify(payload),
        });
        itemModalOpen.value = false;
        toast(itemEditingId.value ? '已保存修改' : '已添加物品');
        await loadAll();
    }
    catch (error) {
        toast(error.message);
    }
}
async function deleteItem(item) {
    if (!window.confirm(`确定删除“${item.name}”吗？`))
        return;
    try {
        await api(`/api/items/${item.id}`, { method: 'DELETE' });
        toast('已删除');
        await loadAll();
    }
    catch (error) {
        toast(error.message);
    }
}
function openAddContainer() {
    containerForm.name = '';
    containerForm.notes = '';
    containerModalOpen.value = true;
}
async function saveContainer() {
    try {
        await api('/api/containers', {
            method: 'POST',
            body: JSON.stringify({ name: containerForm.name.trim(), notes: containerForm.notes.trim() }),
        });
        containerModalOpen.value = false;
        toast('箱子已创建');
        await loadAll();
    }
    catch (error) {
        toast(error.message);
    }
}
async function runSearch() {
    const q = search.value.trim();
    if (!q) {
        searchResults.value = [];
        return;
    }
    try {
        searchResults.value = await api(`/api/items?q=${encodeURIComponent(q)}`);
    }
    catch (error) {
        toast(error.message);
    }
}
function onSearchInput() {
    window.clearTimeout(searchTimer);
    searchTimer = window.setTimeout(runSearch, 180);
}
function clearSearch() {
    search.value = '';
    searchResults.value = [];
}
async function saveSettings() {
    const payload = {
        base_url: aiSettings.base_url.trim(),
        model: aiSettings.model.trim() || 'auto',
    };
    if (apiKeyInput.value.trim())
        payload.api_key = apiKeyInput.value.trim();
    try {
        const saved = await api('/api/settings', { method: 'POST', body: JSON.stringify(payload) });
        Object.assign(aiSettings, saved);
        apiKeyInput.value = '';
        settingsSaved.value = true;
        toast('AI 设置已保存');
        window.setTimeout(() => { settingsSaved.value = false; }, 1800);
    }
    catch (error) {
        toast(error.message);
    }
}
async function refreshMqStatus() {
    try {
        Object.assign(mqStatus, await api('/api/mq/status'));
    }
    catch (error) {
        toast(error.message);
    }
}
function describeAction(action) {
    if (action.type === 'add_item')
        return `新增“${String(action.data?.name || '物品')}”`;
    if (action.type === 'update_item')
        return `修改物品 #${action.item_id}`;
    if (action.type === 'move_item')
        return `移动物品 #${action.item_id} 到箱子 #${action.container_id}`;
    if (action.type === 'delete_item')
        return `删除物品 #${action.item_id}`;
    if (action.type === 'add_container')
        return `新增箱子“${String(action.data?.name || '')}”`;
    return action.type || '未知操作';
}
async function executeAction(message) {
    if (!message.action || message.executed)
        return;
    message.error = '';
    try {
        const result = await api('/api/ai/execute', {
            method: 'POST',
            body: JSON.stringify({ action: message.action }),
        });
        message.text = `${message.text}\n\n${result.message}`;
        message.executed = true;
        await loadAll();
    }
    catch (error) {
        message.error = error.message;
    }
}
async function sendChat(text = chatInput.value) {
    const value = text.trim();
    if (!value || chatBusy.value)
        return;
    chatMessages.value.push({ id: messageId++, role: 'user', text: value });
    chatInput.value = '';
    chatBusy.value = true;
    await nextTick();
    scrollChat();
    try {
        const response = await api('/api/chat', {
            method: 'POST',
            body: JSON.stringify({ message: value }),
        });
        chatMessages.value.push({ id: messageId++, role: 'assistant', text: response.reply || '已处理。', action: response.action });
    }
    catch (error) {
        chatMessages.value.push({ id: messageId++, role: 'assistant', text: `请求失败：${error.message}` });
    }
    finally {
        chatBusy.value = false;
        await nextTick();
        scrollChat();
    }
}
function scrollChat() {
    const box = document.querySelector('.chat-messages');
    if (box)
        box.scrollTop = box.scrollHeight;
}
async function installApp() {
    if (!deferredPrompt.value)
        return;
    deferredPrompt.value.prompt();
    await deferredPrompt.value.userChoice;
    deferredPrompt.value = null;
}
function onKeydown(event) {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        currentView.value = 'inventory';
        nextTick(() => document.querySelector('.search-box input')?.focus());
    }
    if (event.key === 'Escape') {
        if (itemModalOpen.value)
            itemModalOpen.value = false;
        else if (containerModalOpen.value)
            containerModalOpen.value = false;
        else if (openContainerId.value != null)
            closeContainerDetail();
    }
}
function onBeforeInstallPrompt(event) {
    event.preventDefault();
    deferredPrompt.value = event;
}
onMounted(async () => {
    window.addEventListener('keydown', onKeydown);
    window.addEventListener('beforeinstallprompt', onBeforeInstallPrompt);
    if (import.meta.env.PROD && 'serviceWorker' in navigator) {
        navigator.serviceWorker.register('/sw.js').catch(() => undefined);
    }
    try {
        await loadAll();
    }
    catch (error) {
        toast(error.message);
    }
});
onBeforeUnmount(() => {
    window.removeEventListener('keydown', onKeydown);
    window.removeEventListener('beforeinstallprompt', onBeforeInstallPrompt);
    window.clearTimeout(toastTimer);
    window.clearTimeout(searchTimer);
});
const __VLS_ctx = {
    ...{},
    ...{},
};
let __VLS_components;
let __VLS_intrinsics;
let __VLS_directives;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "app-shell" },
});
/** @type {__VLS_StyleScopedClasses['app-shell']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.aside, __VLS_intrinsics.aside)({
    ...{ class: "sidebar" },
});
/** @type {__VLS_StyleScopedClasses['sidebar']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "brand" },
});
/** @type {__VLS_StyleScopedClasses['brand']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "brand-mark" },
});
/** @type {__VLS_StyleScopedClasses['brand-mark']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.nav, __VLS_intrinsics.nav)({
    ...{ class: "nav-list" },
});
/** @type {__VLS_StyleScopedClasses['nav-list']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    ...{ onClick: (...[$event]) => {
            return (__VLS_ctx.switchView('inventory'));
            // @ts-ignore
            [switchView,];
        } },
    ...{ class: "nav-btn" },
    ...{ class: ({ active: __VLS_ctx.currentView === 'inventory' }) },
});
/** @type {__VLS_StyleScopedClasses['nav-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['active']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.em, __VLS_intrinsics.em)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    ...{ onClick: (...[$event]) => {
            return (__VLS_ctx.switchView('containers'));
            // @ts-ignore
            [switchView, currentView,];
        } },
    ...{ class: "nav-btn" },
    ...{ class: ({ active: __VLS_ctx.currentView === 'containers' }) },
});
/** @type {__VLS_StyleScopedClasses['nav-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['active']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.em, __VLS_intrinsics.em)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    ...{ onClick: (...[$event]) => {
            return (__VLS_ctx.switchView('assistant'));
            // @ts-ignore
            [switchView, currentView,];
        } },
    ...{ class: "nav-btn" },
    ...{ class: ({ active: __VLS_ctx.currentView === 'assistant' }) },
});
/** @type {__VLS_StyleScopedClasses['nav-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['active']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.em, __VLS_intrinsics.em)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    ...{ onClick: (...[$event]) => {
            return (__VLS_ctx.switchView('settings'));
            // @ts-ignore
            [switchView, currentView,];
        } },
    ...{ class: "nav-btn" },
    ...{ class: ({ active: __VLS_ctx.currentView === 'settings' }) },
});
/** @type {__VLS_StyleScopedClasses['nav-btn']} */ ;
/** @type {__VLS_StyleScopedClasses['active']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.em, __VLS_intrinsics.em)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "sidebar-foot" },
});
/** @type {__VLS_StyleScopedClasses['sidebar-foot']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "status-dot" },
});
/** @type {__VLS_StyleScopedClasses['status-dot']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.main, __VLS_intrinsics.main)({
    ...{ class: "main" },
});
/** @type {__VLS_StyleScopedClasses['main']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.header, __VLS_intrinsics.header)({
    ...{ class: "topbar" },
});
/** @type {__VLS_StyleScopedClasses['topbar']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
    ...{ class: "eyebrow" },
});
/** @type {__VLS_StyleScopedClasses['eyebrow']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.h1, __VLS_intrinsics.h1)({});
(__VLS_ctx.pageTitle);
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "top-actions" },
});
/** @type {__VLS_StyleScopedClasses['top-actions']} */ ;
if (__VLS_ctx.deferredPrompt) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: (__VLS_ctx.installApp) },
        ...{ class: "ghost-btn" },
    });
    /** @type {__VLS_StyleScopedClasses['ghost-btn']} */ ;
}
if (__VLS_ctx.currentView !== 'settings') {
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.currentView !== 'settings'))
                    throw 0;
                return (__VLS_ctx.openAddItem());
                // @ts-ignore
                [currentView, currentView, pageTitle, deferredPrompt, installApp, openAddItem,];
            } },
        ...{ class: "primary-btn" },
    });
    /** @type {__VLS_StyleScopedClasses['primary-btn']} */ ;
}
__VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
    ...{ class: "view" },
    ...{ class: ({ active: __VLS_ctx.currentView === 'inventory' }) },
});
/** @type {__VLS_StyleScopedClasses['view']} */ ;
/** @type {__VLS_StyleScopedClasses['active']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "hero-card" },
});
/** @type {__VLS_StyleScopedClasses['hero-card']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "hero-copy" },
});
/** @type {__VLS_StyleScopedClasses['hero-copy']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
    ...{ class: "pill" },
});
/** @type {__VLS_StyleScopedClasses['pill']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.h2, __VLS_intrinsics.h2)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    ...{ onClick: (...[$event]) => {
            return (__VLS_ctx.switchView('assistant'));
            // @ts-ignore
            [switchView, currentView,];
        } },
    ...{ class: "hero-ai" },
});
/** @type {__VLS_StyleScopedClasses['hero-ai']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "stats-grid" },
});
/** @type {__VLS_StyleScopedClasses['stats-grid']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.article, __VLS_intrinsics.article)({
    ...{ class: "stat" },
});
/** @type {__VLS_StyleScopedClasses['stat']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
(__VLS_ctx.summary.containers);
__VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.article, __VLS_intrinsics.article)({
    ...{ class: "stat" },
});
/** @type {__VLS_StyleScopedClasses['stat']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
(__VLS_ctx.summary.items);
__VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.article, __VLS_intrinsics.article)({
    ...{ class: "stat" },
});
/** @type {__VLS_StyleScopedClasses['stat']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
(__VLS_ctx.summary.quantity);
__VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.article, __VLS_intrinsics.article)({
    ...{ class: "stat" },
});
/** @type {__VLS_StyleScopedClasses['stat']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.strong, __VLS_intrinsics.strong)({});
(__VLS_ctx.summary.special);
__VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "toolbar storage-toolbar" },
});
/** @type {__VLS_StyleScopedClasses['toolbar']} */ ;
/** @type {__VLS_StyleScopedClasses['storage-toolbar']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({
    ...{ class: "search-box" },
});
/** @type {__VLS_StyleScopedClasses['search-box']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.input)({
    ...{ onInput: (__VLS_ctx.onSearchInput) },
    placeholder: "搜索物品、标签、状态或箱子…",
    autocomplete: "off",
});
(__VLS_ctx.search);
__VLS_asFunctionalElement1(__VLS_intrinsics.kbd, __VLS_intrinsics.kbd)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    ...{ onClick: (__VLS_ctx.clearSearch) },
    ...{ class: "outline-btn" },
});
/** @type {__VLS_StyleScopedClasses['outline-btn']} */ ;
if (!__VLS_ctx.searching) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "section-head" },
    });
    /** @type {__VLS_StyleScopedClasses['section-head']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.h3, __VLS_intrinsics.h3)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "privacy-hint" },
    });
    /** @type {__VLS_StyleScopedClasses['privacy-hint']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "containers-grid" },
    });
    /** @type {__VLS_StyleScopedClasses['containers-grid']} */ ;
    for (const [c] of __VLS_vFor((__VLS_ctx.containers))) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.article, __VLS_intrinsics.article)({
            ...{ onClick: (...[$event]) => {
                    if (!(!__VLS_ctx.searching))
                        throw 0;
                    return (__VLS_ctx.openContainerDetail(c.id));
                    // @ts-ignore
                    [summary, summary, summary, summary, onSearchInput, search, clearSearch, searching, containers, openContainerDetail,];
                } },
            ...{ onKeydown: (...[$event]) => {
                    if (!(!__VLS_ctx.searching))
                        throw 0;
                    return (__VLS_ctx.openContainerDetail(c.id));
                    // @ts-ignore
                    [openContainerDetail,];
                } },
            ...{ onKeydown: (...[$event]) => {
                    if (!(!__VLS_ctx.searching))
                        throw 0;
                    return (__VLS_ctx.openContainerDetail(c.id));
                    // @ts-ignore
                    [openContainerDetail,];
                } },
            key: (c.id),
            ...{ class: "container-card" },
            role: "button",
            tabindex: "0",
        });
        /** @type {__VLS_StyleScopedClasses['container-card']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "container-card-top" },
        });
        /** @type {__VLS_StyleScopedClasses['container-card-top']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "box-mark" },
        });
        /** @type {__VLS_StyleScopedClasses['box-mark']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
            ...{ class: "box-closed" },
        });
        /** @type {__VLS_StyleScopedClasses['box-closed']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.h4, __VLS_intrinsics.h4)({});
        (c.name);
        __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
        (c.notes || '收纳位置');
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "container-bottom" },
        });
        /** @type {__VLS_StyleScopedClasses['container-bottom']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
            ...{ class: "container-count" },
        });
        /** @type {__VLS_StyleScopedClasses['container-count']} */ ;
        (c.item_count);
        (c.quantity_sum);
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
            ...{ class: "container-open" },
        });
        /** @type {__VLS_StyleScopedClasses['container-open']} */ ;
        // @ts-ignore
        [];
    }
}
else {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "section-head" },
    });
    /** @type {__VLS_StyleScopedClasses['section-head']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.h3, __VLS_intrinsics.h3)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
    (__VLS_ctx.searchResults.length);
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: (__VLS_ctx.clearSearch) },
        ...{ class: "text-btn" },
    });
    /** @type {__VLS_StyleScopedClasses['text-btn']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "items-grid" },
    });
    /** @type {__VLS_StyleScopedClasses['items-grid']} */ ;
    for (const [item] of __VLS_vFor((__VLS_ctx.searchResults))) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.article, __VLS_intrinsics.article)({
            key: (item.id),
            ...{ class: "item-card" },
        });
        /** @type {__VLS_StyleScopedClasses['item-card']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "item-top" },
        });
        /** @type {__VLS_StyleScopedClasses['item-top']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "item-icon" },
        });
        /** @type {__VLS_StyleScopedClasses['item-icon']} */ ;
        (__VLS_ctx.firstChar(item.name));
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "item-actions" },
        });
        /** @type {__VLS_StyleScopedClasses['item-actions']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.searching))
                        throw 0;
                    return (__VLS_ctx.openEditItem(item));
                    // @ts-ignore
                    [clearSearch, searchResults, searchResults, firstChar, openEditItem,];
                } },
            ...{ class: "icon-btn" },
            title: "编辑",
        });
        /** @type {__VLS_StyleScopedClasses['icon-btn']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.searching))
                        throw 0;
                    return (__VLS_ctx.deleteItem(item));
                    // @ts-ignore
                    [deleteItem,];
                } },
            ...{ class: "icon-btn danger" },
            title: "删除",
        });
        /** @type {__VLS_StyleScopedClasses['icon-btn']} */ ;
        /** @type {__VLS_StyleScopedClasses['danger']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.h4, __VLS_intrinsics.h4)({});
        (item.name);
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "location" },
        });
        /** @type {__VLS_StyleScopedClasses['location']} */ ;
        (item.container_name);
        if (item.notes) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
                ...{ class: "item-notes" },
                title: (item.notes),
            });
            /** @type {__VLS_StyleScopedClasses['item-notes']} */ ;
            (item.notes);
        }
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "item-meta" },
        });
        /** @type {__VLS_StyleScopedClasses['item-meta']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
            ...{ class: "chip" },
        });
        /** @type {__VLS_StyleScopedClasses['chip']} */ ;
        (__VLS_ctx.quantityText(item));
        if (item.condition && item.condition !== '正常') {
            __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
                ...{ class: "chip bad" },
            });
            /** @type {__VLS_StyleScopedClasses['chip']} */ ;
            /** @type {__VLS_StyleScopedClasses['bad']} */ ;
            (item.condition);
        }
        for (const [tag] of __VLS_vFor((item.tags.split(',').filter(Boolean).slice(0, 2)))) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
                key: (tag),
                ...{ class: "chip" },
            });
            /** @type {__VLS_StyleScopedClasses['chip']} */ ;
            (tag.trim());
            // @ts-ignore
            [quantityText,];
        }
        // @ts-ignore
        [];
    }
    if (__VLS_ctx.searchResults.length === 0) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "empty" },
        });
        /** @type {__VLS_StyleScopedClasses['empty']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "empty-icon" },
        });
        /** @type {__VLS_StyleScopedClasses['empty-icon']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.h3, __VLS_intrinsics.h3)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
    }
}
__VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
    ...{ class: "view" },
    ...{ class: ({ active: __VLS_ctx.currentView === 'containers' }) },
});
/** @type {__VLS_StyleScopedClasses['view']} */ ;
/** @type {__VLS_StyleScopedClasses['active']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "section-head row-mobile" },
});
/** @type {__VLS_StyleScopedClasses['section-head']} */ ;
/** @type {__VLS_StyleScopedClasses['row-mobile']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.h3, __VLS_intrinsics.h3)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    ...{ onClick: (__VLS_ctx.openAddContainer) },
    ...{ class: "primary-btn" },
});
/** @type {__VLS_StyleScopedClasses['primary-btn']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "containers-grid" },
});
/** @type {__VLS_StyleScopedClasses['containers-grid']} */ ;
for (const [c] of __VLS_vFor((__VLS_ctx.containers))) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.article, __VLS_intrinsics.article)({
        ...{ onClick: (...[$event]) => {
                return (__VLS_ctx.openContainerDetail(c.id));
                // @ts-ignore
                [currentView, containers, openContainerDetail, searchResults, openAddContainer,];
            } },
        ...{ onKeydown: (...[$event]) => {
                return (__VLS_ctx.openContainerDetail(c.id));
                // @ts-ignore
                [openContainerDetail,];
            } },
        ...{ onKeydown: (...[$event]) => {
                return (__VLS_ctx.openContainerDetail(c.id));
                // @ts-ignore
                [openContainerDetail,];
            } },
        key: (c.id),
        ...{ class: "container-card" },
        role: "button",
        tabindex: "0",
    });
    /** @type {__VLS_StyleScopedClasses['container-card']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "container-card-top" },
    });
    /** @type {__VLS_StyleScopedClasses['container-card-top']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "box-mark" },
    });
    /** @type {__VLS_StyleScopedClasses['box-mark']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "box-closed" },
    });
    /** @type {__VLS_StyleScopedClasses['box-closed']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.h4, __VLS_intrinsics.h4)({});
    (c.name);
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
    (c.notes || '收纳位置');
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "container-bottom" },
    });
    /** @type {__VLS_StyleScopedClasses['container-bottom']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "container-count" },
    });
    /** @type {__VLS_StyleScopedClasses['container-count']} */ ;
    (c.item_count);
    (c.quantity_sum);
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "container-open" },
    });
    /** @type {__VLS_StyleScopedClasses['container-open']} */ ;
    // @ts-ignore
    [];
}
__VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
    ...{ class: "view assistant-layout" },
    ...{ class: ({ active: __VLS_ctx.currentView === 'assistant' }) },
});
/** @type {__VLS_StyleScopedClasses['view']} */ ;
/** @type {__VLS_StyleScopedClasses['assistant-layout']} */ ;
/** @type {__VLS_StyleScopedClasses['active']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "chat-panel" },
});
/** @type {__VLS_StyleScopedClasses['chat-panel']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "chat-head" },
});
/** @type {__VLS_StyleScopedClasses['chat-head']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "ai-avatar" },
});
/** @type {__VLS_StyleScopedClasses['ai-avatar']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.h3, __VLS_intrinsics.h3)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
    ...{ class: "mini-dot" },
    ...{ style: ({ background: __VLS_ctx.aiSettings.has_api_key ? '#22c55e' : '#f59e0b' }) },
});
/** @type {__VLS_StyleScopedClasses['mini-dot']} */ ;
(__VLS_ctx.aiSettings.has_api_key ? 'AI 接口已配置' : '本地检索模式');
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "chat-messages" },
});
/** @type {__VLS_StyleScopedClasses['chat-messages']} */ ;
for (const [message] of __VLS_vFor((__VLS_ctx.chatMessages))) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        key: (message.id),
        ...{ class: "message" },
        ...{ class: (message.role) },
    });
    /** @type {__VLS_StyleScopedClasses['message']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "bubble" },
    });
    /** @type {__VLS_StyleScopedClasses['bubble']} */ ;
    (message.text);
    if (message.action && !message.executed) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "action-card" },
        });
        /** @type {__VLS_StyleScopedClasses['action-card']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
        (__VLS_ctx.describeAction(message.action));
        if (message.error) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
                ...{ class: "action-error" },
            });
            /** @type {__VLS_StyleScopedClasses['action-error']} */ ;
            (message.error);
        }
        __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
            ...{ onClick: (...[$event]) => {
                    if (!(message.action && !message.executed))
                        throw 0;
                    return (__VLS_ctx.executeAction(message));
                    // @ts-ignore
                    [currentView, aiSettings, aiSettings, chatMessages, describeAction, executeAction,];
                } },
        });
    }
    // @ts-ignore
    [];
}
if (__VLS_ctx.chatBusy) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "message assistant" },
    });
    /** @type {__VLS_StyleScopedClasses['message']} */ ;
    /** @type {__VLS_StyleScopedClasses['assistant']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "bubble" },
    });
    /** @type {__VLS_StyleScopedClasses['bubble']} */ ;
}
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "suggestions" },
});
/** @type {__VLS_StyleScopedClasses['suggestions']} */ ;
for (const [suggestion] of __VLS_vFor((__VLS_ctx.chatSuggestions))) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: (...[$event]) => {
                return (__VLS_ctx.sendChat(suggestion));
                // @ts-ignore
                [chatBusy, chatSuggestions, sendChat,];
            } },
        key: (suggestion),
    });
    (suggestion);
    // @ts-ignore
    [];
}
__VLS_asFunctionalElement1(__VLS_intrinsics.form, __VLS_intrinsics.form)({
    ...{ onSubmit: (...[$event]) => {
            return (__VLS_ctx.sendChat());
            // @ts-ignore
            [sendChat,];
        } },
    ...{ class: "chat-input-wrap" },
});
/** @type {__VLS_StyleScopedClasses['chat-input-wrap']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.textarea, __VLS_intrinsics.textarea)({
    ...{ onKeydown: (...[$event]) => {
            return (__VLS_ctx.sendChat());
            // @ts-ignore
            [sendChat,];
        } },
    value: (__VLS_ctx.chatInput),
    rows: "1",
    placeholder: "问物品在哪里，或让 AI 添加 / 移动 / 修改…",
});
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    type: "submit",
    ...{ class: "send-btn" },
});
/** @type {__VLS_StyleScopedClasses['send-btn']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
    ...{ class: "chat-hint" },
});
/** @type {__VLS_StyleScopedClasses['chat-hint']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.aside, __VLS_intrinsics.aside)({
    ...{ class: "assistant-side" },
});
/** @type {__VLS_StyleScopedClasses['assistant-side']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.h4, __VLS_intrinsics.h4)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "prompt-card" },
});
/** @type {__VLS_StyleScopedClasses['prompt-card']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.b, __VLS_intrinsics.b)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "prompt-card" },
});
/** @type {__VLS_StyleScopedClasses['prompt-card']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.b, __VLS_intrinsics.b)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "prompt-card" },
});
/** @type {__VLS_StyleScopedClasses['prompt-card']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.b, __VLS_intrinsics.b)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "prompt-card" },
});
/** @type {__VLS_StyleScopedClasses['prompt-card']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.b, __VLS_intrinsics.b)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "side-note" },
});
/** @type {__VLS_StyleScopedClasses['side-note']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.section, __VLS_intrinsics.section)({
    ...{ class: "view" },
    ...{ class: ({ active: __VLS_ctx.currentView === 'settings' }) },
});
/** @type {__VLS_StyleScopedClasses['view']} */ ;
/** @type {__VLS_StyleScopedClasses['active']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "settings-card" },
});
/** @type {__VLS_StyleScopedClasses['settings-card']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "settings-title" },
});
/** @type {__VLS_StyleScopedClasses['settings-title']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "settings-icon" },
});
/** @type {__VLS_StyleScopedClasses['settings-icon']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.h3, __VLS_intrinsics.h3)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.form, __VLS_intrinsics.form)({
    ...{ onSubmit: (__VLS_ctx.saveSettings) },
    ...{ class: "settings-form" },
});
/** @type {__VLS_StyleScopedClasses['settings-form']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.input)({
    placeholder: "http://192.168.3.101:3001/v1",
});
(__VLS_ctx.aiSettings.base_url);
__VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "password-wrap" },
});
/** @type {__VLS_StyleScopedClasses['password-wrap']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.input)({
    type: (__VLS_ctx.showApiKey ? 'text' : 'password'),
    placeholder: "已保存时无需重复填写",
});
(__VLS_ctx.apiKeyInput);
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    ...{ onClick: (...[$event]) => {
            return (__VLS_ctx.showApiKey = !__VLS_ctx.showApiKey);
            // @ts-ignore
            [currentView, aiSettings, chatInput, saveSettings, showApiKey, showApiKey, showApiKey, apiKeyInput,];
        } },
    type: "button",
});
(__VLS_ctx.showApiKey ? '隐藏' : '显示');
__VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({
    ...{ style: ({ color: __VLS_ctx.aiSettings.has_api_key ? '#16a34a' : '#98a2b3' }) },
});
(__VLS_ctx.aiSettings.has_api_key ? 'API Key 已保存在本机服务器' : '尚未保存 API Key');
__VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.input)({
    placeholder: "auto",
});
(__VLS_ctx.aiSettings.model);
__VLS_asFunctionalElement1(__VLS_intrinsics.small, __VLS_intrinsics.small)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "settings-actions" },
});
/** @type {__VLS_StyleScopedClasses['settings-actions']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    type: "submit",
    ...{ class: "primary-btn" },
});
/** @type {__VLS_StyleScopedClasses['primary-btn']} */ ;
if (__VLS_ctx.settingsSaved) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({});
}
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "settings-card" },
});
/** @type {__VLS_StyleScopedClasses['settings-card']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "settings-title" },
});
/** @type {__VLS_StyleScopedClasses['settings-title']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "settings-icon" },
});
/** @type {__VLS_StyleScopedClasses['settings-icon']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.h3, __VLS_intrinsics.h3)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "lan-help mq-status-box" },
});
/** @type {__VLS_StyleScopedClasses['lan-help']} */ ;
/** @type {__VLS_StyleScopedClasses['mq-status-box']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
    ...{ class: "mini-dot" },
    ...{ style: ({ background: !__VLS_ctx.mqStatus.enabled ? '#98a2b3' : __VLS_ctx.mqStatus.connected ? '#22c55e' : '#f59e0b' }) },
});
/** @type {__VLS_StyleScopedClasses['mini-dot']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.b, __VLS_intrinsics.b)({});
(!__VLS_ctx.mqStatus.enabled ? '已禁用' : __VLS_ctx.mqStatus.connected ? 'RabbitMQ 已连接' : 'RabbitMQ 未连接（主功能不受影响）');
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.code, __VLS_intrinsics.code)({});
(__VLS_ctx.mqStatus.url || '—');
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.code, __VLS_intrinsics.code)({});
(__VLS_ctx.mqStatus.exchange || '—');
__VLS_asFunctionalElement1(__VLS_intrinsics.code, __VLS_intrinsics.code)({});
(__VLS_ctx.mqStatus.queue || '—');
if (__VLS_ctx.mqStatus.last_error) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
    (__VLS_ctx.mqStatus.last_error);
}
__VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
    ...{ onClick: (__VLS_ctx.refreshMqStatus) },
    type: "button",
    ...{ class: "outline-btn" },
});
/** @type {__VLS_StyleScopedClasses['outline-btn']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "settings-card" },
});
/** @type {__VLS_StyleScopedClasses['settings-card']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "settings-title" },
});
/** @type {__VLS_StyleScopedClasses['settings-title']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "settings-icon" },
});
/** @type {__VLS_StyleScopedClasses['settings-icon']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.h3, __VLS_intrinsics.h3)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "lan-help" },
});
/** @type {__VLS_StyleScopedClasses['lan-help']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.code, __VLS_intrinsics.code)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.code, __VLS_intrinsics.code)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.code, __VLS_intrinsics.code)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "settings-card warning-card" },
});
/** @type {__VLS_StyleScopedClasses['settings-card']} */ ;
/** @type {__VLS_StyleScopedClasses['warning-card']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "settings-title" },
});
/** @type {__VLS_StyleScopedClasses['settings-title']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "settings-icon" },
});
/** @type {__VLS_StyleScopedClasses['settings-icon']} */ ;
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.h3, __VLS_intrinsics.h3)({});
__VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
if (__VLS_ctx.itemModalOpen) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.itemModalOpen))
                    throw 0;
                return (__VLS_ctx.itemModalOpen = false);
                // @ts-ignore
                [aiSettings, aiSettings, aiSettings, showApiKey, settingsSaved, mqStatus, mqStatus, mqStatus, mqStatus, mqStatus, mqStatus, mqStatus, mqStatus, mqStatus, refreshMqStatus, itemModalOpen, itemModalOpen,];
            } },
        ...{ class: "modal-backdrop" },
    });
    /** @type {__VLS_StyleScopedClasses['modal-backdrop']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "modal" },
        role: "dialog",
        'aria-modal': "true",
    });
    /** @type {__VLS_StyleScopedClasses['modal']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "modal-head" },
    });
    /** @type {__VLS_StyleScopedClasses['modal-head']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
        ...{ class: "eyebrow" },
    });
    /** @type {__VLS_StyleScopedClasses['eyebrow']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.h3, __VLS_intrinsics.h3)({});
    (__VLS_ctx.itemEditingId ? '编辑物品' : '添加物品');
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.itemModalOpen))
                    throw 0;
                return (__VLS_ctx.itemModalOpen = false);
                // @ts-ignore
                [itemModalOpen, itemEditingId,];
            } },
        ...{ class: "icon-btn" },
    });
    /** @type {__VLS_StyleScopedClasses['icon-btn']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.form, __VLS_intrinsics.form)({
        ...{ onSubmit: (__VLS_ctx.saveItem) },
        ...{ class: "modal-form" },
    });
    /** @type {__VLS_StyleScopedClasses['modal-form']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({
        ...{ class: "span-2" },
    });
    /** @type {__VLS_StyleScopedClasses['span-2']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
        required: true,
        placeholder: "例如：HDMI 线",
    });
    (__VLS_ctx.itemForm.name);
    __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.select, __VLS_intrinsics.select)({
        value: (__VLS_ctx.itemForm.container_id),
        required: true,
    });
    for (const [c] of __VLS_vFor((__VLS_ctx.containers))) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.option, __VLS_intrinsics.option)({
            key: (c.id),
            value: (c.id),
        });
        (c.name);
        // @ts-ignore
        [containers, saveItem, itemForm, itemForm,];
    }
    __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
        type: "number",
        min: "0",
        placeholder: "1",
    });
    (__VLS_ctx.itemForm.quantity);
    __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
        placeholder: "例如：一些 / 很多",
    });
    (__VLS_ctx.itemForm.quantity_text);
    __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
        placeholder: "正常 / 损坏 / 没电",
    });
    (__VLS_ctx.itemForm.condition);
    __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({
        ...{ class: "span-2" },
    });
    /** @type {__VLS_StyleScopedClasses['span-2']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
        placeholder: "电脑, HDMI, 线材",
    });
    (__VLS_ctx.itemForm.tags);
    __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({
        ...{ class: "span-2" },
    });
    /** @type {__VLS_StyleScopedClasses['span-2']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.textarea, __VLS_intrinsics.textarea)({
        value: (__VLS_ctx.itemForm.notes),
        rows: "3",
        placeholder: "记录更具体的位置、用途或状态…",
    });
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "modal-actions span-2" },
    });
    /** @type {__VLS_StyleScopedClasses['modal-actions']} */ ;
    /** @type {__VLS_StyleScopedClasses['span-2']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.itemModalOpen))
                    throw 0;
                return (__VLS_ctx.itemModalOpen = false);
                // @ts-ignore
                [itemModalOpen, itemForm, itemForm, itemForm, itemForm, itemForm,];
            } },
        type: "button",
        ...{ class: "outline-btn" },
    });
    /** @type {__VLS_StyleScopedClasses['outline-btn']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        type: "submit",
        ...{ class: "primary-btn" },
    });
    /** @type {__VLS_StyleScopedClasses['primary-btn']} */ ;
}
if (__VLS_ctx.containerModalOpen) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.containerModalOpen))
                    throw 0;
                return (__VLS_ctx.containerModalOpen = false);
                // @ts-ignore
                [containerModalOpen, containerModalOpen,];
            } },
        ...{ class: "modal-backdrop" },
    });
    /** @type {__VLS_StyleScopedClasses['modal-backdrop']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "modal small" },
        role: "dialog",
        'aria-modal': "true",
    });
    /** @type {__VLS_StyleScopedClasses['modal']} */ ;
    /** @type {__VLS_StyleScopedClasses['small']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "modal-head" },
    });
    /** @type {__VLS_StyleScopedClasses['modal-head']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
        ...{ class: "eyebrow" },
    });
    /** @type {__VLS_StyleScopedClasses['eyebrow']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.h3, __VLS_intrinsics.h3)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.containerModalOpen))
                    throw 0;
                return (__VLS_ctx.containerModalOpen = false);
                // @ts-ignore
                [containerModalOpen,];
            } },
        ...{ class: "icon-btn" },
    });
    /** @type {__VLS_StyleScopedClasses['icon-btn']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.form, __VLS_intrinsics.form)({
        ...{ onSubmit: (__VLS_ctx.saveContainer) },
        ...{ class: "modal-form one-col" },
    });
    /** @type {__VLS_StyleScopedClasses['modal-form']} */ ;
    /** @type {__VLS_StyleScopedClasses['one-col']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.input)({
        required: true,
        placeholder: "例如：黑色工具箱",
    });
    (__VLS_ctx.containerForm.name);
    __VLS_asFunctionalElement1(__VLS_intrinsics.label, __VLS_intrinsics.label)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.textarea, __VLS_intrinsics.textarea)({
        value: (__VLS_ctx.containerForm.notes),
        rows: "3",
        placeholder: "可选",
    });
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "modal-actions" },
    });
    /** @type {__VLS_StyleScopedClasses['modal-actions']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.containerModalOpen))
                    throw 0;
                return (__VLS_ctx.containerModalOpen = false);
                // @ts-ignore
                [containerModalOpen, saveContainer, containerForm, containerForm,];
            } },
        type: "button",
        ...{ class: "outline-btn" },
    });
    /** @type {__VLS_StyleScopedClasses['outline-btn']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        type: "submit",
        ...{ class: "primary-btn" },
    });
    /** @type {__VLS_StyleScopedClasses['primary-btn']} */ ;
}
if (__VLS_ctx.openContainer) {
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ onClick: (__VLS_ctx.closeContainerDetail) },
        ...{ class: "modal-backdrop container-detail-backdrop" },
    });
    /** @type {__VLS_StyleScopedClasses['modal-backdrop']} */ ;
    /** @type {__VLS_StyleScopedClasses['container-detail-backdrop']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "modal container-detail-modal" },
        role: "dialog",
        'aria-modal': "true",
    });
    /** @type {__VLS_StyleScopedClasses['modal']} */ ;
    /** @type {__VLS_StyleScopedClasses['container-detail-modal']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "container-detail-head" },
    });
    /** @type {__VLS_StyleScopedClasses['container-detail-head']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "container-detail-title-wrap" },
    });
    /** @type {__VLS_StyleScopedClasses['container-detail-title-wrap']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "box-mark large" },
    });
    /** @type {__VLS_StyleScopedClasses['box-mark']} */ ;
    /** @type {__VLS_StyleScopedClasses['large']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({});
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
        ...{ class: "eyebrow" },
    });
    /** @type {__VLS_StyleScopedClasses['eyebrow']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.h3, __VLS_intrinsics.h3)({});
    (__VLS_ctx.openContainer.name);
    __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({
        ...{ class: "container-detail-note" },
    });
    /** @type {__VLS_StyleScopedClasses['container-detail-note']} */ ;
    (__VLS_ctx.openContainer.notes || '收纳位置');
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: (__VLS_ctx.closeContainerDetail) },
        ...{ class: "icon-btn" },
    });
    /** @type {__VLS_StyleScopedClasses['icon-btn']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
        ...{ class: "container-detail-toolbar" },
    });
    /** @type {__VLS_StyleScopedClasses['container-detail-toolbar']} */ ;
    __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
        ...{ class: "container-count" },
    });
    /** @type {__VLS_StyleScopedClasses['container-count']} */ ;
    (__VLS_ctx.openContainer.item_count);
    (__VLS_ctx.openContainer.quantity_sum);
    __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
        ...{ onClick: (...[$event]) => {
                if (!(__VLS_ctx.openContainer))
                    throw 0;
                return (__VLS_ctx.openAddItem(__VLS_ctx.openContainer.id));
                // @ts-ignore
                [openAddItem, openContainer, openContainer, openContainer, openContainer, openContainer, openContainer, closeContainerDetail, closeContainerDetail,];
            } },
        ...{ class: "primary-btn compact" },
    });
    /** @type {__VLS_StyleScopedClasses['primary-btn']} */ ;
    /** @type {__VLS_StyleScopedClasses['compact']} */ ;
    if (__VLS_ctx.openContainerItems.length) {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "container-detail-items" },
        });
        /** @type {__VLS_StyleScopedClasses['container-detail-items']} */ ;
        for (const [item] of __VLS_vFor((__VLS_ctx.openContainerItems))) {
            __VLS_asFunctionalElement1(__VLS_intrinsics.article, __VLS_intrinsics.article)({
                key: (item.id),
                ...{ class: "container-item-row" },
            });
            /** @type {__VLS_StyleScopedClasses['container-item-row']} */ ;
            __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
                ...{ class: "container-item-icon" },
            });
            /** @type {__VLS_StyleScopedClasses['container-item-icon']} */ ;
            (__VLS_ctx.firstChar(item.name));
            __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
                ...{ class: "container-item-main" },
            });
            /** @type {__VLS_StyleScopedClasses['container-item-main']} */ ;
            __VLS_asFunctionalElement1(__VLS_intrinsics.h4, __VLS_intrinsics.h4)({});
            (item.name);
            if (item.notes) {
                __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
                (item.notes);
            }
            __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
                ...{ class: "item-meta" },
            });
            /** @type {__VLS_StyleScopedClasses['item-meta']} */ ;
            __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
                ...{ class: "chip" },
            });
            /** @type {__VLS_StyleScopedClasses['chip']} */ ;
            (__VLS_ctx.quantityText(item));
            if (item.condition && item.condition !== '正常') {
                __VLS_asFunctionalElement1(__VLS_intrinsics.span, __VLS_intrinsics.span)({
                    ...{ class: "chip bad" },
                });
                /** @type {__VLS_StyleScopedClasses['chip']} */ ;
                /** @type {__VLS_StyleScopedClasses['bad']} */ ;
                (item.condition);
            }
            __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
                ...{ class: "container-item-actions" },
            });
            /** @type {__VLS_StyleScopedClasses['container-item-actions']} */ ;
            __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
                ...{ onClick: (...[$event]) => {
                        if (!(__VLS_ctx.openContainer))
                            throw 0;
                        if (!(__VLS_ctx.openContainerItems.length))
                            throw 0;
                        return (__VLS_ctx.openEditItem(item));
                        // @ts-ignore
                        [firstChar, openEditItem, quantityText, openContainerItems, openContainerItems,];
                    } },
                ...{ class: "icon-btn" },
                title: "编辑",
            });
            /** @type {__VLS_StyleScopedClasses['icon-btn']} */ ;
            __VLS_asFunctionalElement1(__VLS_intrinsics.button, __VLS_intrinsics.button)({
                ...{ onClick: (...[$event]) => {
                        if (!(__VLS_ctx.openContainer))
                            throw 0;
                        if (!(__VLS_ctx.openContainerItems.length))
                            throw 0;
                        return (__VLS_ctx.deleteItem(item));
                        // @ts-ignore
                        [deleteItem,];
                    } },
                ...{ class: "icon-btn danger" },
                title: "删除",
            });
            /** @type {__VLS_StyleScopedClasses['icon-btn']} */ ;
            /** @type {__VLS_StyleScopedClasses['danger']} */ ;
            // @ts-ignore
            [];
        }
    }
    else {
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "container-detail-empty" },
        });
        /** @type {__VLS_StyleScopedClasses['container-detail-empty']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
            ...{ class: "empty-icon" },
        });
        /** @type {__VLS_StyleScopedClasses['empty-icon']} */ ;
        __VLS_asFunctionalElement1(__VLS_intrinsics.h3, __VLS_intrinsics.h3)({});
        __VLS_asFunctionalElement1(__VLS_intrinsics.p, __VLS_intrinsics.p)({});
        (__VLS_ctx.openContainer.notes || '这个箱子目前没有物品记录。');
    }
}
__VLS_asFunctionalElement1(__VLS_intrinsics.div, __VLS_intrinsics.div)({
    ...{ class: "toast" },
    ...{ class: ({ show: __VLS_ctx.toastText }) },
});
/** @type {__VLS_StyleScopedClasses['toast']} */ ;
/** @type {__VLS_StyleScopedClasses['show']} */ ;
(__VLS_ctx.toastText);
// @ts-ignore
[openContainer, toastText, toastText,];
const __VLS_export = (await import('vue')).defineComponent({});
export default {};
