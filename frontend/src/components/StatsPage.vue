<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import { api } from '../api.js';

const period = ref('7d'), data = ref(null), error = ref(''), busy = ref(false), activeDay = ref('');
const days = computed(() => [...new Set((data.value?.trends || []).map(item => item.day))]);
const rawMax = computed(() => Math.max(1, ...(data.value?.trends || []).map(item => item.runs)));
const tickStep = computed(() => Math.max(1, Math.ceil(rawMax.value / 4)));
const maxRuns = computed(() => Math.max(4, Math.ceil(rawMax.value / tickStep.value) * tickStep.value));
const yTicks = computed(() => Array.from({ length: Math.floor(maxRuns.value / tickStep.value) + 1 }, (_, index) => index * tickStep.value));
const activeIndex = computed(() => days.value.indexOf(activeDay.value));
const activeValues = computed(() => ({
  chat: data.value?.trends.find(item => item.day === activeDay.value && item.mode === 'CHAT')?.runs || 0,
  build: data.value?.trends.find(item => item.day === activeDay.value && item.mode === 'BUILD')?.runs || 0
}));
function x(index) { return days.value.length < 2 ? 325 : 45 + index * (565 / (days.value.length - 1)); }
function y(value) { return 145 - value * 115 / maxRuns.value; }
function points(mode) {
  const values = days.value.map(day => data.value?.trends.find(item => item.day === day && item.mode === mode)?.runs || 0);
  return values.map((value, index) => `${x(index)},${y(value)}`).join(' ');
}
function showLabel(index) { return days.value.length <= 10 || index === 0 || index === days.value.length - 1 || index % 5 === 0; }
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
          <div class="trend-chart-wrap">
            <svg class="trend-chart" viewBox="0 0 640 180" role="img" aria-label="Chat 与 Build 运行次数折线图">
              <g v-for="tick in yTicks" :key="tick"><line x1="45" x2="610" :y1="y(tick)" :y2="y(tick)" class="grid-line"/><text x="36" :y="y(tick) + 4" text-anchor="end" class="axis-label">{{ tick }}</text></g>
              <path d="M45 145H610M45 30V145" class="axis"/>
              <polyline v-if="days.length" :points="points('CHAT')" class="line chat"/><polyline v-if="days.length" :points="points('BUILD')" class="line build"/>
              <g v-for="(day,index) in days" :key="day">
                <circle :cx="x(index)" :cy="y(data.trends.find(item => item.day === day && item.mode === 'CHAT')?.runs || 0)" r="3.5" class="chart-dot chat"/>
                <circle :cx="x(index)" :cy="y(data.trends.find(item => item.day === day && item.mode === 'BUILD')?.runs || 0)" r="3.5" class="chart-dot build"/>
                <text v-if="showLabel(index)" :x="x(index)" y="169" text-anchor="middle" class="axis-label">{{ day.slice(5) }}</text>
                <rect :x="Math.max(45, x(index) - Math.max(10, 280 / Math.max(days.length, 1)))" y="25" :width="Math.max(20, 560 / Math.max(days.length, 1))" height="125" class="chart-hit" tabindex="0" role="button" :aria-label="`${day}，Chat ${data.trends.find(item => item.day === day && item.mode === 'CHAT')?.runs || 0} 次，Build ${data.trends.find(item => item.day === day && item.mode === 'BUILD')?.runs || 0} 次`" @mouseenter="activeDay=day" @mouseleave="activeDay=''" @focus="activeDay=day" @blur="activeDay=''" @click="activeDay=activeDay===day?'':day"/>
              </g>
              <line v-if="activeIndex >= 0" :x1="x(activeIndex)" :x2="x(activeIndex)" y1="30" y2="145" class="cursor-line"/>
            </svg>
            <div v-if="activeIndex >= 0" class="chart-tooltip" :style="{ left: `${Math.min(82, Math.max(10, x(activeIndex) / 6.4))}%` }" role="status"><strong>{{ activeDay }}</strong><span><i class="chat"></i>Chat {{ activeValues.chat }}</span><span><i class="build"></i>Build {{ activeValues.build }}</span></div>
          </div>
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
