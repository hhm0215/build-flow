import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Estimate, Purchase, Site, TaxInvoice } from '../../types'
import SiteListPage from './SiteListPage'

const query = vi.hoisted(() => ({
  estimatesError: false,
  purchasesError: false,
  taxesError: false,
  estimatesLoading: false,
  purchasesLoading: false,
  taxesLoading: false,
  estimates: [] as Estimate[] | undefined,
  purchases: [] as Purchase[] | undefined,
  taxes: [] as TaxInvoice[] | undefined,
  estimatesRefetch: vi.fn(),
  purchasesRefetch: vi.fn(),
  taxesRefetch: vi.fn(),
}))

vi.mock('../../api/sites.api', () => ({
  useSites: () => ({
    data: [{
      id: 4,
      siteName: '합성 현장',
      client: null,
      address: null,
      status: 'IN_PROGRESS',
      startDate: null,
      endDate: null,
      memo: null,
      archivedAt: null,
      createdAt: '2026-10-19T00:00:00',
      updatedAt: '2026-10-19T00:00:00',
    } satisfies Site],
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
  }),
}))
vi.mock('../../api/estimates.api', () => ({
  useEstimates: () => ({ data: query.estimates, isLoading: query.estimatesLoading, isError: query.estimatesError, refetch: query.estimatesRefetch }),
}))
vi.mock('../../api/purchases.api', () => ({
  usePurchases: () => ({ data: query.purchases, isLoading: query.purchasesLoading, isError: query.purchasesError, refetch: query.purchasesRefetch }),
}))
vi.mock('../../api/taxes.api', () => ({
  useTaxes: () => ({ data: query.taxes, isLoading: query.taxesLoading, isError: query.taxesError, refetch: query.taxesRefetch }),
}))
vi.mock('./SiteCreateModal', () => ({ default: () => null }))

