import type { SiteStatus } from '../types'

/** 백엔드 SiteStatus 선언 순서(수주 전 → 완료)와 같다. */
export const SITE_STATUSES: SiteStatus[] = ['BIDDING', 'IN_PROGRESS', 'SETTLING', 'WARRANTY', 'COMPLETED']

/** 새 현장 등록 시 고를 수 있는 시작 상태. 서버도 같은 규칙으로 검증한다. */
export const INITIAL_SITE_STATUSES: SiteStatus[] = ['IN_PROGRESS', 'BIDDING']

/**
 * 서버 `SiteStatus.canTransitionTo`와 같은 규칙.
 * 수주 전(BIDDING)은 시공 중으로만 나가고, 수주 이후 상태는 수주 전으로 돌아오지 않는다.
 */
export function canTransitionSiteStatus(from: SiteStatus, to: SiteStatus): boolean {
  if (from === to) return true
  if (from === 'BIDDING') return to === 'IN_PROGRESS'
  return to !== 'BIDDING'
}

/** 대시보드 "진행 중인 현장" 카드의 보조 문구. 수주 전 현장이 있으면 건수를 덧붙인다. */
export function siteCountCaption(sitesByStatus: Record<string, number>, totalSites: number): string {
  const bidding = sitesByStatus['BIDDING'] || 0
  return bidding > 0 ? `전체 ${totalSites}개 현장 · 수주 전 ${bidding}건` : `전체 ${totalSites}개 현장`
}
