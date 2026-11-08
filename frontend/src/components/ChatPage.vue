<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { api, streamRun } from '../api.js';
import UiIcon from './UiIcon.vue';

const props = defineProps({ sessionId: String });
const emit = defineEmits(['new-session', 'session-updated']);
const messages = ref([]), models = ref([]), selectedModel = ref(''), text = ref(''), mode = ref('CHAT');
const error = ref(''), streaming = ref(''), run = ref(null), events = ref([]), job = ref(null), busy = ref(false), intent = ref('');
const conversationEl = ref(null), memoryContext = ref(null);
let controller = null, latest = 0;
const active = computed(() => run.value && ['queued', 'running', 'waiting_confirmation', 'cancel_requested'].includes(run.value.status));
const pending = computed(() => job.value?.action?.status === 'PENDING');
const modeInfo = computed(() => mode.value === 'CHAT'
  ? { title: 'Chat 对话链路', detail: '允许对话与明确标记的只读工具，不会修改数据。', className: 'chat' }
  : { title: 'Build 执行链路', detail: '允许调度工具与 Skill，数据变更必须人工确认。', className: 'build' });
function scrollToBottom(behavior = 'smooth') { nextTick(() => conversationEl.value?.scrollTo({ top: conversationEl.value.scrollHeight, behavior })); }

async function load() {
  controller?.abort(); controller = null; latest = 0;
  messages.value = []; events.value = []; streaming.value = ''; job.value = null; run.value = null; error.value = ''; intent.value = '';
  try {
    models.value = (await api('/api/models')).filter(item => item.kind === 'CHAT' && item.enabled);
    selectedModel.value = models.value.find(item => item.isDefault)?.id || '';
    if (!props.sessionId) return;
    const [history, runs, context] = await Promise.all([api(`/api/sessions/${props.sessionId}/messages`), api(`/api/sessions/${props.sessionId}/runs`), api(`/api/sessions/${props.sessionId}/memory-context`)]);
    messages.value = history;
    memoryContext.value = context;
    const current = runs.find(item => ['queued', 'running', 'waiting_confirmation', 'cancel_requested'].includes(item.status));
    if (current) {
      run.value = current; mode.value = current.mode || 'CHAT';
      const previous = await api(`/api/runs/${current.id}/events`);
      previous.forEach(item => handleEvent({ id: item.seq, type: item.type, payload: item.payload }, true));
      subscribe(current.id);
    }
    scrollToBottom('auto');
  } catch (e) { error.value = e.message; }
}

function handleEvent(event, restoring = false) {
  latest = Math.max(latest, event.id);
  if (event.type === 'run_started') { mode.value = event.payload.mode || mode.value; intent.value = event.payload.intent || ''; events.value.push({ type: '意图路由', text: event.payload.intent || 'CHAT_ONLY' }); }
  if (event.type === 'text_delta' && !restoring) { streaming.value += event.payload.text || ''; scrollToBottom(); }
  if (event.type === 'tool_started') events.value.push({ type: '工具调用', text: event.payload.name || '工具' });
  if (event.type === 'memory_recall') events.value.push({ type: '记忆检索', text: event.payload.message || '检索状态未知' });
  if (event.type === 'confirmation_required') {
    job.value = event.payload; run.value.status = 'waiting_confirmation'; streaming.value = '';
    events.value.push({ type: '等待确认', text: '高风险操作已暂停' });
    if (!restoring) refreshMessages();
  }
  if (event.type === 'tool_result') { job.value = event.payload; run.value.status = 'running'; events.value.push({ type: '工具结果', text: event.payload.status || '已返回' }); }
  if (['run_completed', 'run_failed', 'run_interrupted', 'run_cancelled'].includes(event.type)) {
    run.value.status = event.type.slice(4);
    if (event.type !== 'run_completed') error.value = event.payload.message || '运行未完成';
    streaming.value = '';
    if (!restoring) refreshMessages();
    if (!restoring && event.type === 'run_completed') {
      window.setTimeout(() => emit('session-updated'), 1200);
      window.setTimeout(() => emit('session-updated'), 3500);
    }
  }
}

