import type { Client, Site } from '../types'

export interface ClientFilterOption {
  value: number
  label: string
  archived: boolean
}

/**
 * 현장 목록 거래처 필터의 선택지.
 *
 * - 거래처 목록(`/clients`)이 기준이고, 목록 조회가 실패하거나 아직 없어도
 *   현장 응답에 포함된 거래처로 선택지를 채운다(현장 필터는 계속 쓸 수 있게).
 * - 보관 거래처도 연결 현장이 남아 있을 수 있으므로 선택지에 남기되 뒤로 보내고 표시한다.
 * - URL로 들어온 ID가 어느 목록에도 없으면 숫자만 보이지 않도록 별도 선택지를 만든다.
 */
export function buildClientFilterOptions(
  clients: readonly Client[] | undefined,
  sites: readonly Site[],
  selectedId?: number,
): ClientFilterOption[] {
  const byId = new Map<number, ClientFilterOption>()
  for (const client of clients ?? []) {
    byId.set(client.id, { value: client.id, label: client.companyName, archived: !!client.archivedAt })
  }
  for (const site of sites) {
    const client = site.client
    if (!client || byId.has(client.id)) continue
    byId.set(client.id, { value: client.id, label: client.companyName, archived: !!client.archivedAt })
  }
  const options = [...byId.values()].sort(
    (a, b) => Number(a.archived) - Number(b.archived) || a.label.localeCompare(b.label, 'ko'),
  )
  if (selectedId != null && !byId.has(selectedId)) {
    options.push({ value: selectedId, label: `거래처 #${selectedId} (목록에 없음)`, archived: false })
  }
  return options
}

/** 거래처 필터 술어. 미지정이면 모든 현장을 통과시키고, 지정되면 거래처 미지정 현장은 제외한다. */
export function matchesClientFilter(site: Pick<Site, 'client'>, clientId: number | undefined): boolean {
  if (clientId == null) return true
  return site.client?.id === clientId
}

/**
 * 거래처 목록 → 현장 목록 이동 경로.
 * 보관 현장이 섞여 있으면 기본 보관 제외 필터 때문에 연결 현장 수와 결과가 어긋나므로
 * 보관 포함(archive=ALL)으로 연다.
 */
export function sitesOfClientPath(clientId: number, archivedSiteCount = 0): string {
  const params = new URLSearchParams({ clientId: String(clientId) })
  if (archivedSiteCount > 0) params.set('archive', 'ALL')
  return `/sites?${params.toString()}`
}
