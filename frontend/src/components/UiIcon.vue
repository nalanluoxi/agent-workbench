<script setup>
defineProps({ name: String, size: { type: Number, default: 18 } });
</script>
<template>
  <svg class="ui-icon" :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <template v-if="name === 'dashboard'"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="4" rx="1"/><rect x="14" y="11" width="7" height="10" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/></template>
    <template v-else-if="name === 'chat'"><path d="M5 18.5 3.5 21l4.4-1.4A9 9 0 1 0 4.4 16"/><path d="M8 10h8M8 14h5"/></template>
    <template v-else-if="name === 'model'"><path d="M12 3 4 7v10l8 4 8-4V7l-8-4Z"/><path d="m4 7 8 4 8-4M12 11v10"/></template>
    <template v-else-if="name === 'tools'"><path d="M14.5 6.5a4 4 0 0 0-5.3 5.3L4 17l3 3 5.2-5.2a4 4 0 0 0 5.3-5.3l-2.4 2.4-3-3 2.4-2.4Z"/></template>
    <template v-else-if="name === 'database'"><ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v6c0 1.7 3.6 3 8 3s8-1.3 8-3V5M4 11v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6"/></template>
    <template v-else-if="name === 'skill'"><path d="m12 3 2.1 4.4L19 8l-3.5 3.4.8 4.8L12 14l-4.3 2.2.8-4.8L5 8l4.9-.6L12 3Z"/><path d="M9 20h6"/></template>
    <template v-else-if="name === 'mcp'"><path d="M8 12a4 4 0 0 1 4-4h2M16 12a4 4 0 0 1-4 4h-2"/><path d="M15 5h4v4M9 19H5v-4"/></template>
    <template v-else-if="name === 'memory'"><path d="M9 4.5A3 3 0 0 0 4.5 7v2A3 3 0 0 0 3 14.5 3.5 3.5 0 0 0 7 18a3 3 0 0 0 5 1.5v-15a3 3 0 0 0-3-3Z"/><path d="M15 4.5A3 3 0 0 1 19.5 7v2a3 3 0 0 1 1.5 5.5 3.5 3.5 0 0 1-4 3.5 3 3 0 0 1-5 1.5v-15a3 3 0 0 1 3-3Z"/></template>
    <template v-else-if="name === 'plus'"><path d="M12 5v14M5 12h14"/></template>
    <template v-else><circle cx="12" cy="12" r="8"/><path d="M12 8v4l3 2"/></template>
  </svg>
</template>
