import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Estimate } from '../../types'
import EstimateDeleteModal from './EstimateDeleteModal'

const mutateAsync = vi.hoisted(() => vi.fn())

vi.mock('../../api/estimates.api', () => ({
  useDeleteEstimate: () => ({ mutateAsync, isPending: false }),
}))

const estimate = {
  id: 9, siteId: 3, title: '초안 견적', status: 'DRAFT', estimateDate: '2026-09-19',
  totalAmount: 1000, memo: '', items: [], createdAt: '', updatedAt: '',
} satisfies Estimate

beforeEach(() => {
  mutateAsync.mockReset()
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false, media: query, addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(),
  }))
  const getComputedStyle = window.getComputedStyle.bind(window)
  vi.spyOn(window, 'getComputedStyle').mockImplementation((element) => getComputedStyle(element))
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('EstimateDeleteModal', () => {
  it('중복 삭제·진행 중 닫기를 막고 성공 시 닫는다', async () => {
    let resolveMutation: (() => void) | undefined
    mutateAsync.mockReturnValue(new Promise<void>((resolve) => { resolveMutation = resolve }))
    const onClose = vi.fn()
    render(<EstimateDeleteModal estimate={estimate} onClose={onClose} />)
    const remove = screen.getByRole('button', { name: '삭제' })
    fireEvent.click(remove)
    fireEvent.click(remove)
    await waitFor(() => expect(mutateAsync).toHaveBeenCalledTimes(1))
    fireEvent.click(screen.getByRole('button', { name: '취소' }))
    expect(onClose).not.toHaveBeenCalled()
    await act(async () => { resolveMutation?.() })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('실패 메시지를 표시하고 재시도한다', async () => {
    mutateAsync.mockRejectedValueOnce({ response: { data: { error: '삭제 실패' } } })
    mutateAsync.mockResolvedValueOnce(undefined)
    const onClose = vi.fn()
    render(<EstimateDeleteModal estimate={estimate} onClose={onClose} />)
    fireEvent.click(screen.getByRole('button', { name: '삭제' }))
    expect(await screen.findByText('삭제 실패')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '삭제' }))
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
  })

  it('확정 견적은 전달돼도 삭제 호출을 막는다', () => {
    render(<EstimateDeleteModal estimate={{ ...estimate, status: 'CONFIRMED' }} onClose={vi.fn()} />)
    expect(screen.getByRole('button', { name: '삭제' })).toBeDisabled()
    expect(mutateAsync).not.toHaveBeenCalled()
  })
})
