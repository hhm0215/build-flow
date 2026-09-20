import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { TaxInvoice } from '../../types'
import TaxListPage from './TaxListPage'

const invoices = vi.hoisted(() => ({ items: [] as TaxInvoice[] }))

vi.mock('../../api/taxes.api', () => ({
  useTaxes: () => ({ data: invoices.items, isLoading: false, isError: false, refetch: vi.fn() }),
  useCreateTax: () => ({ mutate: vi.fn(), isPending: false }),
}))
vi.mock('./TaxPaymentConfirmModal', () => ({
  default: ({ invoice }: { invoice: TaxInvoice }) => <div data-testid="confirm-target">{invoice.id}</div>,
}))

const invoice = (id: number, type: TaxInvoice['type'], paymentConfirmed: boolean): TaxInvoice => ({
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
  it('미확정 매출에만 입금 확인을 노출하고 대상 인보이스를 선택한다', () => {
    render(<MemoryRouter><TaxListPage /></MemoryRouter>)
    expect(screen.getAllByRole('button', { name: '입금 확인' })).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: '입금 확인' }))
    expect(screen.getByTestId('confirm-target')).toHaveTextContent('1')
  })
})
