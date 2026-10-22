import { memo } from 'react'
import ReactMarkdown from 'react-markdown'
import type { ChatMessage } from '../../types/chat.types'

// 스트리밍 조각마다 바뀌는 건 마지막 답변 객체뿐이다 — 이전 메시지(특히 Markdown 파싱)는 다시 그리지 않는다
function ChatMessageItem({ message }: { message: ChatMessage }) {
  return (
    <div className={`chat-message ${message.role} ${message.status}`}>
      {message.role === 'assistant' && message.status === 'complete'
        ? <ReactMarkdown>{message.content}</ReactMarkdown>
        : <span>{message.content || (message.status === 'streaming' ? '...' : '')}</span>}
    </div>
  )
}

export default memo(ChatMessageItem)