async function subscribe(id) {
  const local = new AbortController(); controller = local;
  try {
    while (!local.signal.aborted) {
      await streamRun(id, latest, event => { if (controller === local) handleEvent(event); }, local.signal);
      if (controller !== local || local.signal.aborted) break;
      const state = await api(`/api/runs/${id}`); run.value = state;
      if (!['queued', 'running', 'waiting_confirmation', 'cancel_requested'].includes(state.status)) break;
      await new Promise(resolve => setTimeout(resolve, 300));
    }
  } catch (e) { if (e.name !== 'AbortError') error.value = e.message; }
}
async function refreshMessages() { if (props.sessionId) { try { messages.value = await api(`/api/sessions/${props.sessionId}/messages`); scrollToBottom(); } catch (e) { error.value = e.message; } } }
async function send() {
  if (!text.value.trim() || !props.sessionId || active.value) return;
  busy.value = true; error.value = ''; streaming.value = ''; events.value = []; job.value = null; intent.value = ''; latest = 0;
  const value = text.value.trim();
  try {
    run.value = await api(`/api/sessions/${props.sessionId}/runs`, { method: 'POST', body: { message: value, modelId: selectedModel.value || null, mode: mode.value } });
    text.value = ''; await refreshMessages(); subscribe(run.value.id);
  } catch (e) { error.value = e.message; }
  finally { busy.value = false; }
}
async function decide(decision) {
  busy.value = true; error.value = '';
  try {
    const kind = job.value.action.kind === 'MCP' ? 'mcp-actions' : job.value.action.kind === 'SKILL_SCRIPT' ? 'skill-script-actions' : 'actions';
    job.value = await api(`/api/${kind}/${job.value.action.id}/${decision}`, { method: 'POST' });
  } catch (e) { error.value = e.message; }
  finally { busy.value = false; }
}
async function cancel() { try { run.value = await api(`/api/runs/${run.value.id}/cancel`, { method: 'POST' }); } catch (e) { error.value = e.message; } }
watch(() => props.sessionId, load, { immediate: true });
onBeforeUnmount(() => controller?.abort());
</script>

