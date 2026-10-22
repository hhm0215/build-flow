import { http, HttpResponse } from 'msw'
import { mockSites } from '../data/sites.data'
import { ApiResponse, Site, SiteCreateRequest, SiteStatus, SiteUpdateRequest } from '../../types'
import { canTransitionSiteStatus, INITIAL_SITE_STATUSES, SITE_STATUSES } from '../../utils/siteStatus'
import { findMockClientById } from './clients.handlers'

let sites = [...mockSites]

function withCurrentClient(site: Site): Site {
  return { ...site, client: site.client ? findMockClientById(site.client.id) : null }
}

function notFound() {
  return HttpResponse.json<ApiResponse<null>>(
    { success: false, data: null, error: '현장을 찾을 수 없습니다.' },
    { status: 404 },
  )
}

function archivedClientError() {
  return HttpResponse.json<ApiResponse<null>>(
    { success: false, data: null, error: '보관된 거래처는 현장에 새로 연결할 수 없습니다. 거래처를 복원한 뒤 연결해 주세요.' },
    { status: 400 },
  )
}

function archivedConflict() {
  return HttpResponse.json<ApiResponse<null>>(
    { success: false, data: null, error: '보관된 현장은 복원한 뒤 수정할 수 있습니다.' },
    { status: 409 },
  )
}

/** 보관 현장에 새 자료 등록 시 각 서비스가 돌려주는 400 (백엔드 SITE_ARCHIVED) */
export function archivedSiteCreateError(siteId: number | null | undefined) {
  if (siteId == null || !sites.some((s) => s.id === Number(siteId) && s.archivedAt)) return null
  return HttpResponse.json<ApiResponse<null>>(
    { success: false, data: null, error: '보관된 현장에는 새 자료를 등록할 수 없습니다. 현장을 복원한 뒤 등록해 주세요.' },
    { status: 400 },
  )
}

/** 보관 현장 소속 기존 자료의 수정·삭제 409 (백엔드 SITE_ARCHIVED_DOCUMENT_LOCKED). 입금 확인은 허용 */
export function archivedSiteLockError(siteId: number | null | undefined) {
  if (siteId == null || !sites.some((s) => s.id === Number(siteId) && s.archivedAt)) return null
  return HttpResponse.json<ApiResponse<null>>(
    { success: false, data: null, error: '보관된 현장의 자료는 현장을 복원한 뒤 수정·삭제할 수 있습니다.' },
    { status: 409 },
  )
}

