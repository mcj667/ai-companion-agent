import type { ChatMessage, LoveReport, Session } from '../types/chat'
import { ApiError, buildLoveUrl, del, getJson, postJson, postText } from './http'
import { readSseTextStream } from '../utils/sse'

/** POST /api/love/chat — 一次性返回纯文本 */
export function chat(
  params: { message: string; chatId: string },
  signal?: AbortSignal,
): Promise<string> {
  return postText('/love/chat', params, signal)
}

/** POST /api/love/rag — 检索增强，一次性纯文本 */
export function rag(
  params: { message: string; chatId: string },
  signal?: AbortSignal,
): Promise<string> {
  return postText('/love/rag', params, signal)
}

/**
 * POST /api/love/search — 联网搜索模式，一次性纯文本。
 *
 * 返回的东西看起来跟 chat / rag 一模一样（就是一段文字），但后端做的事情完全不同：
 * 模型可以在这个过程中自己决定去调联网搜索、网页抓取工具，可能来回好几轮才写出答案。
 * 前端不需要知道这些，照常显示就好。
 */
export function search(
  params: { message: string; chatId: string },
  signal?: AbortSignal,
): Promise<string> {
  return postText('/love/search', params, signal)
}

/**
 * POST /api/love/mcp — MCP 模式，一次性纯文本。
 *
 * 前端完全看不出它和 /search 的区别（都是返回一段文字），但后端拿工具的途径不同：
 * /search 的工具是本项目自己写的 Java 方法，/mcp 的工具来自一个独立的 MCP 服务进程。
 * 对前端来说没有任何差别，所以这里不用做任何特殊处理。
 */
export function mcp(
  params: { message: string; chatId: string },
  signal?: AbortSignal,
): Promise<string> {
  return postText('/love/mcp', params, signal)
}

/** POST /api/love/report — LoveReport JSON */
export function report(
  params: { message: string; chatId: string },
  signal?: AbortSignal,
): Promise<LoveReport> {
  return postJson<LoveReport>('/love/report', params, signal)
}

/**
 * POST /api/love/chat/stream
 * Spring MVC SSE：EventSource 只能 GET，因此用 fetch + ReadableStream。
 */
export async function chatStream(
  params: { message: string; chatId: string },
  onChunk: (text: string) => void,
  signal?: AbortSignal,
): Promise<void> {
  const response = await fetch(buildLoveUrl('/love/chat/stream', params), {
    method: 'POST',
    headers: { Accept: 'text/event-stream' },
    signal,
  })
  if (!response.ok) {
    const text = await response.text()
    throw new ApiError(response.status, text)
  }
  await readSseTextStream(response, onChunk, signal)
}

// ───────────── 以下是会话历史相关，数据来自服务端 Redis ─────────────

/** GET /api/love/sessions — 会话列表，最近聊过的排前面 */
export function fetchSessions(signal?: AbortSignal): Promise<Session[]> {
  return getJson<Session[]>('/love/sessions', undefined, signal)
}

/** GET /api/love/history — 某个会话的历史消息，用来还原页面上的气泡 */
export function fetchHistory(chatId: string, signal?: AbortSignal): Promise<ChatMessage[]> {
  return getJson<ChatMessage[]>('/love/history', { chatId }, signal)
}

/** DELETE /api/love/sessions — 删除会话（Redis 里的消息和会话名单一起清） */
export function removeSession(chatId: string, signal?: AbortSignal): Promise<void> {
  return del('/love/sessions', { chatId }, signal)
}

export function formatLoveReport(report: LoveReport): string {
  const lines = [`## ${report.title || '情感建议报告'}`]
  if (Array.isArray(report.suggestions) && report.suggestions.length > 0) {
    for (const item of report.suggestions) {
      lines.push(`- ${item}`)
    }
  } else {
    lines.push('_模型没有返回建议条目。_')
  }
  return lines.join('\n')
}
