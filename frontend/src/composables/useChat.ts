import { onUnmounted, ref, watch } from 'vue'
import { chat, chatStream, fetchHistory, formatLoveReport, mcp, rag, report, search } from '../api/love'
import type { ChatMessage, ChatMode } from '../types/chat'
import { createId } from '../utils/id'

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException
    ? error.name === 'AbortError'
    : error instanceof Error && error.name === 'AbortError'
}

export function useChat(getSessionId: () => string, onTitleHint?: (sessionId: string, title: string) => void) {
  const messages = ref<ChatMessage[]>([])
  const sending = ref(false)
  const pageError = ref('')
  let abortController: AbortController | null = null
  let loadedSessionId = ''

  /**
   * 从服务端 Redis 拉这个会话的历史消息。
   *
   * 以前是读 localStorage，现在服务端是唯一数据源：换浏览器、换设备，记录都还在，
   * 而且你看到的就是模型上下文里那份数据，两边不会对不上。
   */
  async function load(sessionId: string) {
    loadedSessionId = sessionId
    pageError.value = ''

    if (!sessionId) {
      messages.value = []
      return
    }

    try {
      const history = await fetchHistory(sessionId)
      // 请求是异步的，这期间用户可能已经切到别的会话了，那就别拿旧数据覆盖
      if (loadedSessionId !== sessionId) {
        return
      }
      messages.value = history
    } catch (e) {
      if (loadedSessionId !== sessionId) {
        return
      }
      messages.value = []
      pageError.value = e instanceof Error ? e.message : '加载历史消息失败'
    }
  }

  watch(
    getSessionId,
    (sessionId) => {
      // 切会话时把正在生成的那条停掉，否则回复会写到新会话的界面上
      if (sending.value) {
        abortController?.abort()
      }
      void load(sessionId)
    },
    { immediate: true },
  )

  async function send(text: string, mode: ChatMode) {
    const content = text.trim()
    if (!content || sending.value) {
      return
    }

    const sessionId = getSessionId()
    pageError.value = ''
    const userMessage: ChatMessage = {
      id: createId(),
      role: 'user',
      content,
      status: 'done',
      mode,
    }
    const assistantMessage: ChatMessage = {
      id: createId(),
      role: 'assistant',
      content: '',
      status: mode === 'stream' ? 'streaming' : 'pending',
      mode,
    }

    messages.value = [...messages.value, userMessage, assistantMessage]
    if (messages.value.filter((item) => item.role === 'user').length === 1) {
      onTitleHint?.(sessionId, content)
    }

    abortController = new AbortController()
    sending.value = true
    const signal = abortController.signal

    const patchAssistant = (partial: Partial<ChatMessage>) => {
      if (loadedSessionId !== sessionId) {
        return
      }
      messages.value = messages.value.map((item) =>
        item.id === assistantMessage.id ? { ...item, ...partial } : item,
      )
    }

    try {
      if (mode === 'stream') {
        await chatStream(
          { message: content, chatId: sessionId },
          (chunk) => {
            const current = messages.value.find((item) => item.id === assistantMessage.id)
            patchAssistant({
              content: (current?.content ?? '') + chunk,
              status: 'streaming',
            })
          },
          signal,
        )
        patchAssistant({ status: 'done' })
      } else if (mode === 'chat') {
        const reply = await chat({ message: content, chatId: sessionId }, signal)
        patchAssistant({ content: reply, status: 'done' })
      } else if (mode === 'rag') {
        const reply = await rag({ message: content, chatId: sessionId }, signal)
        patchAssistant({ content: reply, status: 'done' })
      } else if (mode === 'search') {
        // 注意这一支要放在最后一个 else 前面：report 是靠 else 兜底的，
        // 插到 else 后面的话报告模式会被这一支抢走
        const reply = await search({ message: content, chatId: sessionId }, signal)
        patchAssistant({ content: reply, status: 'done' })
      } else if (mode === 'mcp') {
        const reply = await mcp({ message: content, chatId: sessionId }, signal)
        patchAssistant({ content: reply, status: 'done' })
      } else {
        const result = await report({ message: content, chatId: sessionId }, signal)
        patchAssistant({ content: formatLoveReport(result), status: 'done' })
      }
    } catch (error) {
      if (isAbortError(error)) {
        const current = messages.value.find((item) => item.id === assistantMessage.id)
        patchAssistant({
          status: current?.content ? 'done' : 'error',
          content: current?.content || '已停止生成',
        })
      } else {
        const message = error instanceof Error ? error.message : '发送失败'
        pageError.value = message
        patchAssistant({
          status: 'error',
          content: message,
        })
      }
    } finally {
      // 这里不用再存本地了：Advisor 在你收到回复之前，就已经把这一轮
      // 用户消息 + 模型回复写进 Redis 了（MessageChatMemoryAdvisor.after()）。
      sending.value = false
      abortController = null
    }
  }

  function stop() {
    abortController?.abort()
  }

  onUnmounted(() => {
    abortController?.abort()
  })

  return {
    messages,
    sending,
    pageError,
    send,
    stop,
  }
}
