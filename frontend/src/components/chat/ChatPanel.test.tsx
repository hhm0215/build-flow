import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
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

describe('ChatPanel 스크롤 effect (scrollIntoView가 Promise 반환)', () => {
  beforeEach(() => {
    // 최신 Chromium처럼 scrollIntoView가 Promise를 반환해도 effect 정리 단계가 깨지지 않아야 한다
    Element.prototype.scrollIntoView = vi.fn(() => Promise.resolve()) as unknown as Element['scrollIntoView']
    vi.mocked(streamChat).mockReset()
    vi.mocked(getChatAvailability).mockReset()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('스트리밍 렌더·완료·언마운트가 예외 없이 동작한다', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.mocked(getChatAvailability).mockResolvedValue(true)
    let finish: () => void = () => {}
    vi.mocked(streamChat).mockImplementation(async (_message, _session, _signal, onEvent) => {
      onEvent({ event: 'session', data: { sessionId: 7 } })
      onEvent({ event: 'status', data: { phase: 'answering' } })
      onEvent({ event: 'token', data: { content: '응답 첫 조각' } })
      await new Promise<void>((resolve) => { finish = resolve })
      onEvent({ event: 'token', data: { content: ' 마지막' } })
      onEvent({ event: 'done', data: {} })
    })

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const view = render(<QueryClientProvider client={client}><ChatPanel /></QueryClientProvider>)
    fireEvent.click(screen.getByRole('button', { name: 'AI 채팅 열기' }))
    const input = screen.getByPlaceholderText('질문 입력')
    await waitFor(() => expect(input).toBeEnabled())
    fireEvent.change(input, { target: { value: '안녕하세요' } })
    fireEvent.click(screen.getByRole('button', { name: '질문 보내기' }))

    expect(await screen.findByText('응답 첫 조각')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '답변 중단' })).toBeInTheDocument()
    await act(async () => { finish() })
    expect(await screen.findByText('응답 첫 조각 마지막')).toBeInTheDocument()
    expect(screen.getByText('안녕하세요')).toBeInTheDocument()
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled()

    expect(() => view.unmount()).not.toThrow()
    const effectErrors = consoleError.mock.calls.filter((args) => /effect|destroy|not a function/i.test(String(args[0])))
    expect(effectErrors).toEqual([])
  })
})
