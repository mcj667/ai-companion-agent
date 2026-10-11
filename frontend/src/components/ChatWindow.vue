<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import type { ChatMessage } from '../types/chat'
import ChatMessageItem from './ChatMessageItem.vue'

const props = defineProps<{
  messages: ChatMessage[]
  sending: boolean
}>()

const scroller = ref<HTMLElement | null>(null)

async function scrollToBottom() {
  await nextTick()
  const el = scroller.value
  if (el) {
    el.scrollTop = el.scrollHeight
  }
}

watch(
  () => [props.messages.length, props.messages.at(-1)?.content, props.sending],
  () => {
    void scrollToBottom()
  },
)
</script>

<template>
  <section ref="scroller" class="chat-window" aria-live="polite">
    <div v-if="messages.length === 0" class="empty">
      <div class="empty-mark">小爱</div>
      <h2>你好，我是情感陪伴助手</h2>
      <p>把你的情感状态或具体困扰发给我。同一会话会沿用后端 Redis 记忆（chatId）。</p>
      <ul>
        <li>怎样提升自身魅力吸引潜在伴侣？</li>
        <li>异地恋总是因为小事吵架，怎么办？</li>
        <li>给我一份改善沟通的建议报告</li>
      </ul>
    </div>
    <ChatMessageItem v-for="message in messages" :key="message.id" :message="message" />
  </section>
</template>
