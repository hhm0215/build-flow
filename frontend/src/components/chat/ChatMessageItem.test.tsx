import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import ChatMessageItem from './ChatMessageItem'

describe('ChatMessageItem', () => {
  it('완료된 답변만 Markdown으로 그린다', () => {
    const { container } = render(
      <ChatMessageItem message={{ id: 'a', role: 'assistant', content: '마진 **12%**', status: 'complete' }} />,
    )
    expect(container.querySelector('strong')?.textContent).toBe('12%')
  })

  it('스트리밍 중 답변은 원문 그대로, 빈 내용은 진행 표시로 그린다', () => {
    const { container, rerender } = render(
      <ChatMessageItem message={{ id: 'a', role: 'assistant', content: '마진 **12', status: 'streaming' }} />,
    )
    expect(container.querySelector('strong')).toBeNull()
    expect(container.textContent).toBe('마진 **12')

    rerender(<ChatMessageItem message={{ id: 'a', role: 'assistant', content: '', status: 'streaming' }} />)
    expect(container.textContent).toBe('...')
  })

  it('일부 답변 뒤 실패하면 받은 부분과 끊김 사유를 함께 보인다', () => {
    const { container, getByRole } = render(
      <ChatMessageItem message={{ id: 'a', role: 'assistant', content: '마진은 12', status: 'error', errorNote: '응답 시간이 초과되었습니다.' }} />,
    )
    expect(container.textContent).toContain('마진은 12')
    expect(getByRole('alert').textContent).toBe('답변이 중간에 끊겼습니다. 응답 시간이 초과되었습니다.')
  })
})