export const sitesHandlers = [
  // 목록 조회 — GET /api/v1/sites
  http.get('/api/v1/sites', ({ request }) => {
    const url = new URL(request.url)
    const status = url.searchParams.get('status')
    const archived = url.searchParams.get('archived')
    const filtered = sites
      .filter((s) => !status || s.status === status)
      .filter((s) => archived == null || (archived === 'true') === (s.archivedAt != null))

    return HttpResponse.json<ApiResponse<Site[]>>({
      success: true,
      data: filtered.map(withCurrentClient),
      error: null,
    })
  }),

  // 단건 조회 — GET /api/v1/sites/:id
  http.get<{ id: string }>('/api/v1/sites/:id', ({ params }) => {
    const site = sites.find((s) => s.id === Number(params.id))
    if (!site) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '현장을 찾을 수 없습니다.' },
        { status: 404 },
      )
    }
    return HttpResponse.json<ApiResponse<Site>>({ success: true, data: withCurrentClient(site), error: null })
  }),

  // 생성 — POST /api/v1/sites
  http.post<never, SiteCreateRequest>('/api/v1/sites', async ({ request }) => {
    const body = await request.json()
    const client = findMockClientById(body.clientId)
    if (client?.archivedAt) return archivedClientError()
    if (body.clientId != null && !client) {
      return HttpResponse.json(
        { success: false, error: '거래처를 찾을 수 없습니다.' },
        { status: 404 },
      )
    }
    if (body.status != null && !INITIAL_SITE_STATUSES.includes(body.status)) {
      return HttpResponse.json(
        { success: false, error: '새 현장은 수주 전 또는 시공 중으로만 등록할 수 있습니다.' },
        { status: 400 },
      )
    }
    const newSite: Site = {
      id: Math.max(0, ...sites.map((s) => s.id)) + 1,
      siteName: body.siteName,
      client,
      address: body.address || '',
      status: body.status ?? 'IN_PROGRESS',
      startDate: body.startDate || '',
      endDate: body.endDate || '',
      memo: body.memo || '',
      archivedAt: null,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
    sites = [...sites, newSite]
    return HttpResponse.json<ApiResponse<Site>>(
      { success: true, data: newSite, error: null },
      { status: 201 },
    )
  }),

  // 수정 — PUT /api/v1/sites/:id
  http.put<{ id: string }, SiteUpdateRequest>('/api/v1/sites/:id', async ({ params, request }) => {
    const body = await request.json()
    const index = sites.findIndex((s) => s.id === Number(params.id))
    if (index === -1) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '현장을 찾을 수 없습니다.' },
        { status: 404 },
      )
    }
    if (sites[index].archivedAt) return archivedConflict()
    if (!body.siteName?.trim()) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '현장명은 필수입니다.' },
        { status: 400 },
      )
    }
    const client = findMockClientById(body.clientId ?? undefined)
    if (client?.archivedAt && client.id !== sites[index].client?.id) return archivedClientError()
    if (body.clientId != null && !client) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '거래처를 찾을 수 없습니다.' },
        { status: 404 },
      )
    }
    sites[index] = {
      ...sites[index],
      siteName: body.siteName,
      client,
      address: body.address ?? null,
      startDate: body.startDate ?? null,
      endDate: body.endDate ?? null,
      memo: body.memo ?? null,
      updatedAt: new Date().toISOString(),
    }
    return HttpResponse.json<ApiResponse<Site>>({ success: true, data: sites[index], error: null })
  }),

  // 상태 변경 — PATCH /api/v1/sites/:id/status
  http.patch<{ id: string }, { status: string }>('/api/v1/sites/:id/status', async ({ params, request }) => {
    const body = await request.json()
    const index = sites.findIndex((s) => s.id === Number(params.id))
    if (index === -1) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '현장을 찾을 수 없습니다.' },
        { status: 404 },
      )
    }
    if (sites[index].archivedAt) return archivedConflict()
    const next = body.status as SiteStatus
    if (!SITE_STATUSES.includes(next) || !canTransitionSiteStatus(sites[index].status, next)) {
      return HttpResponse.json(
        { success: false, error: '수주 전(BIDDING) 현장은 시공 중으로만 바꿀 수 있고, 수주한 현장은 수주 전으로 되돌릴 수 없습니다.' },
        { status: 400 },
      )
    }
    sites[index] = { ...sites[index], status: next, updatedAt: new Date().toISOString() }
    return HttpResponse.json<ApiResponse<Site>>({ success: true, data: sites[index], error: null })
  }),

  // 보관 — PATCH /api/v1/sites/:id/archive (이미 보관이면 최초 시각 유지)
  http.patch<{ id: string }>('/api/v1/sites/:id/archive', ({ params }) => {
    const index = sites.findIndex((s) => s.id === Number(params.id))
    if (index === -1) return notFound()
    sites[index] = { ...sites[index], archivedAt: sites[index].archivedAt ?? new Date().toISOString() }
    return HttpResponse.json<ApiResponse<Site>>({ success: true, data: withCurrentClient(sites[index]), error: null })
  }),

  // 복원 — PATCH /api/v1/sites/:id/restore
  http.patch<{ id: string }>('/api/v1/sites/:id/restore', ({ params }) => {
    const index = sites.findIndex((s) => s.id === Number(params.id))
    if (index === -1) return notFound()
    sites[index] = { ...sites[index], archivedAt: null }
    return HttpResponse.json<ApiResponse<Site>>({ success: true, data: withCurrentClient(sites[index]), error: null })
  }),

  // 하드 삭제는 계속 차단(ADR-021) — DELETE /api/v1/sites/:id
  http.delete<{ id: string }>('/api/v1/sites/:id', ({ params }) => {
    if (!sites.some((s) => s.id === Number(params.id))) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '현장을 찾을 수 없습니다.' },
        { status: 404 },
      )
    }
    return HttpResponse.json<ApiResponse<null>>(
      { success: false, data: null, error: '현장 삭제는 연결 자료 보존을 위해 지원하지 않습니다. 보관 기능을 사용해 주세요.' },
      { status: 409 },
    )
  }),
]
