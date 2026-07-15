export type ChatRole = 'user' | 'assistant'

export interface ChatMessage {
  id: string
  role: ChatRole
  content: string
  status: 'streaming' | 'complete' | 'error'
}

/** SSE 이벤트 계약 — 백엔드 ChatService의 이벤트 이름과 1:1. 런타임 검사와 타입이 이 상수 하나에서 파생된다. */
export const CHAT_STREAM_EVENTS = ['session', 'status', 'token', 'done', 'error'] as const

export interface ChatStreamEvent {
  event: (typeof CHAT_STREAM_EVENTS)[number]
  data: Record<string, unknown>
}
