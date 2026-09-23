import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Purchase } from '../../types'
import PurchaseListPage from './PurchaseListPage'

const purchases = vi.hoisted(() => ({ items: [] as Purchase[] }))

vi.mock('../../api/purchases.api', () => ({
  usePurchases: () => ({ data: purchases.items, isLoading: false, isError: false, refetch: vi.fn() }),
  useCreatePurchase: () => ({ mutateAsync: vi.fn(), isPending: false }),
}))
vi.mock('./PurchaseEditModal', () => ({
  default: ({ purchase }: { purchase: Purchase }) => <div data-testid="edit-target">{purchase.itemName}</div>,
}))
vi.mock('./PurchaseDeleteModal', () => ({
  default: ({ purchase }: { purchase: Purchase }) => <div data-testid="delete-target">{purchase.itemName}</div>,
}))

const purchase = (id: number, itemName: string, purchaseDate: string | null): Purchase => ({
  id, siteId: 3, itemName, quantity: 2, unitPrice: 1000, totalAmount: 2000,
  supplier: null, purchaseDate, memo: null,
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
})
