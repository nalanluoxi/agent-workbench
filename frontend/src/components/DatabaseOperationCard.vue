<script setup>
import { computed } from 'vue';

const props = defineProps({ job: Object, busy: Boolean, compact: Boolean });
const emit = defineEmits(['decide']);

const pending = computed(() => props.job?.action?.status === 'PENDING');
const actionStatus = computed(() => props.job?.action?.status || '');
const operation = computed(() => props.job?.action?.operation || {});
const impact = computed(() => operation.value.impact || {});
const command = computed(() => String(operation.value.command || '').toUpperCase());
const sqlText = computed(() => String(impact.value.sql || operation.value.args?.sql || '').trim());
const sqlVerb = computed(() => {
  const normalized = sqlText.value.toUpperCase();
  for (const keyword of ['CREATE TABLE', 'ALTER TABLE', 'DROP TABLE', 'TRUNCATE TABLE']) {
    if (normalized.startsWith(keyword)) return keyword;
  }
  return normalized.match(/^\s*([A-Z]+)/)?.[1] || '';
});
const actionName = computed(() => command.value === 'SQL' ? sqlVerb.value : command.value);
const danger = computed(() => ['DELETE', 'DROP', 'DROP TABLE', 'TRUNCATE', 'TRUNCATE TABLE'].includes(actionName.value)
  || String(operation.value.args?.risk || '').toUpperCase() === 'DANGER');
