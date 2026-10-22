import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { TaxInvoice } from '../../types'
import TaxListPage from './TaxListPage'

const invoices = vi.hoisted(() => ({ items: [] as TaxInvoice[] }))

vi.mock('../../api/taxes.api', () => ({
  useTaxes: () => ({ data: invoices.items, isLoading: false, isError: false, refetch: vi.fn() }),
  useCreateTax: () => ({ mutateAsync: vi.fn(), isPending: false }),
}))
vi.mock('./TaxPaymentsModal', () => ({
  default: ({ invoice }: { invoice: TaxInvoice }) => (
    <div data-testid="payment-target">{invoice.id}:{invoice.remainingAmount}</div>
  ),
}))
vi.mock('./TaxEditModal', () => ({
  default: ({ invoice }: { invoice: TaxInvoice }) => <div data-testid="edit-target">{invoice.id}</div>,
}))
vi.mock('./TaxDeleteModal', () => ({
  default: ({ invoice }: { invoice: TaxInvoice }) => <div data-testid="delete-target">{invoice.id}</div>,
}))

const invoice = (
  id: number, type: TaxInvoice['type'], paymentConfirmed: boolean, paidAmount = paymentConfirmed ? 110000 : 0,
): TaxInvoice => ({
  id,
  siteId: 1,
  type,
  supplyAmount: 100000,
  taxAmount: 10000,
  totalAmount: 110000,
  counterparty: `거래처 ${id}`,
  issueDate: '2026-09-01',
  paymentConfirmed,
  paymentDate: paymentConfirmed ? '2026-09-15' : null,
  paidAmount: type === 'SALES' ? paidAmount : null,
  remainingAmount: type === 'SALES' ? 110000 - paidAmount : null,
  paymentStatus: type === 'SALES'
    ? (paymentConfirmed ? 'PAID' : paidAmount > 0 ? 'PARTIAL' : 'UNPAID')
    : null,
  memo: '',
  createdAt: '2026-09-01T00:00:00',
  updatedAt: '2026-09-01T00:00:00',
})

beforeEach(() => {
  invoices.items = [invoice(1, 'SALES', false), invoice(2, 'SALES', true), invoice(3, 'PURCHASE', false)]
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false,
    media: query,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }))
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('TaxListPage', () => {
  it('매출에만 입금 관리를 노출하고 대상 인보이스를 선택한다', () => {
    render(<MemoryRouter><TaxListPage /></MemoryRouter>)
    expect(screen.getAllByRole('button', { name: /입금 관리/ })).toHaveLength(2)
    expect(screen.queryByRole('button', { name: '3번 세금계산서 입금 관리' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '1번 세금계산서 입금 관리' }))
    expect(screen.getByTestId('payment-target')).toHaveTextContent('1:110000')
  })

  it('부분 입금은 상태·잔액을 보여주고 수정·삭제를 숨기며 미수금은 잔액 합계다', () => {
    invoices.items = [invoice(1, 'SALES', false, 40000), invoice(2, 'SALES', true), invoice(4, 'SALES', false)]
    render(<MemoryRouter><TaxListPage /></MemoryRouter>)
    expect(screen.getByText('부분 입금')).toBeInTheDocument()
    expect(screen.getByText('잔액 ₩70,000')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '1번 세금계산서 수정' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '4번 세금계산서 수정' })).toBeInTheDocument()
    expect(screen.getByText('미수금 합계 (2건)')).toBeInTheDocument()
    expect(screen.getByText('₩180,000')).toBeInTheDocument()
  })

  it('미확정 건에만 수정·삭제를 노출하고 행별 대상을 선택한다', () => {
    render(<MemoryRouter><TaxListPage /></MemoryRouter>)
    expect(screen.getAllByRole('button', { name: /세금계산서 수정/ })).toHaveLength(2)
    expect(screen.getAllByRole('button', { name: /세금계산서 삭제/ })).toHaveLength(2)
    expect(screen.queryByRole('button', { name: '2번 세금계산서 수정' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '3번 세금계산서 수정' }))
    expect(screen.getByTestId('edit-target')).toHaveTextContent('3')
    fireEvent.click(screen.getByRole('button', { name: '1번 세금계산서 삭제' }))
    expect(screen.getByTestId('delete-target')).toHaveTextContent('1')
  })

  it('nullable 거래처를 안전하게 표시한다', () => {
    invoices.items = [{ ...invoice(1, 'SALES', false), counterparty: null, issueDate: null, memo: null }]
    render(<MemoryRouter><TaxListPage /></MemoryRouter>)
    expect(screen.getByText('-')).toBeInTheDocument()
  })
})
