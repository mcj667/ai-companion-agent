/**
 * 解析 Spring MVC 对 Flux&lt;String&gt; + text/event-stream 的 SSE 输出。
 * 事件格式与仓库内 static/index.html 一致：data:片段 后跟空行。
 */
export function extractSseData(event: string): string {
  const pieces: string[] = []
  for (const rawLine of event.split('\n')) {
    const line = rawLine.replace(/\r$/, '')
    if (!line.startsWith('data:')) {
      continue
    }
    const payload = line.slice(5)
    pieces.push(payload.startsWith(' ') ? payload.slice(1) : payload)
  }
  return pieces.join('\n')
}

export async function readSseTextStream(
  response: Response,
  onChunk: (text: string) => void,
  signal?: AbortSignal,
): Promise<void> {
  if (!response.body) {
    throw new Error('当前浏览器不支持流式读取')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  const abort = () => {
    void reader.cancel()
  }
  signal?.addEventListener('abort', abort, { once: true })

  try {
    while (true) {
      if (signal?.aborted) {
        throw new DOMException('Aborted', 'AbortError')
      }
      const { value, done } = await reader.read()
      if (done) {
        break
      }
      buffer += decoder.decode(value, { stream: true })
      const events = buffer.split('\n\n')
      buffer = events.pop() ?? ''
      for (const event of events) {
        const text = extractSseData(event)
        if (text) {
          onChunk(text)
        }
      }
    }
    const tail = extractSseData(buffer)
    if (tail) {
      onChunk(tail)
    }
  } finally {
    signal?.removeEventListener('abort', abort)
    try {
      reader.releaseLock()
    } catch {
      // 已 cancel 时忽略
    }
  }
}
