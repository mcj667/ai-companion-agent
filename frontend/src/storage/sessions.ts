/**
 * 本地只存"当前选中哪个会话"这一个纯 UI 状态。
 *
 * 会话列表和聊天记录都改走服务端 Redis 了：
 *   GET    /love/sessions  → 会话列表
 *   GET    /love/history   → 某个会话的消息气泡
 *   DELETE /love/sessions  → 删除会话
 *
 * 以前这里用 localStorage 存了一份消息，问题是：换浏览器就丢、清缓存就丢，
 * 而且和 Redis 里那份是两份互相独立的数据，会越用越对不上。
 * 现在服务端是唯一的数据源。
 */

const ACTIVE_KEY = 'ai-agent.active-session'

export function loadActiveSessionId(): string | null {
  return localStorage.getItem(ACTIVE_KEY)
}

export function saveActiveSessionId(id: string): void {
  localStorage.setItem(ACTIVE_KEY, id)
}
