<script setup>
import { onMounted, ref } from 'vue';
import { api } from '../api.js';
import UiIcon from './UiIcon.vue';

const tools = ref([]), busy = ref(''), error = ref('');
async function load() { try { tools.value = await api('/api/tools'); } catch (e) { error.value = e.message; } }
async function toggle(item) {
  busy.value = item.id; error.value = '';
  try { await api(`/api/tools/${item.id}/enabled`, { method: 'POST', body: { enabled: !item.enabled } }); await load(); }
  catch (e) { error.value = e.message; }
  finally { busy.value = ''; }
}
onMounted(load);
</script>

<template>
  <main class="content-page">
    <div class="page-heading"><div><p class="eyebrow">POLICY REGISTRY</p><h1>工具管理</h1><p class="muted">内置数据库工具采用固定入口；连接凭据不在页面按数据库拆分。</p></div></div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div class="tool-policy-grid">
      <article v-for="item in tools" :key="item.id" class="policy-card">
        <header><span class="resource-icon"><UiIcon name="database" /></span><div><h2>{{ item.name }}</h2><p class="mono">{{ item.cliCommand }}</p></div><button class="switch" :class="{ on: item.enabled }" :aria-label="`${item.enabled ? '停用' : '启用'}${item.name}`" :disabled="busy === item.id" @click="toggle(item)"><span></span></button></header>
        <div class="tool-meta"><span class="status-pill" :class="item.configured ? 'ok' : 'warn'">{{ item.configured ? '已注册' : '未注册' }}</span><span>{{ item.host }}<template v-if="item.port">:{{ item.port }}</template></span><span>默认库 {{ item.defaultDatabase || '—' }}</span></div>
        <div class="policy-section"><strong>Chat 白名单</strong><p>只读命令，可直接调度</p><div class="command-list"><code v-for="command in item.chatAllowlist" :key="command">{{ command }}</code></div></div>
        <div class="policy-section"><strong>Build 白名单</strong><p>包含读取与数据变更</p><div class="command-list"><code v-for="command in item.buildAllowlist" :key="command" :class="{ risk: item.confirmationList.includes(command) }">{{ command }}</code></div></div>
        <footer><span><i class="legend-dot"></i>普通允许</span><span><i class="legend-dot risk"></i>需人工确认</span><a :href="item.type === 'MYSQL' ? '#/mysql' : '#/redis'">打开 CLI 面板</a></footer>
      </article>
    </div>
    <section class="policy-note"><h2>权限执行规则</h2><ol><li>意图路由先确定候选工具，不直接执行。</li><li>Chat 仅能命中只读白名单；越权请求立即拒绝。</li><li>Build 可命中完整白名单；所有写、改、删操作进入确认状态机。</li><li>启停后立即影响后续运行，内置工具不可删除。</li></ol></section>
  </main>
</template>
