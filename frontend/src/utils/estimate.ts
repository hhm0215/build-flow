import type { Estimate, Purchase } from '../types'

export function sumConfirmedEstimateAmount(estimates: Estimate[]): number {
  return estimates
    .filter((estimate) => estimate.status === 'CONFIRMED')
    .reduce((sum, estimate) => sum + estimate.totalAmount, 0)
}

// 서버와 같은 체인 규칙: 수정본은 원 견적의 체인 루트를 잇고, 원본·추가공사는 자기 자신이 루트다.
export function chainRootOf(estimate: Pick<Estimate, 'id' | 'chainRootId'>): number {
  return estimate.chainRootId ?? estimate.id
}

/** 같은 수정 체인의 견적(원본 + 수정본)을 생성 순으로 반환한다. */
export function estimateChain(estimates: Estimate[], target: Estimate): Estimate[] {
  const root = chainRootOf(target)
  return estimates
    .filter((estimate) => estimate.siteId === target.siteId && chainRootOf(estimate) === root)
    .sort((a, b) => a.id - b.id)
}

/** 체인 구성원 중 하나를 원 견적으로 둔 추가공사 견적. */
export function additionsOfChain(estimates: Estimate[], chain: Estimate[]): Estimate[] {
  const ids = new Set(chain.map((estimate) => estimate.id))
  return estimates
    .filter((estimate) => estimate.kind === 'ADDITIONAL' && estimate.revisionOfId != null && ids.has(estimate.revisionOfId))
    .sort((a, b) => a.id - b.id)
}

/**
 * 견적 카드/목록 정렬: 업무 날짜(견적일) 최신순. 같은 견적일이면 등록(생성) 시각, 그다음 id 역순.
 * 과거 견적을 나중에 등록해도 등록일이 아닌 견적일 기준으로 놓인다. 견적일이 없으면 맨 뒤.
 */
export function compareEstimatesByDateDesc(
  a: Pick<Estimate, 'id' | 'estimateDate' | 'createdAt'>,
  b: Pick<Estimate, 'id' | 'estimateDate' | 'createdAt'>,
): number {
  const aDate = a.estimateDate?.slice(0, 10) ?? ''
  const bDate = b.estimateDate?.slice(0, 10) ?? ''
  if (aDate !== bDate) {
    if (!aDate) return 1
    if (!bDate) return -1
    return bDate.localeCompare(aDate)
  }
  return (b.createdAt ?? '').localeCompare(a.createdAt ?? '') || b.id - a.id
}

/** 화면 표시용 날짜(YYYY-MM-DD). 값이 없으면 '-'이며 다른 날짜로 대체하지 않는다. */
export function formatDocumentDate(value?: string | null): string {
  if (!value) return '-'
  return value.split('T')[0]
}

/** target을 확정하면 대체될 현재 확정본(같은 체인에 최대 1개). */
export function estimateSupersededByConfirming(estimates: Estimate[], target: Estimate): Estimate | null {
  return estimateChain(estimates, target)
    .find((estimate) => estimate.id !== target.id && estimate.status === 'CONFIRMED') ?? null
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

// 서버 @Size와 같은 UTF-16 길이로 자른다. 끝에서 서로게이트 쌍이 갈라지면 그 반쪽은 버린다.
export function truncateUtf16(value: string, max: number): string {
  if (value.length <= max) return value
  const cut = value.slice(0, max)
  const last = cut.charCodeAt(cut.length - 1)
  return last >= 0xd800 && last <= 0xdbff ? cut.slice(0, -1) : cut
}

// 공내역서 파일명으로 채우는 기본 견적 제목. 서버 한도(200자)를 넘지 않게 자른다.
export function estimateTitleFromFileName(fileName: string): string {
  return truncateUtf16(fileName.replace(/\.(xlsx|xls)$/i, '').trim(), 200)
}
