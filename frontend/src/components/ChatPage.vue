<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { api, streamRun } from '../api.js';
import UiIcon from './UiIcon.vue';
import DatabaseOperationCard from './DatabaseOperationCard.vue';
import MessageContent from './MessageContent.vue';
import StatusLabel from './StatusLabel.vue';

const props = defineProps({ sessionId: String });
const emit = defineEmits(['new-session', 'session-updated']);
const messages = ref([]), models = ref([]), selectedModel = ref(''), text = ref(''), mode = ref('CHAT');
const artifacts = ref([]), error = ref(''), streaming = ref(''), run = ref(null), events = ref([]), job = ref(null), busy = ref(false), intent = ref('');
const conversationEl = ref(null), memoryContext = ref(null), recallInfo = ref(null);
let controller = null, latest = 0;
const active = computed(() => run.value && ['queued', 'running', 'waiting_confirmation', 'cancel_requested'].includes(run.value.status));
const timeline = computed(() => [
  ...messages.value.map(message => ({ key: `message:${message.id}`, kind: 'message', createdAt: message.createdAt, message })),
  ...artifacts.value.map(artifact => ({ key: `artifact:${artifact.id}`, kind: 'artifact', createdAt: artifact.createdAt, artifact }))
].sort((left, right) => left.createdAt - right.createdAt || left.key.localeCompare(right.key)));
const modeInfo = computed(() => mode.value === 'CHAT'
  ? { title: 'Chat 对话链路', detail: '允许对话与明确标记的只读工具，不会修改数据。', className: 'chat' }
  : { title: 'Build 执行链路', detail: '允许调度工具与 Skill，数据变更必须人工确认。', className: 'build' });
const activitySteps = computed(() => {
  const grouped = [];
  for (const item of events.value) {
    if (item.type === '工具调用') grouped.push({ title: item.text, entries: [item] });
    else if (['等待确认', '工具结果'].includes(item.type) && grouped.length && grouped[grouped.length - 1].entries.some(entry => entry.type === '工具调用')) grouped[grouped.length - 1].entries.push(item);
    else grouped.push({ title: item.type, entries: [item] });
  }
  return grouped;
});
const sessionSummaryText = computed(() => {
  const raw = memoryContext.value?.sessionSummary;
  if (!raw) return '尚未生成滚动摘要';
  try {
    const value = JSON.parse(raw);
    return [value.currentTopic, value.userGoal].filter(Boolean).join('；') || value.manual || raw;
  } catch { return raw; }
});
function scrollToBottom(behavior = 'smooth') { nextTick(() => conversationEl.value?.scrollTo({ top: conversationEl.value.scrollHeight, behavior })); }

