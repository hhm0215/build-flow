import { describe, expect, it } from 'vitest'
import type { TaxInvoice } from '../types'
import { hasActivePayment, paymentStatusOf, remainingOf, validatePaymentAmount } from './taxPayment'

const base: TaxInvoice = {
  id: 1, siteId: 1, type: 'SALES', supplyAmount: 100, taxAmount: 10, totalAmount: 110,
  counterparty: null, issueDate: null, paymentConfirmed: false, paymentDate: null,
  paidAmount: 0, remainingAmount: 110, paymentStatus: 'UNPAID', memo: null,
  createdAt: '2026-10-01T00:00:00', updatedAt: '2026-10-01T00:00:00',
}

describe('taxPayment utils', () => {
  it('부분 입금 잔액과 상태를 서버 값으로 읽는다', () => {
    const partial = { ...base, paidAmount: 40, remainingAmount: 70, paymentStatus: 'PARTIAL' as const }
    expect(paymentStatusOf(partial)).toBe('PARTIAL')
    expect(remainingOf(partial)).toBe(70)
    expect(hasActivePayment(partial)).toBe(true)
    expect(hasActivePayment(base)).toBe(false)
  })

  it('매입은 수금 상태가 없고 잔액 0으로 본다', () => {
    const purchase = { ...base, type: 'PURCHASE' as const, paidAmount: null, remainingAmount: null, paymentStatus: null }
    expect(paymentStatusOf(purchase)).toBeNull()
    expect(remainingOf(purchase)).toBe(0)
  })

  it('입금액 경계: 0·음수·소수 3자리·잔액 초과를 거부한다', () => {
    expect(validatePaymentAmount(0, 110)).not.toBeNull()
    expect(validatePaymentAmount(-1, 110)).not.toBeNull()
    expect(validatePaymentAmount(1.001, 110)).not.toBeNull()
    expect(validatePaymentAmount(110.01, 110)).toBe('입금액이 남은 미수금을 초과합니다.')
    expect(validatePaymentAmount(110, 110)).toBeNull()
    expect(validatePaymentAmount(0.01, 110)).toBeNull()
    expect(validatePaymentAmount(null, 110)).toBe('입금액을 입력해 주세요.')
  })
})
