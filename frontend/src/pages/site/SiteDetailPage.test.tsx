import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SiteDetailPage from './SiteDetailPage'

const query = vi.hoisted(() => ({
  estimatesError: false,
  purchasesError: false,
  taxesError: false,
  warrantiesError: false,
  estimates: [] as unknown[],
  purchases: [] as unknown[],
  taxes: [] as unknown[],
  warranties: [] as unknown[],
  estimatesRefetch: vi.fn(),
  purchasesRefetch: vi.fn(),
  taxesRefetch: vi.fn(),
  warrantiesRefetch: vi.fn(),
}))

vi.mock('../../api/sites.api', () => ({
  useSite: () => ({
    data: {
      id: 4, siteName: '합성 현장', client: null, address: null, status: 'IN_PROGRESS',
      startDate: null, endDate: null, memo: null, archivedAt: null,
      createdAt: '2026-10-19T00:00:00', updatedAt: '2026-10-19T00:00:00',
    },
    isLoading: false, isError: false, refetch: vi.fn(),
  }),
  useUpdateSiteStatus: () => ({ mutate: vi.fn(), isPending: false }),
  useArchiveSite: () => ({ mutate: vi.fn(), isPending: false }),
  useRestoreSite: () => ({ mutate: vi.fn(), isPending: false }),
}))
vi.mock('../../api/estimates.api', () => ({
  useEstimates: () => ({ data: query.estimates, isLoading: false, isError: query.estimatesError, refetch: query.estimatesRefetch }),
}))
vi.mock('../../api/purchases.api', () => ({
  usePurchases: () => ({ data: query.purchases, isLoading: false, isError: query.purchasesError, refetch: query.purchasesRefetch }),
}))
vi.mock('../../api/taxes.api', () => ({
  useTaxes: () => ({ data: query.taxes, isLoading: false, isError: query.taxesError, refetch: query.taxesRefetch }),
}))
vi.mock('../../api/warranties.api', () => ({
  useWarranties: () => ({ data: query.warranties, isLoading: false, isError: query.warrantiesError, refetch: query.warrantiesRefetch }),
}))
vi.mock('./SiteEditModal', () => ({ default: () => null }))
vi.mock('./ClientEditModal', () => ({ default: () => null }))

beforeEach(() => {
  query.estimatesError = false
  query.purchasesError = false
  query.taxesError = false
  query.warrantiesError = false
  query.estimates = []
  query.purchases = []
  query.taxes = []
  query.warranties = []
  for (const refetch of [query.estimatesRefetch, query.purchasesRefetch, query.taxesRefetch, query.warrantiesRefetch]) refetch.mockReset()
  vi.stubGlobal('matchMedia', (media: string) => ({
    matches: false, media, addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(),
  }))
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

const renderPage = () => render(
  <MemoryRouter initialEntries={['/sites/4']}>
    <Routes><Route path="/sites/:id" element={<SiteDetailPage />} /></Routes>
  </MemoryRouter>,
)

describe('SiteDetailPage relation failures', () => {
  it.each([
    ['estimatesError', '견적서', '견적서를 불러오지 못했습니다.', query.estimatesRefetch],
    ['purchasesError', '매입', '매입 내역을 불러오지 못했습니다.', query.purchasesRefetch],
    ['taxesError', '세금계산서', '세금계산서를 불러오지 못했습니다.', query.taxesRefetch],
    ['warrantiesError', '보증보험', '보증보험을 불러오지 못했습니다.', query.warrantiesRefetch],
  ] as const)('%s 상태는 0건 대신 오류·재시도를 표시한다', (errorKey, label, errorMessage, refetch) => {
    query[errorKey] = true
    renderPage()

    fireEvent.click(screen.getByRole('tab', { name: `${label} (조회 실패)` }))
    expect(screen.getByText(errorMessage)).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: `${label} (0)` })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(refetch).toHaveBeenCalledOnce()
  })

  it('성공한 빈 목록만 0건으로 안내한다', () => {
    renderPage()

    expect(screen.getByRole('tab', { name: '견적서 (0)' })).toBeInTheDocument()
    expect(screen.getByText('이 현장에 연결된 견적서가 아직 없습니다.')).toBeInTheDocument()
  })

  it('실패 후 캐시된 옛 견적이 있어도 현재 목록처럼 표시하지 않는다', () => {
    query.estimates = [{
      id: 10, siteId: 4, title: '오래된 합성 견적', status: 'CONFIRMED', totalAmount: 3000,
      items: [], createdAt: '2026-10-18T00:00:00', updatedAt: '2026-10-18T00:00:00',
    }]
    query.estimatesError = true
    renderPage()

    expect(screen.getByRole('tab', { name: '견적서 (조회 실패)' })).toBeInTheDocument()
    expect(screen.getByText('견적서를 불러오지 못했습니다.')).toBeInTheDocument()
    expect(screen.queryByText('오래된 합성 견적')).not.toBeInTheDocument()
  })
})
