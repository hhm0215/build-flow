const MAX_AMOUNT_CENTS = 999_999_999_999_999n

function toCents(value: number): bigint | null {
  if (!Number.isFinite(value) || value < 0) return null
  const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(value.toString())
  if (!match) return null
  const fraction = (match[2] ?? '').padEnd(2, '0')
  return BigInt(match[1]) * 100n + BigInt(fraction)
}

export function validateTaxAmounts(supplyAmount: number, taxAmount: number): string | null {
  const supplyCents = toCents(supplyAmount)
  const taxCents = toCents(taxAmount)
  if (supplyCents == null || supplyCents > MAX_AMOUNT_CENTS) {
    return '공급가액은 0 이상, 정수 13자리·소수 2자리 이하여야 합니다.'
  }
  if (taxCents == null || taxCents > MAX_AMOUNT_CENTS) {
    return '세액은 0 이상, 정수 13자리·소수 2자리 이하여야 합니다.'
  }
  if (supplyCents + taxCents > MAX_AMOUNT_CENTS) {
    return '세금계산서 합계가 저장 가능한 범위를 초과했습니다.'
  }
  return null
}
