export type ChatRole = 'user' | 'assistant'

export interface ChatMessage {
  id: string
  role: ChatRole
  content: string
  status: 'streaming' | 'complete' | 'error'
  /** 일부 답변을 받은 뒤 실패·중단했을 때만 — 받은 부분과 실패 사유를 함께 보여준다 */
  errorNote?: string
}

/** SSE 이벤트 계약 — 백엔드 ChatService의 이벤트 이름과 1:1. 런타임 검사와 타입이 이 상수 하나에서 파생된다. */
export const CHAT_STREAM_EVENTS = ['session', 'status', 'token', 'done', 'error'] as const

export interface ChatStreamEvent {
  event: (typeof CHAT_STREAM_EVENTS)[number]
  data: Record<string, unknown>
}
