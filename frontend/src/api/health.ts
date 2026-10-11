import { API_BASE } from './config'

export async function checkHealth(): Promise<boolean> {
  try {
    const response = await fetch(`${API_BASE}/health`, { method: 'GET' })
    const text = await response.text()
    return response.ok && text.trim() === 'ok'
  } catch {
    return false
  }
}
