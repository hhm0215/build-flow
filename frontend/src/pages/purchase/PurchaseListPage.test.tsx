import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Purchase } from '../../types'
import PurchaseListPage from './PurchaseListPage'

const purchases = vi.hoisted(() => ({ items: [] as Purchase[] }))

vi.mock('../../api/purchases.api', () => ({
  usePurchases: () => ({ data: purchases.items, isLoading: false, isError: false, refetch: vi.fn() }),
  useCreatePurchase: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useAssignPurchaseSite: () => ({ mutateAsync: vi.fn(), isPending: false }),
}))
vi.mock('../../api/sites.api', () => ({
  useSites: () => ({ data: [{ id: 3, siteName: '보관된 현장', archivedAt: '2026-10-01T00:00:00' }] }),
}))
vi.mock('./PurchaseEditModal', () => ({
  default: ({ purchase }: { purchase: Purchase }) => <div data-testid="edit-target">{purchase.itemName}</div>,
}))
vi.mock('./PurchaseDeleteModal', () => ({
  default: ({ purchase }: { purchase: Purchase }) => <div data-testid="delete-target">{purchase.itemName}</div>,
}))

const purchase = (id: number, itemName: string, purchaseDate: string | null, siteId: number | null = 3): Purchase => ({
  id, siteId, itemName, quantity: 2, unitPrice: 1000, totalAmount: 2000,
  supplier: null, purchaseDate, memo: null, evidenceTaxInvoiceId: null,
  createdAt: '2026-09-24T00:00:00', updatedAt: '2026-09-24T00:00:00',
})

beforeEach(() => {
  purchases.items = [purchase(1, '첫 자재', null), purchase(2, '둘째 자재', '2026-09-24')]
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false, media: query, addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(),
  }))
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('PurchaseListPage', () => {
  it('현장 열과 통일된 거래처 용어를 보여준다', () => {
    render(<MemoryRouter><PurchaseListPage /></MemoryRouter>)
    expect(screen.getByRole('columnheader', { name: '현장' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '거래처' })).toBeInTheDocument()
    expect(screen.getAllByText('보관된 현장')).toHaveLength(2)
    expect(screen.getByPlaceholderText('품목명, 거래처 검색')).toBeInTheDocument()
  })

  it('nullable 날짜를 안전하게 표시하고 행별 수정·삭제 대상을 연다', () => {
    render(<MemoryRouter><PurchaseListPage /></MemoryRouter>)

    expect(screen.getAllByText('-').length).toBeGreaterThan(0)
    const editButtons = screen.getAllByRole('button', { name: '수정' })
    const deleteButtons = screen.getAllByRole('button', { name: '삭제' })
    expect(editButtons).toHaveLength(2)
    expect(deleteButtons).toHaveLength(2)
    fireEvent.click(editButtons[1])
    expect(screen.getByTestId('edit-target')).toHaveTextContent('둘째 자재')
    fireEvent.click(deleteButtons[0])
    expect(screen.getByTestId('delete-target')).toHaveTextContent('첫 자재')
  })

  it('미배정 매입은 태그·현장 선택·일괄 배정 안내를 보이고 증빙 버튼을 막는다', () => {
    purchases.items = [
      purchase(1, '배정된 자재', null),
      purchase(2, '미배정 자재', '2026-10-01', null),
    ]
    render(<MemoryRouter><PurchaseListPage /></MemoryRouter>)

    expect(screen.getAllByText('미배정').length).toBeGreaterThan(0)
    expect(screen.getByRole('status')).toHaveTextContent('현장 미배정 매입 1건 · ₩2,000')
    expect(screen.getByRole('button', { name: '배정' })).toBeDisabled()
    expect(screen.getByRole('checkbox', { name: '미배정 자재 선택' })).toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: '배정된 자재 선택' })).not.toBeInTheDocument()
    const evidenceButtons = screen.getAllByRole('button', { name: '증빙' })
    expect(evidenceButtons[0]).toBeEnabled()
    expect(evidenceButtons[1]).toBeDisabled()

    fireEvent.click(screen.getByRole('checkbox', { name: '미배정 자재 선택' }))
    expect(screen.getByRole('button', { name: '선택 1건 배정' })).toBeDisabled()
  })

  it('미배정 필터(URL)는 현장 없는 매입만 보여준다', () => {
    purchases.items = [
      purchase(1, '배정된 자재', null),
      purchase(2, '미배정 자재', '2026-10-01', null),
    ]
    render(<MemoryRouter initialEntries={['/purchases?assignment=unassigned']}><PurchaseListPage /></MemoryRouter>)

    expect(screen.getByText('미배정 자재')).toBeInTheDocument()
    expect(screen.queryByText('배정된 자재')).not.toBeInTheDocument()
  })
})
