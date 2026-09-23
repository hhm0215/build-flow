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
