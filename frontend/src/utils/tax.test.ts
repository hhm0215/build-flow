import { describe, expect, it } from 'vitest'
import { validateTaxAmounts } from './tax'

describe('validateTaxAmounts', () => {
  it('0과 큰 정상 2자리 금액을 허용한다', () => {
    expect(validateTaxAmounts(0, 0)).toBeNull()
    expect(validateTaxAmounts(9_999_999_999_999.12, 0.87)).toBeNull()
    expect(validateTaxAmounts(0.12, 0.97)).toBeNull()
  })

  it('음수와 소수 3자리 금액을 거부한다', () => {
    expect(validateTaxAmounts(-1, 0)).toContain('공급가액')
    expect(validateTaxAmounts(0, -1)).toContain('세액')
    expect(validateTaxAmounts(0.001, 0)).toContain('공급가액')
    expect(validateTaxAmounts(0, 0.001)).toContain('세액')
  })

  it('각 금액과 합계의 DECIMAL(15,2) 범위를 보호한다', () => {
    expect(validateTaxAmounts(10_000_000_000_000, 0)).toContain('공급가액')
    expect(validateTaxAmounts(0, 10_000_000_000_000)).toContain('세액')
    expect(validateTaxAmounts(9_999_999_999_999.99, 0)).toBeNull()
    expect(validateTaxAmounts(9_999_999_999_999.99, 0.01)).toContain('합계')
  })
})
