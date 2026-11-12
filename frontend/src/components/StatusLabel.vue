<script setup>
import { computed } from 'vue';
import { statusMeta } from '../status-meta.js';
const props = defineProps({ code: { type: [String, Number], default: '' }, compact: Boolean });
const meta = computed(() => statusMeta(props.code));
</script>

<template>
  <span class="status-label" :class="[`tone-${meta.tone}`, { compact }]" :title="meta.known ? `${meta.label}（${meta.raw}）` : `未识别的原始状态：${meta.raw}`">
    <i aria-hidden="true"></i><span>{{ meta.label }}</span><small v-if="meta.raw">· {{ meta.raw }}</small>
  </span>
</template>
