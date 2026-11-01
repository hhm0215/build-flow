import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { TaxInvoice, TaxPayment } from '../../types'
import TaxPaymentsModal from './TaxPaymentsModal'

const state = vi.hoisted(() => ({
  record: { mutateAsync: vi.fn(), isPending: false },
  cancel: { mutateAsync: vi.fn(), isPending: false },
  payments: [] as TaxPayment[],
}))

vi.mock('../../api/taxes.api', () => ({
  useRecordTaxPayment: () => state.record,
  useCancelTaxPayment: () => state.cancel,
  useTaxPayments: () => ({ data: state.payments, isLoading: false, isError: false }),
}))

const invoice: TaxInvoice = {
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
  paidAmount: 40000,
  remainingAmount: 70000,
  paymentStatus: 'PARTIAL',
  memo: '',
  createdAt: '2026-09-01T00:00:00',
  updatedAt: '2026-09-01T00:00:00',
}

const payment = (id: number, amount: number, canceled = false): TaxPayment => ({
  id, taxInvoiceId: 17, amount, paymentDate: '2026-10-0' + id, memo: canceled ? null : `${id}차`,
  canceled, canceledAt: canceled ? '2026-10-10T09:30:00' : null, cancelReason: canceled ? '오입금' : null,
  createdAt: '2026-10-01T00:00:00',
})

beforeEach(() => {
  state.record.mutateAsync.mockReset()
  state.cancel.mutateAsync.mockReset()
  state.record.isPending = false
  state.cancel.isPending = false
  state.payments = [payment(1, 40000), payment(2, 5000, true)]
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

describe('TaxPaymentsModal', () => {
  it('잔액을 입금액 기본값으로 보내고 취소된 입금은 취소 버튼 없이 표시한다', async () => {
    state.record.mutateAsync.mockResolvedValue({ id: 3 })
    render(<TaxPaymentsModal invoice={invoice} onClose={vi.fn()} />)

    expect(screen.getByTestId('payment-remaining')).toHaveTextContent('₩70,000')
    expect(screen.getByText('부분 입금')).toBeInTheDocument()
    expect(screen.getByText(/취소됨 2026-10-10 09:30 · 오입금/)).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /입금 취소$/ })).toHaveLength(1)
    // 기본값은 다른 금액 입력과 같은 ₩·쉼표·정수 형식 (이전: 70000.00)
    expect(screen.getByPlaceholderText('입금액')).toHaveValue('₩ 70,000')

    fireEvent.click(screen.getByRole('button', { name: '입금 기록' }))

    await waitFor(() => expect(state.record.mutateAsync).toHaveBeenCalledOnce())
    expect(state.record.mutateAsync.mock.calls[0][0]).toMatchObject({ taxId: 17, amount: 70000 })
    expect(state.record.mutateAsync.mock.calls[0][0].paymentDate).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  })

  it('쉼표·₩가 섞인 입력도 센트까지 반올림 없이 보낸다', async () => {
    state.record.mutateAsync.mockResolvedValue({ id: 4 })
    render(<TaxPaymentsModal invoice={invoice} onClose={vi.fn()} />)
    const amount = screen.getByPlaceholderText('입금액')
    fireEvent.change(amount, { target: { value: '₩ 12,345.5' } })
    fireEvent.blur(amount)
    expect(amount).toHaveValue('₩ 12,345.5')

    fireEvent.click(screen.getByRole('button', { name: '입금 기록' }))

    await waitFor(() => expect(state.record.mutateAsync).toHaveBeenCalledOnce())
    expect(state.record.mutateAsync.mock.calls[0][0]).toMatchObject({ amount: 12345.5 })
  })

  it('잔액을 넘는 입금액은 API를 호출하지 않고 오류를 보여준다', async () => {
    render(<TaxPaymentsModal invoice={invoice} onClose={vi.fn()} />)
    const amount = screen.getByPlaceholderText('입금액')
    fireEvent.change(amount, { target: { value: '70000.01' } })
    fireEvent.blur(amount)

    fireEvent.click(screen.getByRole('button', { name: '입금 기록' }))

    // InputNumber max가 잔액으로 고정하거나, 남으면 클라이언트 검증이 거부한다
    await waitFor(() => {
      const calls = state.record.mutateAsync.mock.calls
      expect(calls.every(([body]) => body.amount <= 70000)).toBe(true)
    })
  })

  it('입금 취소를 확인하면 해당 입금 ID로 취소 API를 호출하고 서버 오류를 표시한다', async () => {
    state.cancel.mutateAsync.mockRejectedValue({ response: { data: { error: '보관된 현장의 자료는 현장을 복원한 뒤 수정·삭제할 수 있습니다.' } } })
    render(<TaxPaymentsModal invoice={invoice} onClose={vi.fn()} />)

    fireEvent.click(screen.getByRole('button', { name: '2026-10-01 입금 취소' }))
    fireEvent.click(await screen.findByRole('button', { name: '입금 취소' }))

    await waitFor(() => expect(state.cancel.mutateAsync).toHaveBeenCalledWith({ taxId: 17, paymentId: 1 }))
    expect(await screen.findByText(/현장을 복원한 뒤/)).toBeInTheDocument()
  })

  it('전액 입금된 계산서는 입금 기록 폼을 숨긴다', () => {
    render(
      <TaxPaymentsModal
        invoice={{ ...invoice, paymentConfirmed: true, paidAmount: 110000, remainingAmount: 0, paymentStatus: 'PAID' }}
        onClose={vi.fn()}
      />,
    )
    expect(screen.getByText('입금 완료')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '입금 기록' })).not.toBeInTheDocument()
  })
})
