import { describe, expect, it } from 'vitest'
import type { Estimate } from '../types'
import { sumConfirmedEstimateAmount } from './estimate'

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