beforeEach(() => {
  query.estimatesError = false
  query.purchasesError = false
  query.taxesError = false
  query.estimatesLoading = false
  query.purchasesLoading = false
  query.taxesLoading = false
  query.estimates = []
  query.purchases = []
  query.taxes = []
  query.estimatesRefetch.mockReset()
  query.purchasesRefetch.mockReset()
  query.taxesRefetch.mockReset()
  vi.stubGlobal('matchMedia', (media: string) => ({
    matches: false,
    media,
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

const renderPage = () => render(<MemoryRouter><SiteListPage /></MemoryRouter>)

const populateRelations = () => {
  query.estimates = [{
    id: 10, siteId: 4, title: '합성 견적', status: 'CONFIRMED', estimateDate: '2026-10-19',
    totalAmount: 3000, memo: '', kind: 'ORIGINAL', revisionOfId: null, chainRootId: 10, supersededById: null, items: [], createdAt: '2026-10-19T00:00:00', updatedAt: '2026-10-19T00:00:00',
  }]
  query.purchases = [{
    id: 11, siteId: 4, itemName: '합성 매입', quantity: 1, unitPrice: 800, totalAmount: 800,
    supplier: null, purchaseDate: '2026-10-19', memo: null, evidenceTaxInvoiceId: null, createdAt: '2026-10-19T00:00:00', updatedAt: '2026-10-19T00:00:00',
  }]
  query.taxes = [{
    id: 12, siteId: 4, type: 'SALES', supplyAmount: 1000, taxAmount: 100, totalAmount: 1100,
    counterparty: null, issueDate: '2026-10-19', paymentConfirmed: false, paymentDate: null,
    paidAmount: 0, remainingAmount: 1100, paymentStatus: 'UNPAID',
    memo: null, createdAt: '2026-10-19T00:00:00', updatedAt: '2026-10-19T00:00:00',
  }]
}

describe('SiteListPage relation failures', () => {
  it('조회 성공 후 실제 빈 목록만 0건·0원으로 표시한다', async () => {
    renderPage()

    expect(await screen.findAllByText('₩0')).toHaveLength(4)
    expect(screen.getByText('이 현장에 연결된 견적서가 아직 없습니다.')).toBeInTheDocument()
    expect(screen.getByText('이 현장에 연결된 세금계산서가 아직 없습니다.')).toBeInTheDocument()
    expect(screen.getByText('이 현장에 연결된 공내역 또는 매입 내역이 아직 없습니다.')).toBeInTheDocument()
  })

  it('관계 서비스 실패를 빈 목록이나 0원으로 오인시키지 않고 각각 재시도한다', async () => {
    query.estimatesError = true
    query.purchasesError = true
    query.taxesError = true
    renderPage()

    expect(await screen.findAllByText('조회 실패')).toHaveLength(4)
    expect(screen.getByText('견적서를 불러오지 못했습니다.')).toBeInTheDocument()
    expect(screen.getByText('세금계산서를 불러오지 못했습니다.')).toBeInTheDocument()
    expect(screen.getByText('매입 내역을 불러오지 못했습니다.')).toBeInTheDocument()
    expect(screen.queryByText('이 현장에 연결된 견적서가 아직 없습니다.')).not.toBeInTheDocument()
    expect(screen.queryByText('미수금 ₩0')).not.toBeInTheDocument()
    fireEvent.click(screen.getAllByRole('button', { name: '다시 시도' })[0])
    expect(query.estimatesRefetch).toHaveBeenCalledTimes(1)
  })

  it('매입 조회만 실패해도 마진을 계산하지 않는다', async () => {
    populateRelations()
    query.purchasesError = true
    renderPage()

    expect(await screen.findByText('견적서·매입 조회 후 산출')).toBeInTheDocument()
    expect(screen.getAllByText('조회 실패')).toHaveLength(2)
    expect(screen.getAllByText('₩3,000')).not.toHaveLength(0)
    expect(screen.getByText('미수금 ₩1,100', { exact: false })).toBeInTheDocument()
    expect(screen.queryByText('₩2,200')).not.toBeInTheDocument()
  })

  it('모든 관계 조회가 성공하면 실제 합계와 마진을 표시한다', async () => {
    populateRelations()
    renderPage()

    expect(await screen.findByText('마진율 73.3%')).toBeInTheDocument()
    expect(screen.getAllByText('₩3,000')).not.toHaveLength(0)
    expect(screen.getAllByText('₩800')).not.toHaveLength(0)
    expect(screen.getAllByText('₩1,100')).not.toHaveLength(0)
    expect(screen.getByText('₩2,200')).toBeInTheDocument()
  })

  it('캐시에 과거 금액이 남아도 재조회 실패 시 확정 금액처럼 표시하지 않는다', async () => {
    populateRelations()
    query.estimatesError = true
    query.purchasesError = true
    query.taxesError = true
    renderPage()

    expect(await screen.findAllByText('조회 실패')).toHaveLength(4)
    expect(screen.queryByText('₩3,000')).not.toBeInTheDocument()
    expect(screen.queryByText('₩2,200')).not.toBeInTheDocument()
    expect(screen.queryByText('미수금 ₩1,100', { exact: false })).not.toBeInTheDocument()
  })

  it('성공 데이터가 없는데 로딩도 아니면 빈 목록으로 확정하지 않는다', async () => {
    query.estimates = undefined
    renderPage()

    expect(await screen.findByText('견적서를 불러오지 못했습니다.')).toBeInTheDocument()
    expect(screen.getAllByText('조회 실패')).toHaveLength(2)
  })

  it('관계 조회 중에는 합계와 마진을 0원으로 확정하지 않는다', async () => {
    query.estimatesLoading = true
    query.purchasesLoading = true
    query.taxesLoading = true
    renderPage()

    expect(await screen.findAllByText('불러오는 중')).toHaveLength(4)
    expect(screen.queryByText('미수금 ₩0')).not.toBeInTheDocument()
  })
})
