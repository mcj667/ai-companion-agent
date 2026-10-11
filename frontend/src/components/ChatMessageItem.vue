<script setup lang="ts">
import { computed } from 'vue'
import type { ChatMessage } from '../types/chat'
import { renderMarkdown } from '../utils/markdown'

const props = defineProps<{
  message: ChatMessage
}>()

const html = computed(() => renderMarkdown(props.message.content || ''))

const modeLabel: Record<string, string> = {
  stream: '流式对话',
  chat: '普通对话',
  rag: '知识库问答',
  search: '联网搜索',
  mcp: 'MCP 工具',
  report: '结构化报告',
}
</script>

<template>
  <article class="msg" :class="message.role">
    <div class="avatar" aria-hidden="true">
      {{ message.role === 'user' ? '我' : '爱' }}
    </div>
    <div class="bubble">
      <div class="meta">
        <strong>{{ message.role === 'user' ? '你' : '小爱' }}</strong>
        <span v-if="message.mode">{{ modeLabel[message.mode] }}</span>
        <span v-if="message.status === 'pending'" class="tag">思考中</span>
        <span v-else-if="message.status === 'streaming'" class="tag pulse">正在输入</span>
        <span v-else-if="message.status === 'error'" class="tag err">出错</span>
      </div>
      <div
        v-if="message.role === 'assistant' && message.status === 'pending' && !message.content"
        class="pending"
      >
        正在生成回复…
      </div>
      <div
        v-else
        class="md"
        :class="{ error: message.status === 'error' }"
        v-html="html"
      />
    </div>
  </article>
</template>
