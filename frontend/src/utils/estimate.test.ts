import { describe, expect, it } from 'vitest'
import type { Estimate, Purchase } from '../types'
import {
  additionsOfChain,
  calculateDocumentProfit,
  estimateChain,
  estimateSupersededByConfirming,
  estimateTitleFromFileName,
  sumConfirmedEstimateAmount,
  truncateUtf16,
  validateEstimateAmounts,
} from './estimate'

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

describe('견적 수정 체인', () => {
  const est = (id: number, status: Estimate['status'], kind: Estimate['kind'],
    revisionOfId: number | null, chainRootId: number, totalAmount: number, siteId = 1) =>
    ({ id, siteId, status, kind, revisionOfId, chainRootId, totalAmount, supersededById: null }) as Estimate
  const estimates = [
    est(1, 'SUPERSEDED', 'ORIGINAL', null, 1, 1000),
    est(2, 'CONFIRMED', 'REVISION', 1, 1, 1200),
    est(3, 'DRAFT', 'REVISION', 2, 1, 900),
    est(4, 'CONFIRMED', 'ADDITIONAL', 1, 4, 300),
    est(5, 'CONFIRMED', 'ORIGINAL', null, 5, 700, 2),
  ]

  it('대체된 견적은 손익 합계에서 빠지고 추가공사는 합산된다', () => {
    expect(sumConfirmedEstimateAmount(estimates.filter((e) => e.siteId === 1))).toBe(1500)
  })

  it('체인 이력·추가공사·확정 시 대체될 견적을 계산한다', () => {
    const chain = estimateChain(estimates, estimates[2])
    expect(chain.map((e) => e.id)).toEqual([1, 2, 3])
    expect(additionsOfChain(estimates, chain).map((e) => e.id)).toEqual([4])
    expect(estimateSupersededByConfirming(estimates, estimates[2])?.id).toBe(2)
    expect(estimateSupersededByConfirming(estimates, estimates[3])).toBeNull()
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

describe('estimateTitleFromFileName', () => {
  it('확장자를 떼고 서버 한도 200자로 자른다', () => {
    expect(estimateTitleFromFileName('A동 공내역서.xlsx')).toBe('A동 공내역서')
    expect(estimateTitleFromFileName(`${'가'.repeat(250)}.XLSX`)).toHaveLength(200)
  })

  it('이모지 서로게이트 쌍을 반으로 자르지 않는다', () => {
    const value = `${'가'.repeat(199)}😀`
    expect(truncateUtf16(value, 200)).toBe('가'.repeat(199))
    expect(truncateUtf16(value, 201)).toBe(value)
  })
})