async function load() {
  controller?.abort(); controller = null; latest = 0;
  messages.value = []; artifacts.value = []; events.value = []; streaming.value = ''; job.value = null; run.value = null; error.value = ''; intent.value = ''; recallInfo.value = null;
  try {
    models.value = (await api('/api/models')).filter(item => item.kind === 'CHAT' && item.enabled);
    selectedModel.value = models.value.find(item => item.isDefault)?.id || '';
    if (!props.sessionId) return;
    const [history, historyArtifacts, runs, context] = await Promise.all([api(`/api/sessions/${props.sessionId}/messages`), api(`/api/sessions/${props.sessionId}/artifacts`), api(`/api/sessions/${props.sessionId}/runs`), api(`/api/sessions/${props.sessionId}/memory-context`)]);
    messages.value = history;
    artifacts.value = historyArtifacts;
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
  if (event.type === 'memory_recall') { recallInfo.value = event.payload; events.value.push({ type: '记忆检索', text: event.payload.message || '检索状态未知', code: event.payload.mode }); }
  if (event.type === 'confirmation_required') {
    job.value = event.payload; run.value.status = 'waiting_confirmation'; streaming.value = '';
    events.value.push({ type: '等待确认', text: '高风险操作已暂停', code: 'WAITING_CONFIRMATION' });
    if (!restoring) { refreshMessages(); refreshArtifacts(); }
  }
  if (event.type === 'tool_result') {
    job.value = event.payload; run.value.status = 'running'; events.value.push({ type: '工具结果', text: event.payload.status || '已返回', code: event.payload.status });
    if (!restoring) refreshArtifacts();
  }
  if (['run_completed', 'run_failed', 'run_interrupted', 'run_cancelled'].includes(event.type)) {
    run.value.status = event.type.slice(4);
    if (event.type !== 'run_completed') error.value = event.payload.message || '运行未完成';
    streaming.value = '';
    if (!restoring) { refreshMessages(); refreshArtifacts(); }
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
async function refreshArtifacts() { if (props.sessionId) { try { artifacts.value = await api(`/api/sessions/${props.sessionId}/artifacts`); scrollToBottom(); } catch (e) { error.value = e.message; } } }
async function send() {
  if (!text.value.trim() || !props.sessionId || active.value) return;
  busy.value = true; error.value = ''; streaming.value = ''; events.value = []; job.value = null; intent.value = ''; recallInfo.value = null; latest = 0;
  const value = text.value.trim();
  try {
    run.value = await api(`/api/sessions/${props.sessionId}/runs`, { method: 'POST', body: { message: value, modelId: selectedModel.value || null, mode: mode.value } });
    text.value = ''; await refreshMessages(); subscribe(run.value.id);
  } catch (e) { error.value = e.message; }
  finally { busy.value = false; }
}
async function decide(decision, target = job.value) {
  busy.value = true; error.value = '';
  try {
    const kind = target.action.kind === 'MCP' ? 'mcp-actions' : target.action.kind === 'SKILL_SCRIPT' ? 'skill-script-actions' : 'actions';
    job.value = await api(`/api/${kind}/${target.action.id}/${decision}`, { method: 'POST' });
    await refreshArtifacts();
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
      <template v-for="item in timeline" :key="item.key">
        <div v-if="item.kind === 'message'" class="message" :class="item.message.role"><div class="message-avatar">{{ item.message.role === 'user' ? '你' : 'AW' }}</div><div class="message-bubble"><div class="message-meta"><strong>{{ item.message.role === 'user' ? '你' : 'ZWD-AWDP' }}</strong><span class="mode-label">{{ item.message.mode || 'CHAT' }}</span></div><MessageContent class="message-content" :content="item.message.content" /></div></div>
        <DatabaseOperationCard v-else-if="item.artifact.kind === 'DATABASE'" class="chat-operation-card" :job="item.artifact.payload" :busy="busy" @decide="decision => decide(decision, item.artifact.payload)" />
        <article v-else class="operation-card chat-operation-card" :class="{ 'approval-card': item.artifact.payload.action?.status === 'PENDING', 'result-card': item.artifact.payload.status === 'COMPLETED', 'operation-error': item.artifact.payload.error }">
          <header class="operation-card-header"><div><p class="eyebrow">TOOL ACTIVITY</p><h3>{{ item.artifact.payload.action?.status === 'PENDING' ? '工具调用待确认' : '工具调用结果' }}</h3></div><StatusLabel :code="item.artifact.payload.action?.status || item.artifact.payload.status || 'COMPLETED'" compact /></header>
          <pre>{{ JSON.stringify(item.artifact.payload.action?.operation || item.artifact.payload.result || item.artifact.payload.error, null, 2) }}</pre>
          <div v-if="item.artifact.payload.action?.status === 'PENDING'" class="operation-actions"><button class="primary" :disabled="busy" @click="decide('approve', item.artifact.payload)">确认执行</button><button class="secondary" :disabled="busy" @click="decide('reject', item.artifact.payload)">拒绝</button></div>
        </article>
      </template>
      <div v-if="streaming" class="message assistant"><div class="message-avatar">AW</div><div class="message-bubble streaming-bubble"><div class="message-meta"><strong>ZWD-AWDP</strong><span class="mode-label">{{ mode }}</span><span class="typing"><i></i><i></i><i></i></span></div><MessageContent class="message-content" :content="streaming" /></div></div>
    </div>
    <div v-if="sessionId" class="composer-wrap"><p v-if="error" class="error" role="alert">{{ error }}</p><form class="composer" @submit.prevent="send"><div class="mode-switch" role="group" aria-label="运行模式"><button type="button" :class="{ active: mode === 'CHAT' }" :disabled="active" @click="mode = 'CHAT'"><strong>Chat</strong><span>对话 + 只读</span></button><button type="button" :class="{ active: mode === 'BUILD' }" :disabled="active" @click="mode = 'BUILD'"><strong>Build</strong><span>工具 + Skill</span></button></div><textarea v-model="text" :disabled="active" rows="3" :placeholder="mode === 'CHAT' ? '提问，或请求只读查询…' : '描述需要执行的任务…'" aria-label="输入消息"></textarea><div class="composer-footer"><select v-model="selectedModel" aria-label="选择对话模型"><option value="">使用默认模型</option><option v-for="model in models" :key="model.id" :value="model.id">{{ model.name }}</option></select><button v-if="active" type="button" class="secondary" @click="cancel">取消运行</button><button v-else class="primary" :disabled="busy || !text.trim()">{{ busy ? '提交中…' : '发送' }}</button></div></form><p class="composer-hint">{{ mode === 'CHAT' ? 'Chat 模式不会执行写、改、删操作。' : 'Build 模式的数据变更全部需要人工确认。' }}</p></div>
  </section>
  <aside class="inspector"><div class="inspector-heading"><span>运行状态</span><StatusLabel :code="active ? 'RUNNING' : 'ACTIVE'" compact /></div><div class="inspector-section current-run"><p class="eyebrow">CURRENT RUN</p><div class="run-mode-row"><StatusLabel :code="run ? run.mode : mode" compact /><StatusLabel v-if="intent" :code="intent" compact /></div><StatusLabel v-if="run" :code="run.status" compact /><p v-if="run" class="small mono subdued">{{ run.id }}</p><p v-else class="muted small">发送消息后显示路由与执行进度。</p></div><div v-if="memoryContext" class="inspector-section memory-context"><p class="eyebrow">MEMORY CONTEXT</p><strong>会话记忆</strong><p class="muted small memory-summary-preview">{{ sessionSummaryText }}</p><div class="context-counts"><span>L2 本次 {{ memoryContext.loadedCounts?.l2 ?? memoryContext.scenes.length }} / 有效 {{ memoryContext.activeTotals?.l2 ?? '—' }}</span><span>L3 本次 {{ memoryContext.loadedCounts?.l3 ?? memoryContext.persona.length }} / 有效 {{ memoryContext.activeTotals?.l3 ?? '—' }}</span></div><div class="memory-job-counts"><span>待处理 {{ memoryContext.jobStats?.queued ?? 0 }}</span><span>处理中 {{ memoryContext.jobStats?.running ?? 0 }}</span><span>失败 {{ memoryContext.jobStats?.failed ?? 0 }}</span></div><div class="recall-meta"><StatusLabel :code="recallInfo?.mode || memoryContext.recallMode" compact /><span v-if="recallInfo">命中 {{ recallInfo.count || 0 }} 条（L1 {{ recallInfo.l1 || 0 }} / L2 {{ recallInfo.l2 || 0 }} / L3 {{ recallInfo.l3 || 0 }}）</span><span v-if="recallInfo?.cacheHit">缓存命中</span><span v-if="recallInfo?.degradedReason" class="warning-text">{{ recallInfo.degradedReason }}</span></div></div><div class="inspector-section activity-section"><p class="eyebrow">ACTIVITY</p><div v-if="!activitySteps.length" class="quiet-state">暂无运行事件</div><ol v-else class="activity"><li v-for="(step, index) in activitySteps" :key="index"><span class="activity-dot"></span><details :open="index === activitySteps.length - 1"><summary><strong>{{ step.title }}</strong><span>{{ step.entries.length }} 个事件</span></summary><div v-for="(entry, entryIndex) in step.entries" :key="entryIndex" class="activity-entry"><StatusLabel v-if="entry.code" :code="entry.code" compact /><strong v-else>{{ entry.type }}</strong><p>{{ entry.text }}</p></div></details></li></ol></div><div class="inspector-bottom"><span class="status-dot"></span> 权限由白名单与状态机共同控制</div></aside>
</template>
