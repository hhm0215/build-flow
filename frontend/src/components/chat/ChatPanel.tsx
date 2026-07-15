import { useEffect, useRef, useState } from 'react'
import { Bot, MessageCircle, RotateCcw, Send, Square, X } from 'lucide-react'
import ReactMarkdown from 'react-markdown'
import { streamChat } from '../../api/chat.api'
import type { ChatMessage, ChatStreamEvent } from '../../types/chat.types'

const SESSION_KEY = 'buildflow-chat-session'

// crypto.randomUUID는 보안 컨텍스트(HTTPS/localhost) 전용 — HTTP LAN 접속 대비 폴백
const genId = () => crypto.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`

export default function ChatPanel() {
  const [open, setOpen] = useState(false)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [phase, setPhase] = useState('')
  const [streaming, setStreaming] = useState(false)
  const abortRef = useRef<AbortController | null>(null)
  const bottomRef = useRef<HTMLDivElement>(null)

  // 스트리밍 중에는 토큰마다 실행되므로 smooth 애니메이션 재시작 비용을 피한다
  useEffect(
    () => bottomRef.current?.scrollIntoView({ behavior: streaming ? 'auto' : 'smooth' }),
    [messages, phase, streaming],
  )
  useEffect(() => () => abortRef.current?.abort(), [])

  const updateAssistant = (id: string, updater: (message: ChatMessage) => ChatMessage) => {
    setMessages((current) => current.map((message) => message.id === id ? updater(message) : message))
  }

  const handleEvent = (event: ChatStreamEvent, assistantId: string) => {
    if (event.event === 'session') {
      sessionStorage.setItem(SESSION_KEY, String(event.data.sessionId))
    } else if (event.event === 'status') {
      const labels: Record<string, string> = { thinking: '질문을 분석하고 있습니다', tool: '현장 데이터를 확인하고 있습니다', answering: '답변을 작성하고 있습니다' }
      setPhase(labels[String(event.data.phase)] ?? '')
    } else if (event.event === 'token') {
      updateAssistant(assistantId, (message) => ({ ...message, content: message.content + String(event.data.content ?? '') }))
    } else if (event.event === 'error') {
      throw new Error(String(event.data.message ?? '답변 생성에 실패했습니다.'))
    } else if (event.event === 'done') {
      updateAssistant(assistantId, (message) => ({ ...message, status: 'complete' }))
      setPhase('')
    }
  }

  const sendMessage = async () => {
    const text = input.trim()
    if (!text || streaming) return
    const userId = genId()
    const assistantId = genId()
    const controller = new AbortController()
    abortRef.current = controller
    setInput('')
    setStreaming(true)
    setMessages((current) => [
      ...current,
      { id: userId, role: 'user', content: text, status: 'complete' },
      { id: assistantId, role: 'assistant', content: '', status: 'streaming' },
    ])

    try {
      await streamChat(text, sessionStorage.getItem(SESSION_KEY), controller.signal, (event) => handleEvent(event, assistantId))
    } catch (error) {
      if (!controller.signal.aborted) {
        const message = error instanceof Error ? error.message : '답변 생성에 실패했습니다.'
        updateAssistant(assistantId, (current) => ({ ...current, content: current.content || message, status: 'error' }))
      } else {
        updateAssistant(assistantId, (current) => ({ ...current, content: current.content || '답변 생성을 중단했습니다.', status: 'error' }))
      }
    } finally {
      setStreaming(false)
      setPhase('')
      abortRef.current = null
    }
  }

  const reset = () => {
    abortRef.current?.abort()
    sessionStorage.removeItem(SESSION_KEY)
    setMessages([])
    setPhase('')
    setStreaming(false) // abort rejection 정착 전에 입력이 막히지 않도록 즉시 해제
  }

  return (
    <>
      <button className="chat-launcher" onClick={() => setOpen(true)} aria-label="AI 채팅 열기" title="AI 채팅">
        <MessageCircle size={21} />
      </button>
      {open && (
        <section className="chat-panel" aria-label="AI 현장 비서">
          <header className="chat-header">
            <div className="chat-title"><Bot size={18} /><span>AI 현장 비서</span></div>
            <div className="chat-actions">
              <button onClick={reset} aria-label="새 대화" title="새 대화"><RotateCcw size={16} /></button>
              <button onClick={() => setOpen(false)} aria-label="채팅 닫기" title="닫기"><X size={18} /></button>
            </div>
          </header>
          <div className="chat-messages">
            {!messages.length && <div className="chat-empty"><Bot size={28} /><p>현장 손익, 미수금, 진행 상태를 물어보세요.</p></div>}
            {messages.map((message) => (
              <div key={message.id} className={`chat-message ${message.role} ${message.status}`}>
                {message.role === 'assistant' && message.status === 'complete'
                  ? <ReactMarkdown>{message.content}</ReactMarkdown>
                  : <span>{message.content || (message.status === 'streaming' ? '...' : '')}</span>}
              </div>
            ))}
            {phase && <div className="chat-phase">{phase}</div>}
            <div ref={bottomRef} />
          </div>
          <div className="chat-composer">
            <textarea
              value={input}
              onChange={(event) => setInput(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && !event.shiftKey) {
                  event.preventDefault()
                  void sendMessage()
                }
              }}
              placeholder="질문 입력"
              rows={2}
              disabled={streaming}
            />
            {streaming
              ? <button className="chat-send stop" onClick={() => abortRef.current?.abort()} aria-label="답변 중단"><Square size={16} fill="currentColor" /></button>
              : <button className="chat-send" onClick={() => void sendMessage()} disabled={!input.trim()} aria-label="질문 보내기"><Send size={17} /></button>}
          </div>
        </section>
      )}
    </>
  )
}
