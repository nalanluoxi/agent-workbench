<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { api } from '../api.js';
const props = defineProps({ path: String });
const id = computed(() => props.path.split('/')[1] || '');
const layer = ref('L1'), search = ref(''), entries = ref([]), sessions = ref([]), jobs = ref([]), error = ref(''), notice = ref(''), busy = ref(false);
const form = reactive({ layer: 'L1', content: '' });
async function load() {
  error.value = '';
  try {
    if (id.value && id.value !== 'new') Object.assign(form, await api(`/api/memories/${id.value}`));
    else if (!id.value) {
      const [all, history, tasks] = await Promise.all([api('/api/memories'), api('/api/sessions'), api('/api/memory-jobs')]);
      entries.value = all; sessions.value = history; jobs.value = tasks;
    }
  } catch (e) { error.value = e.message; }
}
const filtered = computed(() => entries.value.filter(item => item.layer === layer.value && (!search.value || item.content.includes(search.value))));
async function save() { error.value = ''; busy.value = true; try { await api(id.value === 'new' ? '/api/memories' : `/api/memories/${id.value}`, { method: id.value === 'new' ? 'POST' : 'PUT', body: { layer: form.layer, content: form.content } }); location.hash = '#/memory'; } catch (e) { error.value = e.message; } finally { busy.value = false; } }
async function remove(item) { if (!window.confirm('删除此记忆会清理依赖它的场景和画像摘要，且同一来源不会立即重新提取。确认删除吗？')) return; try { await api(`/api/memories/${item.id}`, { method: 'DELETE' }); location.hash = '#/memory'; await load(); } catch (e) { error.value = e.message; } }
async function retry(item) { try { await api(`/api/memory-jobs/${item.runId}/retry`, { method: 'POST' }); notice.value = '已重新提交提取任务'; await load(); } catch (e) { error.value = e.message; } }
onMounted(load);
</script>
<template>
  <main class="content-page"><template v-if="!id"><div class="page-heading"><div><p class="eyebrow">PERSONAL MEMORY</p><h1>记忆管理</h1><p class="muted">查看和维护属于你的对话、事实、场景与画像。</p></div><a class="primary link-button" href="#/memory/new">添加记忆</a></div><p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="notice" class="notice" role="status">{{ notice }}</p><div class="tabs memory-tabs"><button v-for="item in [{id:'L0',label:'L0 对话'},{id:'L1',label:'L1 事实'},{id:'L2',label:'L2 场景'},{id:'L3',label:'L3 画像'}]" :key="item.id" :class="{selected:layer===item.id}" @click="layer=item.id">{{ item.label }}</button></div>
    <template v-if="layer === 'L0'"><div v-if="!sessions.length" class="empty-state"><p>暂无会话记录。</p></div><div v-else class="resource-grid"><article v-for="item in sessions" :key="item.id" class="resource-card"><h2>{{ item.title }}</h2><p class="muted small">会话对话是记忆的原始来源。</p><div class="card-actions"><a :href="`#/chat/${item.id}`">查看会话 →</a></div></article></div></template>
    <template v-else><label class="search-label">搜索当前层级<input v-model.trim="search" placeholder="输入记忆内容"></label><div v-if="!filtered.length" class="empty-state"><p>当前层级暂无匹配记忆。</p></div><div v-else class="resource-grid"><article v-for="item in filtered" :key="item.id" class="resource-card"><div class="resource-heading"><span class="badge green">{{ item.layer }}</span><span class="badge">{{ item.manual ? '人工调整' : '自动提取' }}</span><span v-if="item.status !== 'ACTIVE'" class="badge">待复核</span></div><p class="memory-text">{{ item.content }}</p><p class="small muted">{{ item.sourceSessionId ? '来源会话：' + item.sourceSessionId : '手动添加' }}</p><div class="card-actions"><a :href="`#/memory/${item.id}`">查看 / 修改</a><button class="text-button danger" @click="remove(item)">删除</button></div></article></div></template>
    <details class="memory-jobs"><summary><span><strong>自动提取任务</strong><small>共 {{ jobs.length }} 条，按需展开查看</small></span><span class="memory-jobs-toggle">展开</span></summary><div class="memory-jobs-body"><div class="panel-heading"><p class="muted small">对话完成后使用本次对话模型提取记忆；失败可以重试。</p><button class="text-button" @click.prevent="load">刷新</button></div><p v-if="!jobs.length" class="muted small">暂无提取任务。</p><div v-else class="memory-job-list"><div v-for="item in jobs" :key="item.runId" class="job-row"><span class="mono small">{{ item.runId.slice(0, 8) }}</span><span class="badge">{{ item.status }}</span><span class="muted small">{{ item.error || '' }}</span><button v-if="item.status === 'FAILED' || item.status === 'INTERRUPTED'" class="text-button" @click="retry(item)">重试</button></div></div></div></details>
  </template><template v-else><a class="back-link" href="#/memory">← 返回记忆管理</a><div class="page-heading"><div><p class="eyebrow">MEMORY DETAILS</p><h1>{{ id === 'new' ? '添加记忆' : '修改记忆' }}</h1><p class="muted">人工修改不会被后台静默覆盖，相关派生摘要可能需要复核。</p></div></div><form class="form-card" @submit.prevent="save"><label>记忆层级<select v-model="form.layer" :disabled="id !== 'new'"><option value="L1">L1 事实</option><option value="L2">L2 场景</option><option value="L3">L3 用户画像</option></select></label><label>记忆内容<textarea v-model="form.content" rows="8" maxlength="2000" required></textarea></label><p v-if="error" class="error" role="alert">{{ error }}</p><div class="form-actions"><button class="primary" :disabled="busy">保存记忆</button><a class="secondary link-button" href="#/memory">取消</a></div></form></template></main>
</template>
