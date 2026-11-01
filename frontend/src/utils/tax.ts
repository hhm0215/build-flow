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

export const TAX_AUTO_HINT = '공급가액의 10%(원 미만 절사)를 자동으로 채웁니다. 직접 고친 세액은 유지됩니다.'

/**
 * 공급가액의 부가세 10% 기본값. 원 미만은 절사(내림)한다.
 * 서버는 세액을 계산하지 않고 입력값을 소수 2자리로 그대로 저장하므로 화면 기본값 규칙만 여기서 정한다.
 * 금액이 비었거나 저장 범위를 벗어나면 null을 돌려 자동 채움을 하지 않는다.
 */
export function defaultTaxAmount(supplyAmount: number | null | undefined): number | null {
  if (supplyAmount == null) return null
  const supplyCents = toCents(supplyAmount)
  if (supplyCents == null || supplyCents > MAX_AMOUNT_CENTS) return null
  // 10%를 센트로 구하면 supplyCents / 10, 원 미만 절사까지 하면 supplyCents / 1000
  return Number(supplyCents / 1000n)
}

/**
 * 공급가액이 바뀔 때 세액을 다시 자동으로 채울지 결정한다.
 * 세액이 비어 있거나 사용자가 직접 고치지 않았으면 채우고, 직접 고친 값은 덮어쓰지 않는다.
 */
export function shouldAutoFillTax(currentTax: number | null | undefined, taxTouched: boolean): boolean {
  return currentTax == null || !taxTouched
}

/** 수정 폼 초기 상태: 저장된 세액이 공급가액 10% 기본값과 다르면 사용자가 정한 값으로 보고 보존한다. */
export function isCustomTaxAmount(supplyAmount: number, taxAmount: number): boolean {
  return defaultTaxAmount(supplyAmount) !== taxAmount
}
