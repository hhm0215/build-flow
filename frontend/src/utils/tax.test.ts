import { describe, expect, it } from 'vitest'
import { defaultTaxAmount, isCustomTaxAmount, shouldAutoFillTax, validateTaxAmounts } from './tax'

describe('defaultTaxAmount', () => {
  it('공급가액의 10%를 원 미만 절사로 계산한다', () => {
    expect(defaultTaxAmount(600_000)).toBe(60_000)
    expect(defaultTaxAmount(273_005)).toBe(27_300)
    expect(defaultTaxAmount(9)).toBe(0)
    expect(defaultTaxAmount(19.99)).toBe(1)
    expect(defaultTaxAmount(0)).toBe(0)
  })

  it('큰 금액도 부동소수 오차 없이 계산한다', () => {
    expect(defaultTaxAmount(9_999_999_999_999.99)).toBe(999_999_999_999)
  })

  it('빈 값·음수·소수 3자리·범위 초과는 자동 채움하지 않는다', () => {
    expect(defaultTaxAmount(null)).toBeNull()
    expect(defaultTaxAmount(undefined)).toBeNull()
    expect(defaultTaxAmount(-1)).toBeNull()
    expect(defaultTaxAmount(0.001)).toBeNull()
    expect(defaultTaxAmount(10_000_000_000_000)).toBeNull()
  })
})

describe('shouldAutoFillTax', () => {
  it('세액이 비었거나 직접 고치지 않았으면 채운다', () => {
    expect(shouldAutoFillTax(undefined, false)).toBe(true)
    expect(shouldAutoFillTax(null, true)).toBe(true)
    expect(shouldAutoFillTax(60_000, false)).toBe(true)
  })

  it('사용자가 직접 고친 세액은 덮어쓰지 않는다', () => {
    expect(shouldAutoFillTax(55_000, true)).toBe(false)
    expect(shouldAutoFillTax(0, true)).toBe(false)
  })
})

describe('isCustomTaxAmount', () => {
  it('저장된 세액이 10% 기본값과 다를 때만 직접 입력으로 본다', () => {
    expect(isCustomTaxAmount(600_000, 60_000)).toBe(false)
    expect(isCustomTaxAmount(600_000, 0)).toBe(true)
    expect(isCustomTaxAmount(273_005, 27_300.5)).toBe(true)
  })
})

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
