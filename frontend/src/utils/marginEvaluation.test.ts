import { describe, expect, it } from 'vitest'
import {
  evaluateMargin,
  evaluationBasisCaption,
  hasPurchaseInput,
  siteMarginRateCaption,
} from './marginEvaluation'

describe('hasPurchaseInput', () => {
  it('1원 이상만 매입 입력으로 본다', () => {
    expect(hasPurchaseInput(1)).toBe(true)
    expect(hasPurchaseInput(0)).toBe(false)
    expect(hasPurchaseInput(-100)).toBe(false)
    expect(hasPurchaseInput(Number.NaN)).toBe(false)
    expect(hasPurchaseInput(null)).toBe(false)
    expect(hasPurchaseInput(undefined)).toBe(false)
  })
})

describe('evaluateMargin', () => {
  it('매입 0원 현장은 판정에서 빼고 미입력 현장 수로 센다', () => {
    const result = evaluateMargin([
      { status: 'COMPLETED', estimateAmount: 1_000, purchaseAmount: 0 },
      { status: 'IN_PROGRESS', estimateAmount: 2_000, purchaseAmount: 0 },
    ])
    expect(result.evaluatedSiteCount).toBe(0)
    expect(result.noPurchaseSiteCount).toBe(2)
    expect(result.marginRate).toBeNull()
  })

  it('매입 입력 현장만 견적 가중 평균한다', () => {
    const result = evaluateMargin([
      { status: 'COMPLETED', estimateAmount: 1_000, purchaseAmount: 800 },
      { status: 'SETTLING', estimateAmount: 3_000, purchaseAmount: 1_200 },
      { status: 'COMPLETED', estimateAmount: 5_000, purchaseAmount: 0 },
    ])
    expect(result.evaluatedSiteCount).toBe(2)
    expect(result.noPurchaseSiteCount).toBe(1)
    expect(result.estimateAmount).toBe(4_000)
    expect(result.purchaseAmount).toBe(2_000)
    expect(result.margin).toBe(2_000)
    expect(result.marginRate).toBe(50)
    expect(evaluationBasisCaption(result)).toBe('매입 입력된 2개 현장 기준')
  })

  it('수주 전 현장과 견적 미등록 현장은 판정에서 뺀다', () => {
    const result = evaluateMargin([
      { status: 'BIDDING', estimateAmount: 1_000, purchaseAmount: 500 },
      { status: 'IN_PROGRESS', estimateAmount: 0, purchaseAmount: 300 },
      { status: 'IN_PROGRESS', estimateAmount: 1_000, purchaseAmount: 1_500 },
    ])
    expect(result.evaluatedSiteCount).toBe(1)
    expect(result.noPurchaseSiteCount).toBe(0)
    expect(result.margin).toBe(-500)
    expect(result.marginRate).toBe(-50)
  })

  it('빈 목록은 판정 대상이 없다', () => {
    expect(evaluateMargin([]).marginRate).toBeNull()
  })
})

describe('siteMarginRateCaption', () => {
  it('견적이 없으면 산출 전, 매입이 없으면 미입력, 둘 다 있으면 마진율', () => {
    expect(siteMarginRateCaption(0, 0)).toBe('아직 산출 전')
    expect(siteMarginRateCaption(1_000, 0)).toBe('매입 미입력 · 매입 입력 후 계산')
    expect(siteMarginRateCaption(1_000, 250)).toBe('마진율 75.0%')
  })
})
