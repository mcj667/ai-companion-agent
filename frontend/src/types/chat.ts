export type ChatMode = 'stream' | 'chat' | 'rag' | 'search' | 'mcp' | 'report'

export type MessageStatus = 'pending' | 'streaming' | 'done' | 'error'

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  status: MessageStatus
  mode?: ChatMode
}

export interface Session {
  id: string
  title: string
  createdAt: number
  updatedAt: number
}

export interface LoveReport {
  title: string
  suggestions: string[]
}
