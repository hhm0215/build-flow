import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { TaxInvoice } from '../../types'
import TaxDeleteModal from './TaxDeleteModal'

const mutateAsync = vi.hoisted(() => vi.fn())

vi.mock('../../api/taxes.api', () => ({
  useDeleteTax: () => ({ mutateAsync, isPending: false }),
}))

const invoice = {
  id: 9, siteId: 3, type: 'SALES', supplyAmount: 1000, taxAmount: 100,
  totalAmount: 1100, counterparty: '삭제 거래처', issueDate: null,
  paymentConfirmed: false, paymentDate: null, memo: null,
  createdAt: '', updatedAt: '',
} satisfies TaxInvoice

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

describe('TaxDeleteModal', () => {
  it('id와 siteId로 중복 삭제·진행 중 닫기를 막고 성공 시 닫는다', async () => {
    let resolveMutation: (() => void) | undefined
    mutateAsync.mockReturnValue(new Promise<void>((resolve) => { resolveMutation = resolve }))
    const onClose = vi.fn()
    render(<TaxDeleteModal invoice={invoice} onClose={onClose} />)
    const remove = screen.getByRole('button', { name: '삭제' })
    fireEvent.click(remove)
    fireEvent.click(remove)
    await waitFor(() => expect(mutateAsync).toHaveBeenCalledWith({ id: 9, siteId: 3 }))
    expect(mutateAsync).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: '취소' }))
    expect(onClose).not.toHaveBeenCalled()
    await act(async () => { resolveMutation?.() })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('실패 메시지를 표시하고 재시도한다', async () => {
    mutateAsync.mockRejectedValueOnce({ response: { data: { error: '삭제 실패' } } })
    mutateAsync.mockResolvedValueOnce(undefined)
    const onClose = vi.fn()
    render(<TaxDeleteModal invoice={invoice} onClose={onClose} />)
    fireEvent.click(screen.getByRole('button', { name: '삭제' }))
    expect(await screen.findByText('삭제 실패')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '삭제' }))
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
  })
})
