import type { Site } from '../types'

export interface SiteDisplay {
  label: string
  archived: boolean
}

/** 목록 행의 siteId를 현장 목록 조회 결과로 이름에 매핑한다. 목록에 없거나 아직 불러오는 중이면 #ID로 보여준다. */
export function buildSiteDisplayMap(sites: Pick<Site, 'id' | 'siteName' | 'archivedAt'>[] | undefined) {
  const map = new Map<number, SiteDisplay>()
  for (const s of sites ?? []) {
    map.set(s.id, { label: s.siteName, archived: !!s.archivedAt })
  }
  return (siteId: number): SiteDisplay => map.get(siteId) ?? { label: `#${siteId}`, archived: false }
}
