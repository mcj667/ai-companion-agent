<script setup lang="ts">
import { ref } from 'vue'
import type { ChatMode } from '../types/chat'

defineProps<{
  sending: boolean
}>()

const emit = defineEmits<{
  send: [text: string, mode: ChatMode]
  stop: []
}>()

const text = ref('')
const mode = ref<ChatMode>('stream')

function submit() {
  const value = text.value.trim()
  if (!value) {
    return
  }
  emit('send', value, mode.value)
  text.value = ''
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    if (!event.isComposing) {
      submit()
    }
  }
}
</script>

<template>
  <footer class="composer">
    <div class="modes" role="radiogroup" aria-label="对话模式">
      <label><input v-model="mode" type="radio" value="stream" /> 流式对话</label>
      <label><input v-model="mode" type="radio" value="chat" /> 普通对话</label>
      <label><input v-model="mode" type="radio" value="rag" /> 知识库 RAG</label>
      <label><input v-model="mode" type="radio" value="search" /> 联网搜索</label>
      <label><input v-model="mode" type="radio" value="mcp" /> MCP 工具</label>
      <label><input v-model="mode" type="radio" value="report" /> 结构化报告</label>
    </div>
    <div class="box">
      <textarea
        v-model="text"
        rows="3"
        :disabled="sending"
        placeholder="输入你的问题，Enter 发送，Shift+Enter 换行"
        @keydown="onKeydown"
      />
      <button v-if="sending" type="button" class="stop" @click="emit('stop')">停止</button>
      <button v-else type="button" class="send" :disabled="!text.trim()" @click="submit">发送</button>
    </div>
  </footer>
</template>
