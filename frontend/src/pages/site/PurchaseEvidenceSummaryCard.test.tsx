import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { PurchaseEvidenceSummary } from '../../types'
import PurchaseEvidenceSummaryCard from './PurchaseEvidenceSummaryCard'

const state = vi.hoisted(() => ({
  data: undefined as PurchaseEvidenceSummary | undefined,
  isLoading: false,
  isError: false,
  refetch: vi.fn(),
}))

vi.mock('../../api/purchases.api', () => ({
  usePurchaseEvidenceSummary: () => state,
}))

const base: PurchaseEvidenceSummary = {
  siteId: 1,
  purchaseCount: 3,
  purchaseAmount: 1_200,
  linkedPurchaseCount: 2,
  linkedPurchaseAmount: 900,
  unlinkedPurchaseCount: 1,
  unlinkedPurchaseAmount: 300,
  brokenLinkCount: 1,
  invoiceCount: 2,
  invoiceSupplyAmount: 1_400,
  invoiceTotalAmount: 1_540,
  invoiceWithoutPurchaseCount: 1,
  supplyDifference: 500,
  totalDifference: 640,
  invoices: [],
}

beforeEach(() => {
  state.data = base
  state.isLoading = false
  state.isError = false
  state.refetch.mockReset()
})

describe('PurchaseEvidenceSummaryCard', () => {
  it('미연결 매입 금액과 계산서−연결 매입 차이, 확인할 항목을 보여준다', () => {
    render(<PurchaseEvidenceSummaryCard siteId={1} />)

    expect(screen.getByText('₩300')).toBeInTheDocument()
    expect(screen.getByText('1건 / 전체 3건')).toBeInTheDocument()
    expect(screen.getByText('₩1,540')).toBeInTheDocument()
    expect(screen.getByText('+₩500')).toBeInTheDocument()
    expect(screen.getByText('공급가 기준 · 합계 기준 +₩640')).toBeInTheDocument()
    expect(screen.getByText(/연결된 계산서를 찾을 수 없는 매입 1건/)).toBeInTheDocument()
    expect(screen.getByText(/연결된 매입이 없는 매입 계산서 1건/)).toBeInTheDocument()
  })

  it('음수 차이와 모두 연결된 상태를 표시한다', () => {
    state.data = {
      ...base, unlinkedPurchaseCount: 0, unlinkedPurchaseAmount: 0, brokenLinkCount: 0,
      invoiceWithoutPurchaseCount: 0, supplyDifference: -1_200, totalDifference: -350,
    }
    render(<PurchaseEvidenceSummaryCard siteId={1} />)

    expect(screen.getByText('−₩1,200')).toBeInTheDocument()
    expect(screen.queryByText(/찾을 수 없는 매입/)).not.toBeInTheDocument()
  })

  it('조회 실패는 0원으로 보이지 않고 재시도를 제공한다', () => {
    state.data = undefined
    state.isError = true
    render(<PurchaseEvidenceSummaryCard siteId={1} />)

    expect(screen.getByText('증빙 대조 요약을 불러오지 못했습니다.')).toBeInTheDocument()
    expect(screen.queryByText('₩0')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /다시 시도/ }))
    expect(state.refetch).toHaveBeenCalled()
  })
})
