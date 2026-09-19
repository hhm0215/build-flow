import { describe, expect, it } from 'vitest'
import type { Estimate, Purchase } from '../types'
import { calculateDocumentProfit, sumConfirmedEstimateAmount } from './estimate'

describe('sumConfirmedEstimateAmount', () => {
  it('확정 전 초안 금액은 손익 합계에서 제외한다', () => {
    const estimates = [
      { status: 'DRAFT', totalAmount: 500000 },
      { status: 'CONFIRMED', totalAmount: 120000 },
      { status: 'CONFIRMED', totalAmount: 80000 },
    ] as Estimate[]

    expect(sumConfirmedEstimateAmount(estimates)).toBe(200000)
  })
})

describe('calculateDocumentProfit', () => {
  it('비동기 손익 집계 대신 최신 확정 견적·매입 목록으로 금액을 계산한다', () => {
    const estimates = [
      { status: 'DRAFT', totalAmount: 900000 },
      { status: 'CONFIRMED', totalAmount: 200000 },
    ] as Estimate[]
    const purchases = [{ totalAmount: 50000 }] as Purchase[]

    expect(calculateDocumentProfit(estimates, purchases)).toEqual({
      totalEstimateAmount: 200000,
      totalPurchaseAmount: 50000,
      margin: 150000,
      marginRate: 75,
    })
  })
})
