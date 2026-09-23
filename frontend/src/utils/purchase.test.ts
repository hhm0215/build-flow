import { describe, expect, it } from 'vitest'
import { validatePurchaseAmount } from './purchase'

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
