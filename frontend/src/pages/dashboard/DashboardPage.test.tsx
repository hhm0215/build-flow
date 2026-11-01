import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DashboardPage, { buildStatCards } from './DashboardPage'

describe('buildStatCards', () => {
  const empty = {
    sitesByStatus: {}, totalEstimateAmount: 0, totalPurchaseAmount: 0,
    totalMargin: 0, averageMarginRate: 0, totalSites: 0,
  }
  const card = (cards: ReturnType<typeof buildStatCards>, title: string) => cards.find((c) => c.title === title)!

  it('현장이 없으면 흑자·주의 평가 배지를 숨기고 마진율은 -로 둔다', () => {
    const cards = buildStatCards(empty)
    expect(card(cards, '총 마진').change).toBe('')
    expect(card(cards, '총 마진').changeType).toBe('neutral')
    expect(card(cards, '평균 마진율').value).toBe('-')
    expect(card(cards, '평균 마진율').change).toBe('')
  })

  it('데이터가 있으면 기존 평가 배지를 보여준다', () => {
    const cards = buildStatCards({
      ...empty, sitesByStatus: { IN_PROGRESS: 1 }, totalSites: 1,
      totalEstimateAmount: 600_000, totalPurchaseAmount: 273_000, totalMargin: 327_000, averageMarginRate: 54.5,
      siteProfits: [
        { siteId: 1, siteName: '현장 A', status: 'IN_PROGRESS', estimateAmount: 600_000, purchaseAmount: 273_000, margin: 327_000, marginRate: 54.5 },
      ],
    })
    expect(card(cards, '총 마진').change).toBe('흑자')
    expect(card(cards, '평균 마진율').value).toBe('54.5%')
    expect(card(cards, '평균 마진율').change).toBe('양호')
    expect(card(cards, '평균 마진율').note).toBe('매입 입력된 1개 현장 기준')
  })

  it('매입이 모두 0원이면 100% 양호·흑자 대신 매입 입력 후 계산을 안내한다', () => {
    const cards = buildStatCards({
      ...empty, sitesByStatus: { COMPLETED: 2 }, totalSites: 2,
      totalEstimateAmount: 1_000_000, totalPurchaseAmount: 0, totalMargin: 1_000_000, averageMarginRate: 100,
      siteProfits: [
        { siteId: 1, siteName: '현장 A', status: 'COMPLETED', estimateAmount: 600_000, purchaseAmount: 0, margin: 600_000, marginRate: 100 },
        { siteId: 2, siteName: '현장 B', status: 'COMPLETED', estimateAmount: 400_000, purchaseAmount: 0, margin: 400_000, marginRate: 100 },
      ],
    })
    expect(card(cards, '총 매출 (견적)').value).toBe('₩100만')
    expect(card(cards, '총 마진').value).toBe('-')
    expect(card(cards, '총 마진').change).toBe('')
    expect(card(cards, '총 마진').note).toBe('매입 입력 후 계산')
    expect(card(cards, '평균 마진율').value).toBe('-')
    expect(card(cards, '평균 마진율').change).toBe('')
    expect(card(cards, '평균 마진율').note).toBe('매입 입력 후 계산')
  })

  it('매입 입력 현장만으로 평균 마진율을 다시 계산한다', () => {
    const cards = buildStatCards({
      ...empty, sitesByStatus: { COMPLETED: 2 }, totalSites: 2,
      totalEstimateAmount: 2_000_000, totalPurchaseAmount: 900_000, totalMargin: 1_100_000, averageMarginRate: 55,
      siteProfits: [
        { siteId: 1, siteName: '현장 A', status: 'COMPLETED', estimateAmount: 1_000_000, purchaseAmount: 900_000, margin: 100_000, marginRate: 10 },
        { siteId: 2, siteName: '현장 B', status: 'COMPLETED', estimateAmount: 1_000_000, purchaseAmount: 0, margin: 1_000_000, marginRate: 100 },
      ],
    })
    expect(card(cards, '총 매입').value).toBe('₩90만')
    expect(card(cards, '총 마진').value).toBe('₩10만')
    expect(card(cards, '평균 마진율').value).toBe('10.0%')
    expect(card(cards, '평균 마진율').change).toBe('주의')
  })
})

const { summaryRefetch, summaryQuery } = vi.hoisted(() => ({
  summaryRefetch: vi.fn(),
  summaryQuery: vi.fn(),
}))

vi.mock('../../api/dashboard.api', () => ({
  useDashboardStats: () => ({ data: undefined, isLoading: true, isError: false, refetch: vi.fn() }),
  useDashboardSummary: summaryQuery,
}))

beforeEach(() => {
  summaryRefetch.mockReset()
  summaryQuery.mockReset()
  summaryQuery.mockReturnValue({ data: undefined, isLoading: true, isError: false, refetch: summaryRefetch })
})

describe('DashboardPage', () => {
  it('현장별 손익의 전체 보기를 누르면 현장 목록으로 이동한다', () => {
    render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <Routes>
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/sites" element={<div>현장 목록 도착</div>} />
        </Routes>
      </MemoryRouter>,
    )

    fireEvent.click(screen.getByRole('button', { name: '전체 보기' }))
    expect(screen.getByText('현장 목록 도착')).toBeInTheDocument()
  })

  it('AI 요약 실패 시 빈 결과 대신 오류와 재시도를 표시한다', () => {
    summaryQuery.mockReturnValue({ data: undefined, isLoading: false, isError: true, refetch: summaryRefetch })
    render(<MemoryRouter><DashboardPage /></MemoryRouter>)

    expect(screen.getByText('AI 요약을 불러오지 못했습니다.')).toBeInTheDocument()
    expect(screen.queryByText('요약 데이터가 없습니다')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(summaryRefetch).toHaveBeenCalledOnce()
  })

  it('캐시된 오래된 요약이 있어도 최신 요청이 실패하면 오류를 표시한다', () => {
    summaryQuery.mockReturnValue({ data: { summary: '오래된 요약' }, isLoading: false, isError: true, refetch: summaryRefetch })
    render(<MemoryRouter><DashboardPage /></MemoryRouter>)

    expect(screen.getByText('AI 요약을 불러오지 못했습니다.')).toBeInTheDocument()
    expect(screen.queryByText('오래된 요약')).not.toBeInTheDocument()
  })

  it('정상적인 빈 요약은 빈 결과로 안내한다', () => {
    summaryQuery.mockReturnValue({ data: { summary: '' }, isLoading: false, isError: false, refetch: summaryRefetch })
    render(<MemoryRouter><DashboardPage /></MemoryRouter>)

    expect(screen.getByText('요약 데이터가 없습니다')).toBeInTheDocument()
    expect(screen.queryByText('AI 요약을 불러오지 못했습니다.')).not.toBeInTheDocument()
  })
})
