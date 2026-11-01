// 매입이 아직 입력되지 않은 현장은 견적액이 그대로 마진처럼 보여 마진율 100%로 오해된다.
// 매입 0원은 "진짜 100% 마진"이 아니라 "데이터 미입력"으로 보고, 손익 판정에서 뺀다.

export interface MarginSiteInput {
  status: string
  estimateAmount: number
  purchaseAmount: number
}

export interface MarginEvaluation {
  /** 판정에 쓴 현장 수 (수주 전 제외, 견적·매입이 모두 1원 이상) */
  evaluatedSiteCount: number
  /** 수주 전이 아니고 견적은 있지만 매입이 0원인 현장 수 */
  noPurchaseSiteCount: number
  estimateAmount: number
  purchaseAmount: number
  margin: number
  /** 판정 대상이 없으면 null */
  marginRate: number | null
}

export const NO_PURCHASE_LABEL = '매입 미입력'
export const NEEDS_PURCHASE_LABEL = '매입 입력 후 계산'

export function hasPurchaseInput(purchaseAmount: number | null | undefined): boolean {
  return typeof purchaseAmount === 'number' && Number.isFinite(purchaseAmount) && purchaseAmount > 0
}

function positive(value: number | null | undefined): boolean {
  return typeof value === 'number' && Number.isFinite(value) && value > 0
}

/**
 * 대시보드 평균 마진율·흑자 판정용 집계.
 * 서버 합계와 같이 수주 전(BIDDING) 현장은 빼고, 매입·견적이 모두 입력된 현장만 가중 평균한다.
 */
export function evaluateMargin(sites: readonly MarginSiteInput[]): MarginEvaluation {
  let evaluatedSiteCount = 0
  let noPurchaseSiteCount = 0
  let estimateAmount = 0
  let purchaseAmount = 0

  for (const site of sites) {
    if (site.status === 'BIDDING') continue
    if (!hasPurchaseInput(site.purchaseAmount)) {
      if (positive(site.estimateAmount)) noPurchaseSiteCount += 1
      continue
    }
    if (!positive(site.estimateAmount)) continue
    evaluatedSiteCount += 1
    estimateAmount += site.estimateAmount
    purchaseAmount += site.purchaseAmount
  }

  const margin = estimateAmount - purchaseAmount
  return {
    evaluatedSiteCount,
    noPurchaseSiteCount,
    estimateAmount,
    purchaseAmount,
    margin,
    marginRate: estimateAmount > 0 ? (margin / estimateAmount) * 100 : null,
  }
}

export function evaluationBasisCaption(evaluation: MarginEvaluation): string {
  return `매입 입력된 ${evaluation.evaluatedSiteCount}개 현장 기준`
}

/** 현장 단위 마진 카드 보조 문구 (현장 상세·목록) */
export function siteMarginRateCaption(estimateAmount: number, purchaseAmount: number): string {
  if (!positive(estimateAmount)) return '아직 산출 전'
  if (!hasPurchaseInput(purchaseAmount)) return `${NO_PURCHASE_LABEL} · ${NEEDS_PURCHASE_LABEL}`
  return `마진율 ${(((estimateAmount - purchaseAmount) / estimateAmount) * 100).toFixed(1)}%`
}
