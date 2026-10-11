import { computed, onMounted, ref } from 'vue'
import type { Session } from '../types/chat'
import { fetchSessions, removeSession } from '../api/love'
import { createId } from '../utils/id'
import { loadActiveSessionId, saveActiveSessionId } from '../storage/sessions'

function createSession(title = '新的对话'): Session {
  const now = Date.now()
  return {
    id: createId(),
    title,
    createdAt: now,
    updatedAt: now,
  }
}

export function useSessions() {
  // 初始是空的，挂载后立刻去服务端拉
  const sessions = ref<Session[]>([])
  const activeId = ref<string>(loadActiveSessionId() || '')
  const loading = ref(true)
  const error = ref('')

  const activeSession = computed(
    () => sessions.value.find((item) => item.id === activeId.value) ?? sessions.value[0],
  )

  function persistActive() {
    if (activeId.value) {
      saveActiveSessionId(activeId.value)
    }
  }

  /**
   * 从服务端拉会话列表。
   *
   * 注意一个反直觉的地方：**空白的新会话不会出现在列表里**。
   * 后端的名单是 RedisChatMemory.add() 写入的，也就是"会话必须至少聊过一句"才会露头。
   * 所以新建会话后不刷新，一旦刷新它就没了——ChatGPT 也是这个行为，不是 bug。
   */
  async function refresh() {
    loading.value = true
    error.value = ''
    try {
      const list = await fetchSessions()
      sessions.value = list

      if (list.length === 0) {
        // 服务端一条都没有：本地先摆一个空壳，等用户发言后它才会真正落到后端
        const draft = createSession()
        sessions.value = [draft]
        activeId.value = draft.id
      } else if (!list.some((item) => item.id === activeId.value)) {
        // 原来选中的那个在服务端已经不存在了（比如过期被清），退回到第一个
        activeId.value = list[0].id
      }
      persistActive()
    } catch (e) {
      error.value = e instanceof Error ? e.message : '加载会话列表失败'
    } finally {
      loading.value = false
    }
  }

  onMounted(() => {
    void refresh()
  })

  function selectSession(id: string) {
    activeId.value = id
    persistActive()
  }

  /** 新建会话：纯前端行为，不用调后端。第一次发言时后端会自动把它记进名单。 */
  function newSession() {
    const session = createSession()
    sessions.value = [session, ...sessions.value]
    activeId.value = session.id
    persistActive()
    return session
  }

  /** 第一句话发出去时改标题。本地先改，服务端那边的标题会在下次 refresh 时对齐。 */
  function touchSession(id: string, title?: string) {
    sessions.value = sessions.value.map((item) =>
      item.id === id
        ? {
            ...item,
            updatedAt: Date.now(),
            title: title?.trim() ? title.trim().slice(0, 28) : item.title,
          }
        : item,
    )
  }

  async function deleteSession(id: string) {
    try {
      // 删的是服务端 Redis：消息内容 + 会话名单一起清
      await removeSession(id)
    } catch (e) {
      error.value = e instanceof Error ? e.message : '删除失败'
    }

    const remaining = sessions.value.filter((item) => item.id !== id)
    if (remaining.length === 0) {
      const session = createSession()
      sessions.value = [session]
      activeId.value = session.id
    } else {
      sessions.value = remaining
      if (activeId.value === id) {
        activeId.value = remaining[0].id
      }
    }
    persistActive()
  }

  return {
    sessions,
    activeId,
    activeSession,
    loading,
    error,
    refresh,
    selectSession,
    newSession,
    touchSession,
    deleteSession,
  }
}
