import type { Purchase } from '../types'

const MAX_INTEGER_QUANTITY = 2_147_483_647
const MAX_UNIT_PRICE_CENTS = 999_999_999_999n
const MAX_TOTAL_AMOUNT_CENTS = 999_999_999_999_999n

function toCents(value: number): bigint | null {
  if (!Number.isFinite(value) || value < 0) return null
  const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(value.toString())
  if (!match) return null
  const fraction = (match[2] ?? '').padEnd(2, '0')
  return BigInt(match[1]) * 100n + BigInt(fraction)
}

export function validatePurchaseAmount(quantity: number, unitPrice: number): string | null {
  if (!Number.isInteger(quantity) || quantity < 1 || quantity > MAX_INTEGER_QUANTITY) {
    return '수량은 1 이상의 정수여야 합니다.'
  }
  const unitPriceCents = toCents(unitPrice)
  if (unitPriceCents == null || unitPriceCents > MAX_UNIT_PRICE_CENTS) {
    return '단가는 0 이상, 정수 10자리·소수 2자리 이하여야 합니다.'
  }
  if (BigInt(quantity) * unitPriceCents > MAX_TOTAL_AMOUNT_CENTS) {
    return '매입 총액이 저장 가능한 범위를 초과했습니다.'
  }
  return null
}

/** 현장 미배정 매입 건수·합계(원 단위 소수 2자리 오차 없이 센트로 합산). */
export function summarizeUnassigned(purchases: ReadonlyArray<Pick<Purchase, 'siteId' | 'totalAmount'>>) {
  let count = 0
  let cents = 0n
  for (const purchase of purchases) {
    if (purchase.siteId != null) continue
    count += 1
    cents += BigInt(Math.round(purchase.totalAmount * 100))
  }
  return { count, totalAmount: Number(cents) / 100 }
}

export interface BulkAssignResult {
  succeeded: number[]
  failed: { id: number, message: string }[]
}

/**
 * 여러 미배정 매입을 한 현장에 순차 배정한다. 서버는 건별 트랜잭션이라 일부만 실패할 수 있으므로
 * 실패를 모아 돌려주고 나머지는 계속 진행한다(같은 현장 재요청은 서버에서 멱등).
 */
export async function assignPurchasesSequentially(
  ids: readonly number[],
  assign: (id: number) => Promise<unknown>,
  describeError: (error: unknown) => string,
): Promise<BulkAssignResult> {
  const result: BulkAssignResult = { succeeded: [], failed: [] }
  for (const id of ids) {
    try {
      await assign(id)
      result.succeeded.push(id)
    } catch (error) {
      result.failed.push({ id, message: describeError(error) })
    }
  }
  return result
}

export function purchaseErrorMessage(cause: unknown, fallback: string): string {
  return (cause as { response?: { data?: { error?: string } } })?.response?.data?.error ?? fallback
}
