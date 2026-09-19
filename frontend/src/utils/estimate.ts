import type { Estimate, Purchase } from '../types'

export function sumConfirmedEstimateAmount(estimates: Estimate[]): number {
  return estimates
    .filter((estimate) => estimate.status === 'CONFIRMED')
    .reduce((sum, estimate) => sum + estimate.totalAmount, 0)
}

export function calculateDocumentProfit(estimates: Estimate[], purchases: Purchase[]) {
  const totalEstimateAmount = sumConfirmedEstimateAmount(estimates)
  const totalPurchaseAmount = purchases.reduce((sum, purchase) => sum + purchase.totalAmount, 0)
  const margin = totalEstimateAmount - totalPurchaseAmount
  return {
    totalEstimateAmount,
    totalPurchaseAmount,
    margin,
    marginRate: totalEstimateAmount > 0 ? (margin / totalEstimateAmount) * 100 : 0,
  }
}

// 견적 항목과 합계는 DB DECIMAL(..., 2)에 저장된다. 저장 전 반올림으로
// 화면의 수량·단가·금액이 서로 어긋나는 입력은 허용하지 않는다.
export function validateEstimateAmounts(items: { quantity: number; unitPrice: number }[]): string | null {
  if (!items.length) return '견적 항목은 최소 1개 이상 필요합니다.'
  const maxAmountCents = 999_999_999_999_999n
  let totalCents = 0n
  const toCents = (value: number): bigint | null => {
    if (!Number.isFinite(value)) return null
    const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(value.toString())
    if (!match) return null
    return BigInt(match[1]) * 100n + BigInt((match[2] ?? '').padEnd(2, '0'))
  }
  for (const item of items) {
    const quantityCents = toCents(item.quantity)
    const priceCents = toCents(item.unitPrice)
    if (quantityCents === null || priceCents === null || quantityCents <= 0n
      || quantityCents > 9_999_999_999n || priceCents > maxAmountCents) {
      return '수량·단가는 저장 가능한 범위에서 소수 둘째 자리까지만 입력하세요.'
    }
    const product = quantityCents * priceCents
    if (product % 100n !== 0n) {
      return '수량 × 단가의 금액은 소수 둘째 자리까지 정확히 표현되어야 합니다.'
    }
    totalCents += product / 100n
    if (totalCents > maxAmountCents) return '견적 금액이 저장 가능한 범위를 초과합니다.'
  }
  return null
}
