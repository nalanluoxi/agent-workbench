<script setup>
import { computed, onMounted, ref } from 'vue';
import { api } from '../api.js';
import DatabaseOperationCard from './DatabaseOperationCard.vue';

const props = defineProps({ type: String });
const tool = ref(null), command = ref(props.type === 'MYSQL' ? 'DATABASES' : 'GET');
const args = ref(props.type === 'MYSQL' ? '{}' : '{\n  "database": 0,\n  "key": ""\n}');
const sql = ref('SELECT * FROM ');
const job = ref(null), error = ref(''), busy = ref(false);
const toolId = computed(() => props.type === 'MYSQL' ? 'builtin-mysql' : 'builtin-redis');
const options = computed(() => props.type === 'MYSQL'
  ? ['SQL', 'DATABASES', 'TABLES', 'DESCRIBE', 'SELECT', 'INSERT', 'UPDATE', 'DELETE']
  : ['GET', 'SET', 'DEL', 'TTL', 'EXPIRE', 'HGET', 'HGETALL', 'HSET', 'HDEL', 'LRANGE', 'LPUSH', 'RPUSH', 'LSET', 'LREM', 'SMEMBERS', 'SADD', 'SREM', 'ZRANGE', 'ZADD', 'ZREM', 'XRANGE', 'XADD', 'XDEL']);
const risky = computed(() => command.value === 'SQL'
  ? /^(INSERT|UPDATE|DELETE|DROP|TRUNCATE|ALTER|CREATE)\b/i.test(sql.value.trim())
  : tool.value?.confirmationList?.includes(command.value));

onMounted(async () => { try { tool.value = (await api('/api/tools')).find(item => item.id === toolId.value); } catch (e) { error.value = e.message; } });
async function submit() {
  error.value = ''; busy.value = true; job.value = null;
  try {
    let value;
    if (command.value === 'SQL') value = { sql: sql.value };
    else try { value = JSON.parse(args.value); } catch { throw new Error('操作参数必须是有效的 JSON'); }
    job.value = await api('/api/database/operations', { method: 'POST', body: { connectionId: toolId.value, command: command.value, args: value } });
  } catch (e) { error.value = e.message; }
  finally { busy.value = false; }
}
async function decide(decision) { error.value = ''; busy.value = true; try { job.value = await api(`/api/actions/${job.value.action.id}/${decision}`, { method: 'POST' }); } catch (e) { error.value = e.message; } finally { busy.value = false; } }
</script>

<template>
  <main class="content-page">
    <a class="back-link" href="#/tools">← 返回工具管理</a>
    <div class="page-heading"><div><p class="eyebrow">FIXED CLI TOOL</p><h1>{{ type === 'MYSQL' ? 'MySQL CLI' : 'Redis CLI' }}</h1><p class="muted">固定本机工具入口；不再为每个数据库维护一条连接记录。</p></div><div v-if="tool" class="tool-health"><span class="status-pill" :class="tool.configured ? 'ok' : 'warn'">{{ tool.configured ? '已注册' : '未注册' }}</span><span class="status-pill" :class="tool.enabled ? 'ok' : 'bad'">{{ tool.enabled ? '已启用' : '已停用' }}</span></div></div>
    <div class="split-page">
      <form class="form-card" @submit.prevent="submit"><div class="fixed-target"><span>固定目标</span><strong>{{ tool?.host || '未注册' }}<template v-if="tool?.port">:{{ tool.port }}</template></strong><small>{{ toolId }}</small></div><label>操作<select v-model="command"><option v-for="item in options" :key="item" :value="item">{{ item }}{{ item !== 'SQL' && tool?.confirmationList?.includes(item) ? ' · 需确认' : '' }}</option></select></label><label v-if="command === 'SQL'">SQL 语句<textarea v-model="sql" class="mono sql-editor" rows="9" spellcheck="false" placeholder="输入单条 SQL"></textarea></label><label v-else>操作参数 <span class="muted">JSON</span><textarea v-model="args" class="mono" rows="12" spellcheck="false"></textarea></label><div class="permission-banner" :class="{ risk: risky }"><strong>{{ risky ? '变更操作 · 需要卡片确认' : '只读查询 · 直接执行' }}</strong><span>{{ risky ? '提交后不会立即修改数据，请在右侧卡片点击确认。' : '校验通过后直接执行并展示真实返回数据。' }}</span></div><p class="muted small">是否确认由服务端解析结果决定，不能由 Agent 或页面自行放行。</p><p v-if="error" class="error" role="alert">{{ error }}</p><button class="primary" :disabled="busy || !tool?.configured || !tool?.enabled">{{ busy ? '处理中…' : risky ? '生成确认卡片' : '执行查询' }}</button></form>
      <section class="result-panel"><div class="panel-heading"><h2>执行结果</h2><span class="badge">{{ job?.status || '等待操作' }}</span></div><div v-if="!job" class="empty-state compact"><p>只读查询会直接返回数据；变更操作会在这里显示确认卡片。</p></div><template v-else><p class="small muted mono">{{ job.id }}</p><DatabaseOperationCard :job="job" :busy="busy" compact @decide="decide" /></template></section>
    </div>
  </main>
</template>
