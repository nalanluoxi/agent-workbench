<script setup>
import { onBeforeUnmount, ref, watch } from 'vue';
import { api, setToken } from '../api.js';

const emit = defineEmits(['login']);
const mode = ref('login');
const email = ref(''), password = ref(''), code = ref(''), error = ref(''), notice = ref(''), busy = ref(false);
const codeBusy = ref(false), codeCooldown = ref(0);
let cooldownTimer;

function cooldownKey() { return `workbench-email-code-cooldown:${email.value.trim().toLowerCase()}`; }
function stopCooldownTimer() { if (cooldownTimer) window.clearInterval(cooldownTimer); cooldownTimer = undefined; }
function refreshCooldown() {
  stopCooldownTimer();
  if (!email.value.trim()) { codeCooldown.value = 0; return; }
  const key = cooldownKey();
  const update = () => {
    const deadline = Number(sessionStorage.getItem(key) || 0);
    codeCooldown.value = Math.max(0, Math.ceil((deadline - Date.now()) / 1000));
    if (codeCooldown.value === 0) { sessionStorage.removeItem(key); stopCooldownTimer(); }
  };
  update();
  if (codeCooldown.value > 0) cooldownTimer = window.setInterval(update, 250);
}
function beginCooldown() {
  sessionStorage.setItem(cooldownKey(), String(Date.now() + 60_000));
  refreshCooldown();
}

watch(email, refreshCooldown);
onBeforeUnmount(stopCooldownTimer);

async function sendCode() {
  if (codeBusy.value || codeCooldown.value > 0) return;
  codeBusy.value = true; error.value = ''; notice.value = '';
  try {
    const result = await api('/api/auth/email-codes', { method: 'POST', body: { email: email.value } });
    notice.value = result.message;
    beginCooldown();
  } catch (e) { error.value = e.message; } finally { codeBusy.value = false; }
}
async function submit() {
  busy.value = true; error.value = ''; notice.value = '';
  try {
    if (mode.value === 'register') {
      const result = await api('/api/auth/register', { method: 'POST', body: { email: email.value, password: password.value, code: code.value } });
      notice.value = result.message; mode.value = 'login'; password.value = ''; code.value = '';
    } else {
      const result = await api('/api/auth/login', { method: 'POST', body: { email: email.value, password: password.value } });
      setToken(result.token); password.value = ''; emit('login');
    }
  } catch (e) { error.value = e.message; } finally { busy.value = false; }
}
</script>

<template>
  <main class="auth-shell">
    <section class="auth-intro"><div class="brand-mark">AW</div><p class="eyebrow">ZWD-AWDP · 本地智能体工作台</p><h1>让想法，<br>有序地运行。</h1><p>连接模型、管理工具与 Skill，并跟踪每一步 Agent 执行。</p><div class="auth-lines"><span>01 / 模型与会话</span><span>02 / 工具与技能</span><span>03 / 人工确认</span></div></section>
    <section class="auth-card" aria-labelledby="auth-title">
      <p class="eyebrow">WELCOME BACK</p><h2 id="auth-title">{{ mode === 'login' ? '登录工作台' : '创建账号' }}</h2>
      <p class="muted">{{ mode === 'login' ? '使用邮箱和密码继续。' : '支持 QQ 邮箱和其他常见邮箱。' }}</p>
      <div class="tabs"><button :class="{ selected: mode === 'login' }" @click="mode='login'; error=''; notice=''">登录</button><button :class="{ selected: mode === 'register' }" @click="mode='register'; error=''; notice=''">注册</button></div>
      <form @submit.prevent="submit">
        <label>邮箱<input v-model.trim="email" type="email" required autocomplete="email" placeholder="name@example.com"></label>
        <label v-if="mode === 'register'">邮箱验证码<span class="inline-field"><input v-model="code" required inputmode="numeric" maxlength="6" autocomplete="one-time-code" placeholder="6 位验证码"><button type="button" class="secondary" :disabled="codeBusy || codeCooldown > 0 || !email" @click="sendCode">{{ codeBusy ? '发送中…' : codeCooldown > 0 ? `${codeCooldown}s 后重发` : '发送验证码' }}</button></span><small class="muted">验证码 5 分钟内有效，发送后 60 秒内不可重复发送。</small></label>
        <label>密码<input v-model="password" type="password" required :minlength="mode === 'register' ? 8 : null" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" :placeholder="mode === 'login' ? '请输入密码' : '至少 8 个字符'"></label>
        <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="notice" class="notice" role="status">{{ notice }}</p>
        <button class="primary wide" :disabled="busy">{{ busy ? '处理中…' : mode === 'login' ? '进入工作台' : '验证并注册' }}</button>
      </form>
      <p class="auth-foot">账号数据保存在本机服务中。</p>
    </section>
  </main>
</template>
