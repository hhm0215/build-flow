import { describe, expect, it } from 'vitest'
import { assignPurchasesSequentially, summarizeUnassigned, validatePurchaseAmount } from './purchase'

describe('validatePurchaseAmount', () => {
  it('0원과 DB 최대 경계 안의 금액을 허용한다', () => {
    expect(validatePurchaseAmount(1, 0)).toBeNull()
    expect(validatePurchaseAmount(1, 1_234_567_890.12)).toBeNull()
    expect(validatePurchaseAmount(1, 9_999_999_999.97)).toBeNull()
    expect(validatePurchaseAmount(1000, 9_999_999_999.99)).toBeNull()
  })

  it('소수·0 이하·Integer 범위 밖의 수량을 거절한다', () => {
    expect(validatePurchaseAmount(1.5, 1000)).not.toBeNull()
    expect(validatePurchaseAmount(0, 1000)).not.toBeNull()
    expect(validatePurchaseAmount(2_147_483_648, 0)).not.toBeNull()
  })

  it('음수·소수 3자리·DB 범위 밖의 단가와 총액을 거절한다', () => {
    expect(validatePurchaseAmount(1, -0.01)).not.toBeNull()
    expect(validatePurchaseAmount(1, 1.001)).not.toBeNull()
    expect(validatePurchaseAmount(1, 10_000_000_000)).not.toBeNull()
    expect(validatePurchaseAmount(1001, 9_999_999_999.99)).not.toBeNull()
  })
})

describe('summarizeUnassigned', () => {
  it('siteId가 null인 매입만 센트 단위로 합산한다', () => {
    expect(summarizeUnassigned([
      { siteId: null, totalAmount: 0.1 },
      { siteId: 3, totalAmount: 999 },
      { siteId: null, totalAmount: 0.2 },
    ])).toEqual({ count: 2, totalAmount: 0.3 })
    expect(summarizeUnassigned([])).toEqual({ count: 0, totalAmount: 0 })
  })
})

describe('assignPurchasesSequentially', () => {
  it('순서대로 요청하고 일부 실패해도 나머지를 계속 진행한다', async () => {
    const calls: number[] = []
    const result = await assignPurchasesSequentially([1, 2, 3], async (id) => {
      calls.push(id)
      if (id === 2) throw new Error('보관된 현장')
    }, (error) => (error as Error).message)

    expect(calls).toEqual([1, 2, 3])
    expect(result).toEqual({ succeeded: [1, 3], failed: [{ id: 2, message: '보관된 현장' }] })
  })
})
