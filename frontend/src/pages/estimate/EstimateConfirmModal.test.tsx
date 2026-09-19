import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import EstimateConfirmModal from './EstimateConfirmModal'
import type { Estimate } from '../../types'

const mutation = vi.hoisted(() => ({ mutateAsync: vi.fn(), isPending: false }))

vi.mock('../../api/estimates.api', () => ({
  useConfirmEstimate: () => mutation,
}))

const draft: Estimate = {
  id: 17,
  siteId: 3,
  title: '현장 추가공사',
  status: 'DRAFT',
  estimateDate: '2026-09-19',
  totalAmount: 120000,
  memo: '',
  items: [],
  createdAt: '2026-09-19T00:00:00',
  updatedAt: '2026-09-19T00:00:00',
}

beforeEach(() => {
  mutation.mutateAsync.mockReset()
  mutation.isPending = false
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false,
    media: query,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }))
  const getComputedStyle = window.getComputedStyle.bind(window)
  vi.spyOn(window, 'getComputedStyle').mockImplementation((element) => getComputedStyle(element))
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('EstimateConfirmModal', () => {
  it('취소하면 확정 API를 호출하지 않는다', () => {
    const onClose = vi.fn()
    render(<EstimateConfirmModal estimate={draft} onClose={onClose} />)

    expect(screen.getByText(/확정 후에는 수정할 수 없습니다/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '취소' }))

    expect(onClose).toHaveBeenCalledOnce()
    expect(mutation.mutateAsync).not.toHaveBeenCalled()
  })

  it('성공하면 해당 견적 ID로 확정하고 닫는다', async () => {
    mutation.mutateAsync.mockResolvedValue({ ...draft, status: 'CONFIRMED' })
    const onClose = vi.fn()
    render(<EstimateConfirmModal estimate={draft} onClose={onClose} />)

    fireEvent.click(screen.getByRole('button', { name: '확정' }))

    await waitFor(() => expect(mutation.mutateAsync).toHaveBeenCalledWith(17))
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('서버 오류를 보여주고 재시도를 허용한다', async () => {
    mutation.mutateAsync
      .mockRejectedValueOnce({ response: { data: { error: '이미 확정된 견적서입니다.' } } })
      .mockResolvedValueOnce({ ...draft, status: 'CONFIRMED' })
    const onClose = vi.fn()
    render(<EstimateConfirmModal estimate={draft} onClose={onClose} />)

    fireEvent.click(screen.getByRole('button', { name: '확정' }))
    expect(await screen.findByText('이미 확정된 견적서입니다.')).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: '확정' }))
    await waitFor(() => expect(mutation.mutateAsync).toHaveBeenCalledTimes(2))
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('처리 중 버튼을 비활성화한다', () => {
    mutation.isPending = true
    render(<EstimateConfirmModal estimate={draft} onClose={vi.fn()} />)

    expect(screen.getByRole('button', { name: /확정/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: '취소' })).toBeDisabled()
  })

  it('빠르게 두 번 눌러도 요청을 한 번만 보낸다', async () => {
    let complete: (() => void) | undefined
    mutation.mutateAsync.mockReturnValue(new Promise<void>((resolve) => { complete = resolve }))
    render(<EstimateConfirmModal estimate={draft} onClose={vi.fn()} />)

    const confirm = screen.getByRole('button', { name: '확정' })
    fireEvent.click(confirm)
    fireEvent.click(confirm)

    expect(mutation.mutateAsync).toHaveBeenCalledTimes(1)
    complete?.()
  })
})
