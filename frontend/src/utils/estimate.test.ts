import { describe, expect, it } from 'vitest'
import type { Estimate, Purchase } from '../types'
import { calculateDocumentProfit, sumConfirmedEstimateAmount, validateEstimateAmounts } from './estimate'

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

describe('validateEstimateAmounts', () => {
  it('DB 소수 2자리 수량·단가와 정확한 항목 금액을 허용한다', () => {
    expect(validateEstimateAmounts([{ quantity: 1.25, unitPrice: 1000 }])).toBeNull()
    expect(validateEstimateAmounts([{ quantity: 0.5, unitPrice: 1.1 }])).toBeNull()
  })

  it('저장 시 0.00으로 반올림되거나 항목 금액이 달라지는 입력을 거절한다', () => {
    expect(validateEstimateAmounts([{ quantity: 0.004, unitPrice: 1000 }])).not.toBeNull()
    expect(validateEstimateAmounts([{ quantity: 1000, unitPrice: 0.004 }])).not.toBeNull()
    expect(validateEstimateAmounts([{ quantity: 0.01, unitPrice: 0.01 }])).not.toBeNull()
    expect(validateEstimateAmounts([{ quantity: 1.0000000001, unitPrice: 1_000_000_000_000 }])).not.toBeNull()
    expect(validateEstimateAmounts([])).not.toBeNull()
  })
})
