<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { api, getToken, setToken } from './api.js';
import AuthPage from './components/AuthPage.vue';
import ChatPage from './components/ChatPage.vue';
import DatabasePage from './components/DatabasePage.vue';
import McpPage from './components/McpPage.vue';
import MemoryPage from './components/MemoryPage.vue';
import ModelsPage from './components/ModelsPage.vue';
import SkillsPage from './components/SkillsPage.vue';
import StatsPage from './components/StatsPage.vue';
import ToolsPage from './components/ToolsPage.vue';
import UiIcon from './components/UiIcon.vue';

const authenticated = ref(!!getToken());
const sessions = ref([]);
const currentUser = ref({ email: '', role: 'USER' });
const route = ref(location.hash.slice(2) || 'chat');
const error = ref('');
const page = computed(() => route.value.split('/')[0]);
const sessionId = computed(() => page.value === 'chat' ? route.value.split('/')[1] || '' : '');
const nav = [
  { key: 'dashboard', label: '运行看板', icon: 'dashboard' },
  { key: 'models', label: '模型配置', icon: 'model' },
  { key: 'tools', label: '工具管理', icon: 'tools' },
  { key: 'skills', label: 'Skill 管理', icon: 'skill' },
  { key: 'mcp', label: 'MCP 服务', icon: 'mcp' },
  { key: 'memory', label: '记忆管理', icon: 'memory' },
];

function changed() { route.value = location.hash.slice(2) || 'chat'; }
async function refresh() { try { sessions.value = await api('/api/sessions'); } catch (e) { error.value = e.message; } }
async function loadCurrentUser() { currentUser.value = await api('/api/auth/me'); }
async function login() { authenticated.value = true; await loadCurrentUser(); await refresh(); }
async function logout() { try { await api('/api/auth/logout', { method: 'POST' }); } catch {} setToken(''); authenticated.value = false; sessions.value = []; }
async function newSession() {
  try {
    const session = await api('/api/sessions', { method: 'POST', body: { title: '新会话' } });
    await refresh();
    location.hash = `#/chat/${session.id}`;
  } catch (e) { error.value = e.message; }
}
async function deleteSession(item) {
  if (!window.confirm(`删除会话“${item.title}”？`)) return;
  try {
    await api(`/api/sessions/${item.id}`, { method: 'DELETE' });
    if (sessionId.value === item.id) location.hash = '#/chat';
    await refresh();
  } catch (e) { error.value = e.message; }
}
onMounted(async () => {
  window.addEventListener('hashchange', changed);
  if (authenticated.value) {
    try { await loadCurrentUser(); await refresh(); }
    catch { setToken(''); authenticated.value = false; }
  }
});
onBeforeUnmount(() => window.removeEventListener('hashchange', changed));
</script>

<template>
  <AuthPage v-if="!authenticated" @login="login" />
  <div v-else class="app-shell" :class="{ 'has-inspector': page === 'chat' }">
    <aside class="sidebar">
      <div class="brand"><div class="brand-mark">AW</div><div><strong>ZWD-AWDP</strong><span>AGENT WORKBENCH PLATFORM</span></div></div>
      <button class="new-session" @click="newSession"><UiIcon name="plus" :size="17" />新建会话</button>
      <p class="nav-label">Workspace</p>
      <nav aria-label="主导航">
        <a v-for="item in nav" :key="item.key" :class="{ active: page === item.key }" :href="`#/${item.key}`">
          <UiIcon :name="item.icon" /><span>{{ item.label }}</span><span v-if="page === item.key" class="nav-indicator"></span>
        </a>
      </nav>
      <div class="session-heading"><span>最近会话</span><button class="text-button" aria-label="刷新会话" @click="refresh">刷新</button></div>
      <div class="session-list">
        <div v-for="item in sessions" :key="item.id" class="session-item" :class="{ selected: item.id === sessionId }">
          <a :href="`#/chat/${item.id}`">{{ item.title }}</a><button class="text-button" :aria-label="`删除${item.title}`" @click="deleteSession(item)">×</button>
        </div>
        <p v-if="!sessions.length" class="sidebar-empty">暂无会话</p>
      </div>
      <div class="sidebar-bottom"><div class="profile"><span class="profile-avatar">{{ currentUser.role === 'ADMIN' ? 'A' : (currentUser.email || 'U').slice(0, 1).toUpperCase() }}</span><span><strong>{{ currentUser.role === 'ADMIN' ? 'Admin' : (currentUser.email || '用户') }}</strong><small>{{ currentUser.role === 'ADMIN' ? '管理员' : '安全工作区' }}</small></span></div><button class="text-button" @click="logout">退出</button></div>
    </aside>

    <ChatPage v-if="page === 'chat'" :session-id="sessionId" @new-session="newSession" />
    <StatsPage v-else-if="page === 'dashboard'" />
    <ModelsPage v-else-if="page === 'models'" :key="route" :path="route" />
    <ToolsPage v-else-if="page === 'tools'" />
    <DatabasePage v-else-if="page === 'mysql' || page === 'redis'" :key="page" :type="page === 'mysql' ? 'MYSQL' : 'REDIS'" />
    <SkillsPage v-else-if="page === 'skills'" :key="route" :path="route" />
    <MemoryPage v-else-if="page === 'memory'" :key="route" :path="route" />
    <McpPage v-else-if="page === 'mcp'" :key="route" :path="route" />
    <main v-else class="content-page"><h1>页面不存在</h1><a href="#/chat">返回工作台</a></main>
    <p v-if="error" class="global-error error" role="alert">{{ error }}</p>
  </div>
</template>
