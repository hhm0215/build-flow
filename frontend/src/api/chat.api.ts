import { useAuthStore } from '../stores/authStore'
import { handleSessionExpired } from './axiosInstance'
import { CHAT_STREAM_EVENTS } from '../types/chat.types'
import type { ChatStreamEvent } from '../types/chat.types'

function parseFrame(frame: string): ChatStreamEvent | null {
  let event = 'message'
  const data: string[] = []

  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
  }

  if (!data.length || !(CHAT_STREAM_EVENTS as readonly string[]).includes(event)) return null
  return { event: event as ChatStreamEvent['event'], data: JSON.parse(data.join('\n')) }
}

export async function consumeSseStream(
  stream: ReadableStream<Uint8Array>,
  onEvent: (event: ChatStreamEvent) => void,
) {
  const reader = stream.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  try {
    while (true) {
      const { value, done } = await reader.read()
      buffer += decoder.decode(value, { stream: !done })
      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop() ?? ''
      frames.map(parseFrame).filter((event): event is ChatStreamEvent => event !== null).forEach(onEvent)
      if (done) break
    }

    const trailing = parseFrame(buffer)
    if (trailing) onEvent(trailing)
  } finally {
    // onEvent가 던져도(서버 error 이벤트) 연결을 정리한다 — 정상 종료 후에는 no-op
    await reader.cancel().catch(() => {})
  }
}

export async function streamChat(
  message: string,
  sessionId: string | null,
  signal: AbortSignal,
  onEvent: (event: ChatStreamEvent) => void,
) {
  const token = useAuthStore.getState().accessToken
  const response = await fetch('/api/v1/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({ sessionId, message }),
    signal,
  })

  if (response.status === 401) {
    handleSessionExpired()
    throw new Error('인증이 만료되었습니다.')
  }
  if (!response.ok || !response.body) throw new Error('채팅 서비스에 연결할 수 없습니다.')
  await consumeSseStream(response.body, onEvent)
}
