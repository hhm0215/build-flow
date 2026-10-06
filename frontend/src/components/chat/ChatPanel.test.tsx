import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ChatPanel from './ChatPanel'
import { getChatAvailability, streamChat } from '../../api/chat.api'

vi.mock('../../api/chat.api', () => ({
  getChatAvailability: vi.fn(),
  streamChat: vi.fn(),
}))

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><ChatPanel /></QueryClientProvider>)
  fireEvent.click(screen.getByRole('button', { name: 'AI 채팅 열기' }))
}

describe('ChatPanel availability', () => {
  beforeEach(() => {
    Element.prototype.scrollIntoView = vi.fn()
    vi.mocked(streamChat).mockReset()
    vi.mocked(getChatAvailability).mockReset()
  })

  it('모델이 없으면 원인을 표시하고 질문 전송을 막는다', async () => {
    vi.mocked(getChatAvailability).mockResolvedValue(false)
    renderPanel()

    expect(await screen.findByText('AI 모델이 현재 준비되지 않아 채팅을 사용할 수 없습니다.')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('질문 입력')).toBeDisabled()
    expect(screen.getByRole('button', { name: '질문 보내기' })).toBeDisabled()
    expect(streamChat).not.toHaveBeenCalled()
  })

  it('모델이 준비됐으면 질문 입력을 허용한다', async () => {
    vi.mocked(getChatAvailability).mockResolvedValue(true)
    renderPanel()

    await waitFor(() => expect(screen.getByPlaceholderText('질문 입력')).toBeEnabled())
  })
})
