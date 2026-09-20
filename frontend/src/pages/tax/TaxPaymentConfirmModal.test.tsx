import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { TaxInvoice } from '../../types'
import TaxPaymentConfirmModal from './TaxPaymentConfirmModal'

const mutation = vi.hoisted(() => ({ mutateAsync: vi.fn(), isPending: false }))

vi.mock('../../api/taxes.api', () => ({ useConfirmPayment: () => mutation }))

const invoice = {
  id: 17,
  siteId: 3,
  type: 'SALES',
  supplyAmount: 100000,
  taxAmount: 10000,
  totalAmount: 110000,
  counterparty: '현대건설',
  issueDate: '2026-09-01',
  paymentConfirmed: false,
  paymentDate: null,
  memo: '',
  createdAt: '2026-09-01T00:00:00',
  updatedAt: '2026-09-01T00:00:00',
} satisfies TaxInvoice

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

describe('TaxPaymentConfirmModal', () => {
  it('취소하면 입금 확인 API를 호출하지 않는다', () => {
    const onClose = vi.fn()
    render(<TaxPaymentConfirmModal invoice={invoice} onClose={onClose} />)
    expect(screen.getByText(/현대건설.*110,000/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '취소' }))
    expect(onClose).toHaveBeenCalledOnce()
    expect(mutation.mutateAsync).not.toHaveBeenCalled()
  })

  it('선택한 입금일을 보내고 성공 시 닫는다', async () => {
    mutation.mutateAsync.mockResolvedValue({ ...invoice, paymentConfirmed: true })
    const onClose = vi.fn()
    render(<TaxPaymentConfirmModal invoice={invoice} onClose={onClose} />)
    const paymentDate = screen.getByPlaceholderText('입금일 선택')
    fireEvent.change(paymentDate, { target: { value: '2026-09-10' } })
    fireEvent.keyDown(paymentDate, { key: 'Enter' })
    fireEvent.click(screen.getByRole('button', { name: '입금 확인' }))
    await waitFor(() => expect(mutation.mutateAsync).toHaveBeenCalledWith({
      id: 17,
      paymentDate: '2026-09-10',
    }))
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('서버 오류를 보여주고 재시도한다', async () => {
    mutation.mutateAsync
      .mockRejectedValueOnce({ response: { data: { error: '이미 입금 확인된 세금계산서입니다.' } } })
      .mockResolvedValueOnce({ ...invoice, paymentConfirmed: true })
    const onClose = vi.fn()
    render(<TaxPaymentConfirmModal invoice={invoice} onClose={onClose} />)
    fireEvent.click(screen.getByRole('button', { name: '입금 확인' }))
    expect(await screen.findByText('이미 입금 확인된 세금계산서입니다.')).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '입금 확인' }))
    await waitFor(() => expect(onClose).toHaveBeenCalledOnce())
    expect(mutation.mutateAsync).toHaveBeenCalledTimes(2)
  })

  it('중복 제출을 막고 이미 확정된 항목은 저장하지 않는다', async () => {
    let complete: (() => void) | undefined
    mutation.mutateAsync.mockReturnValue(new Promise<void>((resolve) => { complete = resolve }))
    const { rerender } = render(<TaxPaymentConfirmModal invoice={invoice} onClose={vi.fn()} />)
    const confirm = screen.getByRole('button', { name: '입금 확인' })
    fireEvent.click(confirm)
    fireEvent.click(confirm)
    await waitFor(() => expect(mutation.mutateAsync).toHaveBeenCalledTimes(1))
    complete?.()
    rerender(<TaxPaymentConfirmModal invoice={{ ...invoice, paymentConfirmed: true }} onClose={vi.fn()} />)
    expect(screen.getByRole('button', { name: '입금 확인' })).toBeDisabled()
  })
})