<template>
  <section class="workspace-main">
    <header class="workspace-header"><div><span class="status-dot"></span><strong>对话</strong><span class="header-divider"></span><span class="muted">{{ sessionId ? modeInfo.title : '准备就绪' }}</span></div><span class="badge">LOCAL</span></header>
    <div v-if="!sessionId" class="welcome"><p class="eyebrow">ZWD-AWDP</p><h1>对话与执行，边界清晰。</h1><p>Chat 只回答和读取；Build 才会调度工具、Skill 与确认流程。</p><button class="primary" @click="emit('new-session')"><UiIcon name="plus" :size="17" />创建会话</button><div class="welcome-cards"><div><span>CHAT</span><strong>安全对话</strong><p>允许普通问答与数据库只读命令。</p></div><div><span>BUILD</span><strong>受控执行</strong><p>按白名单路由工具与 Skill。</p></div><div><span>CONFIRM</span><strong>人工决策</strong><p>任何数据变更都暂停并等待确认。</p></div></div></div>
    <div v-else class="mode-notice" :class="modeInfo.className"><span class="mode-notice-mark">{{ mode }}</span><div><strong>{{ modeInfo.title }}</strong><p>{{ modeInfo.detail }}</p></div></div>
    <div v-if="sessionId" ref="conversationEl" class="conversation" aria-live="polite">
      <div v-if="!messages.length && !streaming" class="conversation-empty"><span class="resource-icon large"><UiIcon name="chat" :size="21" /></span><h2>开始新的对话</h2><p>先选择 Chat 或 Build，再描述目标。</p></div>
      <div v-for="message in messages" :key="message.id" class="message" :class="message.role"><div class="message-avatar">{{ message.role === 'user' ? '你' : 'AW' }}</div><div class="message-bubble"><div class="message-meta"><strong>{{ message.role === 'user' ? '你' : 'ZWD-AWDP' }}</strong><span class="mode-label">{{ message.mode || 'CHAT' }}</span></div><div class="message-content">{{ message.content }}</div></div></div>
      <div v-if="streaming" class="message assistant"><div class="message-avatar">AW</div><div class="message-bubble streaming-bubble"><div class="message-meta"><strong>ZWD-AWDP</strong><span class="mode-label">{{ mode }}</span><span class="typing"><i></i><i></i><i></i></span></div><div class="message-content">{{ streaming }}</div></div></div>
      <div v-if="pending" class="approval-card chat-approval"><div class="approval-heading"><div><p class="eyebrow">HUMAN GATE · 高风险操作</p><h3>{{ job.action.kind === 'MCP' ? '外部 MCP 工具调用' : job.action.kind === 'SKILL_SCRIPT' ? '技能脚本运行' : '数据库变更操作' }}</h3></div><span class="status-pill warn">等待确认</span></div><p>运行已暂停。系统不会自动执行，请核对操作内容后决定。</p><div class="approval-facts"><span><small>模式</small><strong>BUILD</strong></span><span><small>操作类型</small><strong>{{ job.action.kind }}</strong></span><span><small>当前状态</small><strong>PENDING</strong></span></div><pre>{{ JSON.stringify(job.action.operation, null, 2) }}</pre><div class="form-actions"><button class="primary" :disabled="busy" @click="decide('approve')">确认并继续</button><button class="secondary" :disabled="busy" @click="decide('reject')">拒绝操作</button></div></div>
    </div>
    <div v-if="sessionId" class="composer-wrap"><p v-if="error" class="error" role="alert">{{ error }}</p><form class="composer" @submit.prevent="send"><div class="mode-switch" role="group" aria-label="运行模式"><button type="button" :class="{ active: mode === 'CHAT' }" :disabled="active" @click="mode = 'CHAT'"><strong>Chat</strong><span>对话 + 只读</span></button><button type="button" :class="{ active: mode === 'BUILD' }" :disabled="active" @click="mode = 'BUILD'"><strong>Build</strong><span>工具 + Skill</span></button></div><textarea v-model="text" :disabled="active" rows="3" :placeholder="mode === 'CHAT' ? '提问，或请求只读查询…' : '描述需要执行的任务…'" aria-label="输入消息"></textarea><div class="composer-footer"><select v-model="selectedModel" aria-label="选择对话模型"><option value="">使用默认模型</option><option v-for="model in models" :key="model.id" :value="model.id">{{ model.name }}</option></select><button v-if="active" type="button" class="secondary" @click="cancel">取消运行</button><button v-else class="primary" :disabled="busy || !text.trim()">{{ busy ? '提交中…' : '发送' }}</button></div></form><p class="composer-hint">{{ mode === 'CHAT' ? 'Chat 模式不会执行写、改、删操作。' : 'Build 模式的数据变更全部需要人工确认。' }}</p></div>
  </section>
  <aside class="inspector"><div class="inspector-heading"><span>运行状态</span><span class="badge">{{ active ? '进行中' : '空闲' }}</span></div><div class="inspector-section"><p class="eyebrow">CURRENT RUN</p><div class="run-mode-row"><strong>{{ run ? run.mode : mode }}</strong><span v-if="intent" class="status-pill">{{ intent }}</span></div><p class="muted small">{{ run ? `状态：${run.status}` : '发送消息后显示路由与执行进度。' }}</p><p v-if="run" class="small mono subdued">{{ run.id }}</p></div><div v-if="memoryContext" class="inspector-section memory-context"><p class="eyebrow">MEMORY CONTEXT</p><strong>分层记忆已加载</strong><p class="muted small">{{ memoryContext.summary }}</p><div class="context-counts"><span>L2 {{ memoryContext.scenes.length }}</span><span>L3 {{ memoryContext.persona.length }}</span><span>队列 {{ memoryContext.pendingJobs }}</span></div></div><div class="inspector-section"><p class="eyebrow">ACTIVITY</p><div v-if="!events.length" class="quiet-state">暂无运行事件</div><ol v-else class="activity"><li v-for="(event, index) in events" :key="index"><span class="activity-dot"></span><div><strong>{{ event.type }}</strong><p>{{ event.text }}</p></div></li></ol></div><div class="inspector-bottom"><span class="status-dot"></span> 权限由白名单与状态机共同控制</div></aside>
</template>
