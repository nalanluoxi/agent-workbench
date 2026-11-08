<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import { api } from '../api.js';

const period = ref('7d'), data = ref(null), error = ref(''), busy = ref(false);
const days = computed(() => [...new Set((data.value?.trends || []).map(item => item.day))]);
const maxRuns = computed(() => Math.max(1, ...(data.value?.trends || []).map(item => item.runs)));
function points(mode) {
  const values = days.value.map(day => data.value?.trends.find(item => item.day === day && item.mode === mode)?.runs || 0);
  return values.map((value, index) => {
    const x = days.value.length < 2 ? 320 : 30 + index * (580 / (days.value.length - 1));
    const y = 145 - value * 115 / maxRuns.value;
    return `${x},${y}`;
  }).join(' ');
}
function percent(value) { return `${Math.max(0, Math.min(100, Number(value || 0)))}%`; }
function formatTime(value) { return new Date(value).toLocaleString('zh-CN', { hour12: false }); }
async function load() { busy.value = true; error.value = ''; try { data.value = await api(`/api/stats/dashboard?period=${period.value}&scope=me`); } catch (e) { error.value = e.message; } finally { busy.value = false; } }
watch(period, load);
onMounted(load);
</script>

<template>
  <main class="content-page dashboard-page">
    <div class="page-heading"><div><p class="eyebrow">OPERATIONS</p><h1>运行看板</h1><p class="muted">统计运行、工具、Skill 与意图路由表现；用户取消不计入失败率。</p></div><div class="heading-actions"><select v-model="period" aria-label="统计周期"><option value="24h">最近 24 小时</option><option value="7d">最近 7 天</option><option value="30d">最近 30 天</option></select><button class="secondary" :disabled="busy" @click="load">刷新</button></div></div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <template v-if="data">
      <section class="metric-grid" aria-label="核心指标">
        <article><span>运行成功率</span><strong>{{ data.overview.runSuccessRate }}%</strong><small>{{ data.overview.successfulRuns }} / {{ data.overview.runDenominator }} 个终态运行</small></article>
        <article><span>工具成功率</span><strong>{{ data.overview.toolSuccessRate }}%</strong><small>{{ data.overview.successfulToolCalls }} / {{ data.overview.toolDenominator }} 次有效调用</small></article>
        <article><span>运行次数</span><strong>{{ data.overview.runs }}</strong><small>当前周期全部模式</small></article>
        <article><span>人工确认</span><strong>{{ data.overview.confirmations }}</strong><small>数据库、MCP 与脚本</small></article>
      </section>

      <div class="dashboard-grid">
        <section class="dashboard-panel chart-panel"><div class="panel-title"><div><h2>Chat / Build 运行趋势</h2><p>按天统计新建运行</p></div><div class="chart-legend"><span><i class="chat"></i>Chat</span><span><i class="build"></i>Build</span></div></div>
          <svg class="trend-chart" viewBox="0 0 640 180" role="img" aria-label="Chat 与 Build 运行次数折线图"><path d="M30 145H610M30 30V145" class="axis"/><polyline v-if="days.length" :points="points('CHAT')" class="line chat"/><polyline v-if="days.length" :points="points('BUILD')" class="line build"/><text v-for="(day,index) in days" :key="day" :x="days.length < 2 ? 320 : 30 + index * (580 / (days.length - 1))" y="169" text-anchor="middle">{{ day.slice(5) }}</text></svg>
          <table class="sr-table"><caption>运行趋势数据</caption><thead><tr><th>日期</th><th>模式</th><th>运行</th><th>成功</th></tr></thead><tbody><tr v-for="item in data.trends" :key="`${item.day}-${item.mode}`"><td>{{ item.day }}</td><td>{{ item.mode }}</td><td>{{ item.runs }}</td><td>{{ item.successful }}</td></tr></tbody></table>
        </section>

        <section class="dashboard-panel"><div class="panel-title"><div><h2>资源状态</h2><p>启用资源 / 总资源</p></div></div><div class="resource-stats"><div><span>内置工具</span><strong>{{ data.resources.enabledTools }} / {{ data.resources.tools }}</strong></div><div><span>Skills</span><strong>{{ data.resources.enabledSkills }} / {{ data.resources.skills }}</strong></div><div><span>MCP Tools</span><strong>{{ data.resources.enabledMcpTools }} / {{ data.resources.mcpTools }}</strong></div></div></section>

        <section class="dashboard-panel"><div class="panel-title"><div><h2>工具调用排名</h2><p>调用量与成功率</p></div></div><div class="rank-list"><div v-for="item in data.toolRanking" :key="item.label" class="rank-row"><div><strong>{{ item.label }}</strong><span>{{ item.calls }} 次</span></div><div class="bar"><i :style="{ width: percent(item.successRate) }"></i></div><b>{{ item.successRate }}%</b></div><p v-if="!data.toolRanking.length" class="quiet-state">当前周期暂无工具调用</p></div></section>

        <section class="dashboard-panel"><div class="panel-title"><div><h2>意图路由分布</h2><p>首阶段分类结果</p></div></div><div class="intent-list"><div v-for="item in data.intents" :key="item.intent"><code>{{ item.intent }}</code><strong>{{ item.count }}</strong></div><p v-if="!data.intents.length" class="quiet-state">当前周期暂无路由记录</p></div></section>

        <section class="dashboard-panel confirmations"><div class="panel-title"><div><h2>确认状态</h2><p>人工确认状态机分布</p></div></div><div class="confirmation-grid"><div v-for="(count,status) in data.confirmations" :key="status"><span>{{ status }}</span><strong>{{ count }}</strong></div><p v-if="!Object.keys(data.confirmations).length" class="quiet-state">当前周期暂无确认动作</p></div></section>

        <section class="dashboard-panel recent-panel"><div class="panel-title"><div><h2>最近操作</h2><p>最近 20 条工具任务</p></div></div><div class="table-wrap"><table class="data-table"><thead><tr><th>资源</th><th>状态</th><th>时间</th><th>错误</th></tr></thead><tbody><tr v-for="item in data.recent" :key="item.id"><td class="mono">{{ item.resource }}</td><td><span class="status-pill" :class="item.status === 'COMPLETED' ? 'ok' : item.status === 'FAILED' ? 'bad' : ''">{{ item.status }}</span></td><td>{{ formatTime(item.createdAt) }}</td><td class="muted">{{ item.error || '—' }}</td></tr><tr v-if="!data.recent.length"><td colspan="4" class="muted">暂无操作记录</td></tr></tbody></table></div></section>
      </div>
    </template>
  </main>
</template>
