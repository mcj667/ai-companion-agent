<script setup lang="ts">
import type { Session } from '../types/chat'

defineProps<{
  sessions: Session[]
  activeId: string
  backendOnline: boolean | null
  /** 正在从服务端拉会话列表。刷新页面时列表要等接口返回，先给个提示免得以为空了 */
  loading: boolean
}>()

const emit = defineEmits<{
  select: [id: string]
  create: []
  remove: [id: string]
}>()

function formatTime(value: number): string {
  return new Date(value).toLocaleString('zh-CN', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}
</script>

<template>
  <aside class="sidebar">
    <div class="brand">
      <div class="logo">爱</div>
      <div>
        <h1>小爱</h1>
        <p>AI 情感陪伴</p>
      </div>
    </div>

    <button class="new-chat" type="button" @click="emit('create')">新建对话</button>

    <p class="hint">
      会话列表和聊天记录都存在后端 Redis，刷新、换浏览器都还在。
      新会话要发出第一句话之后才会出现在这里。
    </p>

    <p v-if="loading" class="hint">正在从服务端加载会话…</p>

    <nav v-else class="list" aria-label="会话列表">
      <button
        v-for="session in sessions"
        :key="session.id"
        type="button"
        class="item"
        :class="{ active: session.id === activeId }"
        @click="emit('select', session.id)"
      >
        <span class="item-title">{{ session.title }}</span>
        <span class="item-meta">{{ formatTime(session.updatedAt) }}</span>
        <span
          class="item-delete"
          title="删除会话（服务端一并清除）"
          @click.stop="emit('remove', session.id)"
        >×</span>
      </button>
    </nav>

    <div class="status">
      <span class="dot" :class="backendOnline === true ? 'ok' : backendOnline === false ? 'bad' : 'wait'" />
      <span v-if="backendOnline === true">后端已连接</span>
      <span v-else-if="backendOnline === false">后端未连接 · 请先启动 8123</span>
      <span v-else>正在检测后端…</span>
    </div>
  </aside>
</template>
