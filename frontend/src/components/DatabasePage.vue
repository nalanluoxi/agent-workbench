<script setup>
import { computed, onMounted, ref } from 'vue';
import { api } from '../api.js';

const props = defineProps({ type: String });
const tool = ref(null), command = ref(props.type === 'MYSQL' ? 'DATABASES' : 'GET');
const args = ref(props.type === 'MYSQL' ? '{}' : '{\n  "database": 0,\n  "key": ""\n}');
const job = ref(null), error = ref(''), busy = ref(false);
const toolId = computed(() => props.type === 'MYSQL' ? 'builtin-mysql' : 'builtin-redis');
const options = computed(() => props.type === 'MYSQL'
  ? ['DATABASES', 'TABLES', 'DESCRIBE', 'SELECT', 'INSERT', 'UPDATE', 'DELETE']
  : ['GET', 'SET', 'DEL', 'TTL', 'EXPIRE', 'HGET', 'HGETALL', 'HSET', 'HDEL', 'LRANGE', 'LPUSH', 'RPUSH', 'LSET', 'LREM', 'SMEMBERS', 'SADD', 'SREM', 'ZRANGE', 'ZADD', 'ZREM', 'XRANGE', 'XADD', 'XDEL']);
const risky = computed(() => tool.value?.confirmationList?.includes(command.value));

onMounted(async () => { try { tool.value = (await api('/api/tools')).find(item => item.id === toolId.value); } catch (e) { error.value = e.message; } });
async function submit() {
  error.value = ''; busy.value = true; job.value = null;
  try {
    let value; try { value = JSON.parse(args.value); } catch { throw new Error('操作参数必须是有效的 JSON'); }
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
      <form class="form-card" @submit.prevent="submit"><div class="fixed-target"><span>固定目标</span><strong>{{ tool?.host || '未注册' }}<template v-if="tool?.port">:{{ tool.port }}</template></strong><small>{{ toolId }}</small></div><label>操作<select v-model="command"><option v-for="item in options" :key="item" :value="item">{{ item }}{{ tool?.confirmationList?.includes(item) ? ' · 需确认' : '' }}</option></select></label><label>操作参数 <span class="muted">JSON</span><textarea v-model="args" class="mono" rows="12" spellcheck="false"></textarea></label><div class="permission-banner" :class="{ risk: risky }"><strong>{{ risky ? '人工确认操作' : '只读操作' }}</strong><span>{{ risky ? '提交后先生成确认卡片，不会直接修改数据。' : '校验通过后直接读取目标数据。' }}</span></div><p class="muted small">MySQL 可在参数中传 <code>database</code>；Redis 可传 0–9999 的 <code>database</code>。Stream 旧消息不支持原地修改。</p><p v-if="error" class="error" role="alert">{{ error }}</p><button class="primary" :disabled="busy || !tool?.configured || !tool?.enabled">{{ busy ? '处理中…' : risky ? '提交并等待确认' : '执行读取' }}</button></form>
      <section class="result-panel"><div class="panel-heading"><h2>执行结果</h2><span class="badge">{{ job?.status || '等待操作' }}</span></div><div v-if="!job" class="empty-state compact"><p>选择命令并提交后，在这里查看状态与返回值。</p></div><template v-else><p class="small muted mono">{{ job.id }}</p><div v-if="job.action?.status === 'PENDING'" class="approval-card"><p class="eyebrow">HUMAN GATE</p><h3>确认数据库变更</h3><p>请检查数据库、命令及参数。批准后才会执行。</p><pre>{{ JSON.stringify(job.action.operation, null, 2) }}</pre><div class="form-actions"><button class="primary" :disabled="busy" @click="decide('approve')">确认执行</button><button class="secondary" :disabled="busy" @click="decide('reject')">拒绝</button></div></div><p v-if="job.error" class="error">{{ job.error }}</p><pre v-if="job.result">{{ JSON.stringify(job.result, null, 2) }}</pre></template></section>
    </div>
  </main>
</template>
