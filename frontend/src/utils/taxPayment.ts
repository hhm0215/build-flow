import type { TaxInvoice, TaxPaymentStatus } from '../types'

const MAX_AMOUNT_CENTS = 999_999_999_999_999n

export function amountToCents(value: number): bigint | null {
  if (!Number.isFinite(value) || value < 0) return null
  const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(value.toString())
  if (!match) return null
  return BigInt(match[1]) * 100n + BigInt((match[2] ?? '').padEnd(2, '0'))
}

export function centsToAmount(cents: bigint): number {
  return Number(cents) / 100
}

/**
 * 입금액 InputNumber 표시 형식 — 다른 금액 입력(보증금액 등)과 같은 `₩ 660,000`.
 * 정수는 소수점 없이, 센트가 있으면 그대로(`₩ 1,234.5`) 보여준다. 쉼표는 정수부에만 넣는다.
 */
export function formatWonInput(value: number | string | undefined): string {
  if (value == null || `${value}` === '') return ''
  const [integer, fraction] = `${value}`.split('.')
  const grouped = integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return `₩ ${grouped}${fraction !== undefined ? `.${fraction}` : ''}`
}

/** formatWonInput의 역변환. 반올림하지 않으므로 소수 3자리 이상은 validatePaymentAmount가 거부한다. */
export function parseWonInput(value: string | undefined): number {
  return (value ?? '').replace(/₩\s?|,/g, '') as unknown as number
}

/** 매출 수금 상태. 매입은 null. 구버전 응답(paymentStatus 없음)은 완료 여부로 대체한다. */
export function paymentStatusOf(invoice: TaxInvoice): TaxPaymentStatus | null {
  if (invoice.type !== 'SALES') return null
  if (invoice.paymentStatus) return invoice.paymentStatus
  return invoice.paymentConfirmed ? 'PAID' : 'UNPAID'
}

/** 남은 미수금. 매입은 0. */
export function remainingOf(invoice: TaxInvoice): number {
  if (invoice.type !== 'SALES') return 0
  if (invoice.remainingAmount != null) return invoice.remainingAmount
  return invoice.paymentConfirmed ? 0 : invoice.totalAmount
}

/** 유효 입금이 있으면 수정·삭제 불가(서버 409와 동일 규칙). */
export function hasActivePayment(invoice: TaxInvoice): boolean {
  return invoice.paymentConfirmed || (invoice.paidAmount ?? 0) > 0
}

export const PAYMENT_STATUS_META: Record<TaxPaymentStatus, { label: string; color: string }> = {
  PAID: { label: '입금 완료', color: '#22c55e' },
  PARTIAL: { label: '부분 입금', color: '#3b82f6' },
  UNPAID: { label: '미입금', color: '#f59e0b' },
}

/** 서버와 같은 입금액 규칙: 0 초과, 소수 2자리, 잔액 이하. */
export function validatePaymentAmount(amount: number | null | undefined, remaining: number): string | null {
  if (amount == null) return '입금액을 입력해 주세요.'
  const cents = amountToCents(amount)
  if (cents == null || cents === 0n || cents > MAX_AMOUNT_CENTS) {
    return '입금액은 0보다 크고 정수 13자리·소수 2자리 이하여야 합니다.'
  }
  const remainingCents = amountToCents(remaining)
  if (remainingCents == null || cents > remainingCents) {
    return '입금액이 남은 미수금을 초과합니다.'
  }
  return null
}
