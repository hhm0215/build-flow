import { http, HttpResponse } from 'msw'
import { mockSites } from '../data/sites.data'
import { ApiResponse, Site, SiteCreateRequest } from '../../types'

let sites = [...mockSites]

export const sitesHandlers = [
  // 목록 조회 — GET /api/v1/sites
  http.get('/api/v1/sites', ({ request }) => {
    const url = new URL(request.url)
    const status = url.searchParams.get('status')
    const filtered = status ? sites.filter((s) => s.status === status) : sites

    return HttpResponse.json<ApiResponse<Site[]>>({
      success: true,
      data: filtered,
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
    return HttpResponse.json<ApiResponse<Site>>({ success: true, data: site, error: null })
  }),

  // 생성 — POST /api/v1/sites
  http.post<never, SiteCreateRequest>('/api/v1/sites', async ({ request }) => {
    const body = await request.json()
    const newSite: Site = {
      id: Math.max(...sites.map((s) => s.id)) + 1,
      siteName: body.siteName,
      client: null,
      address: body.address || '',
      status: 'IN_PROGRESS',
      startDate: body.startDate || '',
      endDate: body.endDate || '',
      memo: body.memo || '',
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
  http.put<{ id: string }, SiteCreateRequest>('/api/v1/sites/:id', async ({ params, request }) => {
    const body = await request.json()
    const index = sites.findIndex((s) => s.id === Number(params.id))
    if (index === -1) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '현장을 찾을 수 없습니다.' },
        { status: 404 },
      )
    }
    sites[index] = { ...sites[index], ...body, updatedAt: new Date().toISOString() }
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
    sites[index] = { ...sites[index], status: body.status as Site['status'], updatedAt: new Date().toISOString() }
    return HttpResponse.json<ApiResponse<Site>>({ success: true, data: sites[index], error: null })
  }),

  // 삭제 — DELETE /api/v1/sites/:id
  http.delete<{ id: string }>('/api/v1/sites/:id', ({ params }) => {
    sites = sites.filter((s) => s.id !== Number(params.id))
    return HttpResponse.json<ApiResponse<null>>({ success: true, data: null, error: null })
  }),
]
