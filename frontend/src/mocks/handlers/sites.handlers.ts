import { http, HttpResponse } from 'msw'
import { mockSites } from '../data/sites.data'
import { ApiResponse, PagedData, Site } from '../../types'

// ─────────────────────────────────────────────
// URL 파라미터 타입:
//   PathParams  — URL 경로의 :id 같은 파라미터
//   RequestBody — POST/PUT 요청 바디 타입
//
// http.get<PathParams, RequestBody>(url, resolver)
// ─────────────────────────────────────────────

// 메모리 내 가변 목록 (POST로 추가하면 실제로 반영됨)
let sites = [...mockSites]

export const sitesHandlers = [

  // 목록 조회 — GET /api/v1/sites
  http.get('/api/v1/sites', ({ request }) => {
    const url = new URL(request.url)
    const status = url.searchParams.get('status')

    const filtered = status ? sites.filter((s) => s.status === status) : sites

    return HttpResponse.json<ApiResponse<PagedData<Site>>>({
      success: true,
      data: {
        content: filtered,
        totalElements: filtered.length,
        totalPages: 1,
        number: 0,
        size: 20,
      },
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
  http.post<never, Omit<Site, 'id' | 'totalRevenue' | 'totalCost' | 'margin' | 'marginRate'>>(
    '/api/v1/sites',
    async ({ request }) => {
      const body = await request.json()
      const newSite: Site = {
        ...body,
        id: Math.max(...sites.map((s) => s.id)) + 1,
        totalRevenue: 0,
        totalCost: 0,
        margin: 0,
        marginRate: 0,
      }
      sites = [...sites, newSite]
      return HttpResponse.json<ApiResponse<Site>>(
        { success: true, data: newSite, error: null },
        { status: 201 },
      )
    },
  ),

  // 삭제 — DELETE /api/v1/sites/:id
  http.delete<{ id: string }>('/api/v1/sites/:id', ({ params }) => {
    sites = sites.filter((s) => s.id !== Number(params.id))
    return HttpResponse.json<ApiResponse<null>>({ success: true, data: null, error: null })
  }),
]