const rows = computed(() => Array.isArray(props.job?.result) ? props.job.result : null);
const columns = computed(() => {
  const names = new Set();
  for (const row of rows.value || []) if (row && typeof row === 'object') Object.keys(row).forEach(key => names.add(key));
  return [...names];
});
const parsedTable = computed(() => sqlText.value.match(/\b(?:TABLE|INTO|UPDATE|FROM)\s+`?([A-Za-z0-9_$.-]+)`?/i)?.[1] || '');
const database = computed(() => operation.value.args?.database ?? operation.value.args?.db ?? '');
const target = computed(() => impact.value.target || operation.value.args?.table || operation.value.args?.key || parsedTable.value || '当前连接');
const targetSummary = computed(() => database.value ? `${database.value} · ${target.value}` : target.value);
const expiry = computed(() => props.job?.action?.expiresAt ? new Date(props.job.action.expiresAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '');
const statusMeta = computed(() => ({
  COMPLETED: { label: '已完成', title: '数据库操作已完成', tone: 'ok' },
  APPROVED: { label: '已批准', title: '数据库操作已批准', tone: 'ok' },
  REJECTED: { label: '已拒绝', title: '数据库操作已拒绝', tone: 'bad' },
  FAILED: { label: '执行失败', title: '数据库操作失败', tone: 'bad' },
  EXPIRED: { label: '已过期', title: '确认请求已过期', tone: 'warn' },
  UNKNOWN: { label: '状态未知', title: '数据库操作状态未知', tone: 'warn' }
}[actionStatus.value] || { label: actionStatus.value || '已结束', title: '数据库操作已结束', tone: 'warn' }));

const actionLabel = computed(() => ({
  'CREATE TABLE': '创建数据表',
  'ALTER TABLE': '修改表结构',
  INSERT: '写入数据',
  UPDATE: '修改数据',
  DELETE: '删除数据',
  'DROP TABLE': '删除数据表',
  DROP: '删除对象',
  'TRUNCATE TABLE': '清空数据表',
  TRUNCATE: '清空数据表'
}[actionName.value] || actionName.value || '数据库操作'));

const impactText = computed(() => {
  if (actionName.value === 'CREATE TABLE') return '该操作将改变数据库结构。执行后如需撤销，需要手动删除新建的数据表。';
  if (actionName.value === 'ALTER TABLE') return '该操作将修改数据表结构，可能影响现有数据和依赖该表的业务。';
  if (['DELETE', 'DROP', 'DROP TABLE', 'TRUNCATE', 'TRUNCATE TABLE'].includes(actionName.value)) return '该操作可能删除数据或数据库对象，执行后可能无法恢复。';
  return '该操作会修改数据库内容，请确认目标和 SQL 与预期一致。';
});
const estimatedRows = computed(() => Number.isFinite(Number(impact.value.estimatedRows)) ? Number(impact.value.estimatedRows) : null);
const previewRows = computed(() => Array.isArray(impact.value.previewRows) ? impact.value.previewRows : []);
const previewColumns = computed(() => [...new Set(previewRows.value.flatMap(row => Object.keys(row || {})))]);
const scopeLabel = computed(() => impact.value.scope === 'OBJECT' ? '数据库对象' : impact.value.scope === 'DATABASE' ? '数据库' : '数据行');
const rangeText = computed(() => {
  if (actionName.value === 'CREATE TABLE') return `将新增数据表 ${targetSummary.value}`;
  if (actionName.value === 'DROP TABLE') return `将删除数据表 ${targetSummary.value}`;
  if (actionName.value.includes('TRUNCATE')) return `将清空数据表 ${targetSummary.value}`;
  if (estimatedRows.value !== null) return `${actionLabel.value}，预计影响 ${estimatedRows.value} 行`;
  return `${actionLabel.value}：${targetSummary.value}`;
});

function display(value) {
  if (value === null) return 'NULL';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}
</script>

<template>
  <article v-if="pending" class="operation-card approval-card" :class="{ destructive: danger, compact }" role="group" aria-label="待确认数据库操作">
    <header class="operation-card-header">
      <div><p class="operation-kicker">数据库操作</p><h3>{{ danger ? '高风险操作待确认' : '数据库变更待确认' }}</h3></div>
      <span class="status-pill" :class="danger ? 'bad' : 'warn'">{{ danger ? '高风险' : '待确认' }}</span>
    </header>
    <p class="operation-summary">系统尚未执行。请核对以下操作内容，再决定是否继续。</p>
    <div class="operation-meta" aria-label="操作摘要">
      <span><small>操作</small><strong>{{ actionLabel }}</strong></span>
      <span><small>目标</small><strong class="mono">{{ targetSummary }}</strong></span>
      <span><small>有效期</small><strong>{{ expiry || '10 分钟' }}</strong></span>
    </div>
    <section class="impact-summary" aria-label="影响范围">
      <div><small>影响范围</small><strong>{{ scopeLabel }}</strong></div>
      <div><small>预估结果</small><strong>{{ rangeText }}</strong></div>
      <div v-if="impact.previewTruncated"><small>数据预览</small><strong>仅展示前 {{ previewRows.length }} 行</strong></div>
    </section>
    <section v-if="previewRows.length" class="impact-preview" aria-label="受影响数据预览">
      <div class="sql-section-title"><span>{{ actionName === 'INSERT' ? '待写入数据' : '当前匹配数据' }}</span><code>{{ estimatedRows }} 行</code></div>
      <div class="result-table-wrap"><table class="result-table"><thead><tr><th v-for="column in previewColumns" :key="column">{{ column }}</th></tr></thead><tbody><tr v-for="(row,index) in previewRows" :key="index"><td v-for="column in previewColumns" :key="column" class="mono">{{ display(row[column]) }}</td></tr></tbody></table></div>
    </section>
    <section v-if="sqlText" class="sql-section" aria-label="待执行 SQL">
      <div class="sql-section-title"><span>待执行 SQL</span><code>{{ actionName || 'SQL' }}</code></div>
      <pre class="sql-preview"><code>{{ sqlText }}</code></pre>
    </section>
    <p v-else-if="impact.conditions" class="condition-summary"><strong>匹配条件</strong><code>{{ JSON.stringify(impact.conditions) }}</code></p>
    <p class="operation-impact" :class="{ danger }"><span aria-hidden="true">!</span>{{ impactText }}</p>
    <div class="operation-actions">
      <button class="secondary" :disabled="busy" @click="emit('decide', 'reject')">拒绝</button>
      <button class="primary" :disabled="busy" @click="emit('decide', 'approve')">{{ busy ? '处理中…' : '确认执行' }}</button>
    </div>
  </article>

  <article v-else-if="job?.status === 'COMPLETED' && job?.result !== undefined && job?.result !== null" class="operation-card result-card" :class="{ compact }" aria-label="数据库操作结果">
    <header class="operation-card-header"><div><p class="operation-kicker">数据库结果</p><h3>{{ operation.command ? '数据库变更已完成' : '查询已完成' }}</h3></div><span class="status-pill ok">已完成</span></header>
    <template v-if="rows && !operation.command">
      <p class="operation-summary">共返回 {{ rows.length }} 行真实数据。</p>
      <div v-if="rows.length" class="result-table-wrap"><table class="result-table"><thead><tr><th v-for="column in columns" :key="column">{{ column }}</th></tr></thead><tbody><tr v-for="(row,index) in rows" :key="index"><td v-for="column in columns" :key="column" class="mono">{{ display(row[column]) }}</td></tr></tbody></table></div>
      <p v-else class="quiet-state">查询执行成功，没有匹配的数据。</p>
    </template>
    <pre v-else>{{ JSON.stringify(job.result, null, 2) }}</pre>
  </article>

  <article v-else-if="actionStatus && actionStatus !== 'PENDING'" class="operation-card history-card" :class="[compact ? 'compact' : '', `history-${statusMeta.tone}`]" aria-label="数据库操作最终状态">
    <header class="operation-card-header"><div><p class="operation-kicker">数据库操作记录</p><h3>{{ statusMeta.title }}</h3></div><span class="status-pill" :class="statusMeta.tone">{{ statusMeta.label }}</span></header>
    <p class="history-summary"><strong>{{ actionLabel }}</strong><span class="mono">{{ targetSummary }}</span></p>
    <details class="history-details">
      <summary>查看操作详情</summary>
      <pre v-if="sqlText" class="sql-preview"><code>{{ sqlText }}</code></pre>
      <p v-else class="operation-summary">{{ rangeText }}</p>
    </details>
  </article>

  <article v-else-if="job?.error" class="operation-card operation-error" role="alert">
    <header class="operation-card-header"><div><p class="operation-kicker">数据库操作</p><h3>数据库操作未完成</h3></div><span class="status-pill bad">失败</span></header>
    <p>{{ job.error }}</p>
    <p class="operation-summary">系统已阻止继续执行；请根据上方真实错误标识检查对应的数据约束、字段、对象或连接状态。</p>
  </article>
</template>
