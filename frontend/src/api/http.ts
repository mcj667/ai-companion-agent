import { API_BASE } from './config'

export class ApiError extends Error {
  readonly status: number
  readonly body: string

  constructor(status: number, body: string) {
    super(formatApiError(status, body))
    this.name = 'ApiError'
    this.status = status
    this.body = body
  }
}

function formatApiError(status: number, body: string): string {
  const trimmed = body.trim()
  if (!trimmed) {
    return `请求失败（HTTP ${status}）`
  }
  try {
    const parsed = JSON.parse(trimmed) as {
      message?: string
      error?: string
      status?: number
    }
    if (parsed.message) {
      return parsed.message
    }
    if (parsed.error) {
      return typeof parsed.status === 'number'
        ? `${parsed.error}（HTTP ${parsed.status}）`
        : parsed.error
    }
  } catch {
    // 后端可能返回纯文本或 HTML
  }
  const preview = trimmed.replace(/\s+/g, ' ').slice(0, 280)
  return `请求失败（HTTP ${status}）：${preview}`
}

export function buildLoveUrl(
  path: string,
  params: { message: string; chatId: string },
): string {
  const query = new URLSearchParams({
    message: params.message,
    chatId: params.chatId,
  })
  return `${API_BASE}${path}?${query.toString()}`
}

/** 拼出带查询串的地址；没有参数时不要留一个光秃秃的 "?" */
function withQuery(path: string, params?: Record<string, string>): string {
  const query = new URLSearchParams(params ?? {}).toString()
  return query ? `${API_BASE}${path}?${query}` : `${API_BASE}${path}`
}

async function parseJson<T>(response: Response, text: string): Promise<T> {
  if (!response.ok) {
    throw new ApiError(response.status, text)
  }
  try {
    return JSON.parse(text) as T
  } catch {
    throw new ApiError(response.status, `无法解析 JSON 响应：${text.slice(0, 200)}`)
  }
}

/** GET 请求，返回 JSON */
export async function getJson<T>(
  path: string,
  params?: Record<string, string>,
  signal?: AbortSignal,
): Promise<T> {
  const response = await fetch(withQuery(path, params), { signal })
  return parseJson<T>(response, await response.text())
}

/** DELETE 请求，只关心成功与否 */
export async function del(
  path: string,
  params?: Record<string, string>,
  signal?: AbortSignal,
): Promise<void> {
  const response = await fetch(withQuery(path, params), { method: 'DELETE', signal })
  if (!response.ok) {
    throw new ApiError(response.status, await response.text())
  }
}

export async function postText(
  path: string,
  params: { message: string; chatId: string },
  signal?: AbortSignal,
): Promise<string> {
  const response = await fetch(buildLoveUrl(path, params), {
    method: 'POST',
    signal,
  })
  const text = await response.text()
  if (!response.ok) {
    throw new ApiError(response.status, text)
  }
  return text
}

export async function postJson<T>(
  path: string,
  params: { message: string; chatId: string },
  signal?: AbortSignal,
): Promise<T> {
  const response = await fetch(buildLoveUrl(path, params), {
    method: 'POST',
    signal,
  })
  const text = await response.text()
  if (!response.ok) {
    throw new ApiError(response.status, text)
  }
  try {
    return JSON.parse(text) as T
  } catch {
    throw new ApiError(response.status, `无法解析 JSON 响应：${text.slice(0, 200)}`)
  }
}
