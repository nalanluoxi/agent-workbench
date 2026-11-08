<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { api } from '../api.js';

const props = defineProps({ path: String });
const entries = ref([]), error = ref(''), notice = ref(''), busy = ref(false), testing = ref(false), tested = ref(null);
const id = computed(() => props.path.split('/')[1] || '');
const editing = computed(() => !!id.value);
const presets = {
  OPENAI: { label: 'OpenAI', provider: 'OpenAI', baseUrl: 'https://api.openai.com/v1', modelName: 'gpt-4.1-mini' },
  DEEPSEEK: { label: 'DeepSeek', provider: 'DeepSeek', baseUrl: 'https://api.deepseek.com', modelName: 'deepseek-chat' },
  QWEN: { label: 'Qwen / DashScope', provider: 'Qwen', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', modelName: 'qwen-plus' },
  CUSTOM: { label: '自定义 OpenAI 兼容', provider: 'OpenAI Compatible', baseUrl: '', modelName: '' },
};
const providerKey = ref('OPENAI');
const form = reactive({ kind: 'CHAT', name: '', provider: presets.OPENAI.provider, baseUrl: presets.OPENAI.baseUrl, modelName: presets.OPENAI.modelName, apiKey: '', enabled: true });

function draft() { return { kind: form.kind, name: form.name, provider: form.provider, baseUrl: form.baseUrl, modelName: form.modelName, apiKey: form.apiKey, enabled: form.enabled }; }
function chooseProvider() { const value = presets[providerKey.value]; form.provider = value.provider; form.baseUrl = value.baseUrl; form.modelName = form.kind === 'EMBEDDING' ? '' : value.modelName; }
async function load() {
  try {
    entries.value = await api('/api/models');
    if (editing.value && id.value !== 'new') {
      const current = entries.value.find(item => item.id === id.value);
      if (!current) throw new Error('模型配置不存在');
      Object.assign(form, current, { apiKey: '' });
      providerKey.value = Object.keys(presets).find(key => key !== 'CUSTOM' && presets[key].provider === current.provider) || 'CUSTOM';
    }
  } catch (e) { error.value = e.message; }
}
async function testConnection() {
  error.value = ''; notice.value = ''; testing.value = true; tested.value = null;
  try {
    const result = await api('/api/models/test', { method: 'POST', body: { modelId: id.value && id.value !== 'new' ? id.value : null, input: draft() } });
    tested.value = result; notice.value = `测试成功，延迟 ${result.latencyMs} ms。当前配置可保存。`;
  } catch (e) { error.value = e.message; }
  finally { testing.value = false; }
}
async function save() {
  if (!tested.value?.testToken) { error.value = '请先测试当前配置'; return; }
  error.value = ''; busy.value = true;
  try {
    const body = { ...draft(), testToken: tested.value.testToken };
    await api(id.value === 'new' ? '/api/models' : `/api/models/${id.value}`, { method: id.value === 'new' ? 'POST' : 'PUT', body });
    form.apiKey = ''; location.hash = '#/models';
  } catch (e) { error.value = e.message; }
  finally { busy.value = false; }
}
async function setDefault(model) { try { await api(`/api/models/${model.id}/default`, { method: 'POST' }); notice.value = '默认模型已更新'; await load(); } catch (e) { error.value = e.message; } }
async function remove(model) { if (!window.confirm(`删除模型配置“${model.name}”？`)) return; try { await api(`/api/models/${model.id}`, { method: 'DELETE' }); await load(); } catch (e) { error.value = e.message; } }
watch(form, () => { tested.value = null; if (notice.value?.startsWith('测试成功')) notice.value = ''; }, { deep: true });
onMounted(load);
</script>

<template>
  <main class="content-page">
    <template v-if="!editing"><div class="page-heading"><div><p class="eyebrow">MODEL REGISTRY</p><h1>模型配置</h1><p class="muted">支持多个服务商、默认模型和自定义 OpenAI 兼容接口。</p></div><a class="primary link-button" href="#/models/new">添加模型</a></div><p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="notice" class="notice" role="status">{{ notice }}</p><div v-if="!entries.length" class="empty-state"><h2>还没有模型配置</h2><p>添加并测试一个在线模型后，即可开始对话。</p><a href="#/models/new">添加模型</a></div><div v-else class="resource-grid"><article v-for="model in entries" :key="model.id" class="resource-card"><div class="resource-heading"><span class="resource-icon">M</span><span class="badge">{{ model.kind === 'CHAT' ? '对话' : '嵌入' }}</span><span v-if="model.isDefault" class="badge green">默认</span><span class="status-pill" :class="model.enabled ? 'ok' : ''">{{ model.enabled ? '启用' : '停用' }}</span></div><h2>{{ model.name }}</h2><p class="muted">{{ model.provider }} · {{ model.modelName }}</p><p class="small mono">{{ model.baseUrl }}</p><div class="card-actions"><button class="text-button" @click="setDefault(model)" :disabled="!model.enabled || model.isDefault">设为默认</button><a :href="`#/models/${model.id}`">编辑</a><button class="text-button danger" @click="remove(model)">删除</button></div></article></div></template>
    <template v-else><a class="back-link" href="#/models">← 返回模型配置</a><div class="page-heading"><div><p class="eyebrow">CONNECTION TEST REQUIRED</p><h1>{{ id === 'new' ? '添加模型' : '编辑模型' }}</h1><p class="muted">测试会真实请求模型接口；只有测试通过且表单未变化时才能保存。</p></div></div><form class="form-card model-form" @submit.prevent="save"><label>模型类型<select v-model="form.kind" :disabled="id !== 'new'"><option value="CHAT">对话模型</option><option value="EMBEDDING">嵌入模型</option></select></label><label>服务商预设<select v-model="providerKey" @change="chooseProvider"><option v-for="(value, key) in presets" :key="key" :value="key">{{ value.label }}</option></select></label><label>配置名称<input v-model.trim="form.name" required placeholder="例如：DeepSeek 主模型"></label><label>服务商标识<input v-model.trim="form.provider" required></label><label>API 基础地址<input v-model.trim="form.baseUrl" required type="url" placeholder="https://example.com/v1"></label><label>模型标识<input v-model.trim="form.modelName" required placeholder="服务端提供的模型名称"></label><label>API Key <span class="muted">{{ id === 'new' ? '按服务要求填写' : '留空沿用已保存密钥' }}</span><input v-model="form.apiKey" type="password" autocomplete="new-password"></label><label class="check-label"><input v-model="form.enabled" type="checkbox"> 启用此模型</label><div class="test-strip" :class="{ passed: tested }"><div><strong>{{ tested ? '连接测试通过' : '尚未测试' }}</strong><span>{{ tested ? `有效期至 ${new Date(tested.expiresAt).toLocaleTimeString('zh-CN', { hour12: false })}` : '修改任一字段后需要重新测试' }}</span></div><button type="button" class="secondary" :disabled="testing || busy" @click="testConnection">{{ testing ? '测试中…' : '测试连接' }}</button></div><p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="notice" class="notice" role="status">{{ notice }}</p><div class="form-actions"><button class="primary" :disabled="busy || !tested">{{ busy ? '保存中…' : '保存配置' }}</button><a class="secondary link-button" href="#/models">取消</a></div></form></template>
  </main>
</template>
