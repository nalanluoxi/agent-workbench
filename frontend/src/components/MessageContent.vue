<script setup>
import { computed } from 'vue';

const props = defineProps({ content: { type: String, default: '' } });

function inline(text) {
  const parts = [];
  const pattern = /(\*\*[^*]+\*\*|`[^`\n]+`)/g;
  let cursor = 0;
  for (const match of text.matchAll(pattern)) {
    if (match.index > cursor) parts.push({ type: 'text', text: text.slice(cursor, match.index) });
    const value = match[0];
    parts.push(value.startsWith('**')
      ? { type: 'strong', text: value.slice(2, -2) }
      : { type: 'code', text: value.slice(1, -1) });
    cursor = match.index + value.length;
  }
  if (cursor < text.length) parts.push({ type: 'text', text: text.slice(cursor) });
  return parts;
}

const blocks = computed(() => {
  const lines = props.content.replace(/\r\n/g, '\n').split('\n');
  const result = [];
  let paragraph = [];
  let list = [];
  let code = null;
  let language = '';
  const flushParagraph = () => {
    if (paragraph.length) result.push({ type: 'paragraph', parts: inline(paragraph.join('\n')) });
    paragraph = [];
  };
  const flushList = () => {
    if (list.length) result.push({ type: 'list', items: list.map(inline) });
    list = [];
  };
  for (const line of lines) {
    if (code !== null) {
      if (line.trim().startsWith('```')) {
        result.push({ type: 'code', text: code.join('\n'), language });
        code = null; language = '';
      } else code.push(line);
      continue;
    }
    if (line.trim().startsWith('```')) {
      flushParagraph(); flushList(); code = []; language = line.trim().slice(3).trim(); continue;
    }
    const item = line.match(/^\s*[-*]\s+(.+)$/);
    if (item) { flushParagraph(); list.push(item[1]); continue; }
    if (!line.trim()) { flushParagraph(); flushList(); continue; }
    flushList(); paragraph.push(line);
  }
  if (code !== null) result.push({ type: 'code', text: code.join('\n'), language });
  flushParagraph(); flushList();
  return result;
});
</script>

<template>
  <div class="rich-message">
    <template v-for="(block, blockIndex) in blocks" :key="blockIndex">
      <pre v-if="block.type === 'code'" class="message-code"><code>{{ block.text }}</code></pre>
      <ul v-else-if="block.type === 'list'">
        <li v-for="(item, itemIndex) in block.items" :key="itemIndex"><template v-for="(part, index) in item" :key="index"><strong v-if="part.type === 'strong'">{{ part.text }}</strong><code v-else-if="part.type === 'code'">{{ part.text }}</code><template v-else>{{ part.text }}</template></template></li>
      </ul>
      <p v-else><template v-for="(part, index) in block.parts" :key="index"><strong v-if="part.type === 'strong'">{{ part.text }}</strong><code v-else-if="part.type === 'code'">{{ part.text }}</code><template v-else>{{ part.text }}</template></template></p>
    </template>
  </div>
</template>
