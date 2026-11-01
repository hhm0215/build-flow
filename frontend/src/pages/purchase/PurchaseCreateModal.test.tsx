import { render, screen } from '@testing-library/react'
import dayjs from 'dayjs'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { todayKst } from '../../utils/date'
import PurchaseCreateModal, { toPurchaseCreateRequest } from './PurchaseCreateModal'

vi.mock('../../api/purchases.api', () => ({
  useCreatePurchase: () => ({ mutateAsync: vi.fn(), isPending: false }),
}))
vi.mock('../../api/sites.api', () => ({
  useSites: () => ({ data: [{ id: 4, siteName: '합성 현장', archivedAt: null, client: null }], isLoading: false }),
}))

beforeEach(() => {
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

describe('PurchaseCreateModal', () => {
  it('매입일 기본값은 오늘(KST)이고 현장 상세에서 연 현장이 선택되어 있다', () => {
    render(<PurchaseCreateModal initialSiteId={4} onClose={vi.fn()} />)
    expect(screen.getByPlaceholderText('매입일 선택')).toHaveValue(todayKst())
    expect(screen.getByText('합성 현장 (#4)')).toBeInTheDocument()
    expect(screen.getByText('거래처')).toBeInTheDocument()
  })

  it('매입일을 지우면 보내지 않고, 문자열 입력은 정리해 POST 계약으로 변환한다', () => {
    expect(toPurchaseCreateRequest({
      siteId: 4, itemName: ' 시멘트 ', quantity: 2, unitPrice: 1000,
      supplier: ' ', purchaseDate: null, memo: undefined,
    })).toEqual({
      siteId: 4, itemName: '시멘트', quantity: 2, unitPrice: 1000,
      supplier: undefined, purchaseDate: undefined, memo: undefined,
    })
    expect(toPurchaseCreateRequest({
      siteId: 4, itemName: '철근', quantity: 1, unitPrice: 5, purchaseDate: dayjs('2026-11-01'),
    }).purchaseDate).toBe('2026-11-01')
  })
})
