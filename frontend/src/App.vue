<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { checkHealth } from './api/health'
import ChatWindow from './components/ChatWindow.vue'
import Composer from './components/Composer.vue'
import SessionSidebar from './components/SessionSidebar.vue'
import { useChat } from './composables/useChat'
import { useSessions } from './composables/useSessions'
import type { ChatMode } from './types/chat'

const {
  sessions,
  activeId,
  loading: sessionsLoading,
  selectSession,
  newSession,
  touchSession,
  deleteSession,
} = useSessions()

const { messages, sending, pageError, send, stop } = useChat(
  () => activeId.value,
  (sessionId, title) => touchSession(sessionId, title),
)

const backendOnline = ref<boolean | null>(null)

onMounted(async () => {
  backendOnline.value = await checkHealth()
})

function handleSend(text: string, mode: ChatMode) {
  void send(text, mode)
}
</script>

<template>
  <div class="app-shell">
    <SessionSidebar
      :sessions="sessions"
      :active-id="activeId"
      :backend-online="backendOnline"
      :loading="sessionsLoading"
      @select="selectSession"
      @create="newSession"
      @remove="deleteSession"
    />
    <main class="main">
      <header class="topbar">
        <div>
          <h2>{{ sessions.find((item) => item.id === activeId)?.title || '对话' }}</h2>
          <p class="sub">chatId 会随请求发给后端，用于 Redis 会话记忆</p>
        </div>
      </header>
      <p v-if="pageError" class="banner" role="alert">{{ pageError }}</p>
      <ChatWindow :messages="messages" :sending="sending" />
      <Composer :sending="sending" @send="handleSend" @stop="stop" />
    </main>
  </div>
</template>
