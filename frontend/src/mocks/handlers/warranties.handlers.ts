import { http, HttpResponse } from 'msw'
import { mockWarranties } from '../data/warranties.data'
import { ApiResponse, Warranty } from '../../types'

let warranties = [...mockWarranties]

export const warrantiesHandlers = [
  // 전체 목록 — GET /api/v1/warranties?siteId=
  http.get('/api/v1/warranties', ({ request }) => {
    const url = new URL(request.url)
    const siteId = url.searchParams.get('siteId')

    let filtered = warranties
    if (siteId) filtered = filtered.filter((w) => w.siteId === Number(siteId))

    return HttpResponse.json<ApiResponse<Warranty[]>>({
      success: true,
      data: filtered,
      error: null,
    })
  }),

  // 만료 임박 조회 — GET /api/v1/warranties/expiring?days=30
  http.get('/api/v1/warranties/expiring', ({ request }) => {
    const url = new URL(request.url)
    const days = Number(url.searchParams.get('days') ?? 30)

    const expiring = warranties.filter((w) => !w.expired && w.daysUntilExpiry <= days)

    return HttpResponse.json<ApiResponse<Warranty[]>>({
      success: true,
      data: expiring,
      error: null,
    })
  }),

  // 생성 — POST /api/v1/warranties
  http.post<never, Omit<Warranty, 'id' | 'daysUntilExpiry' | 'expired' | 'createdAt' | 'updatedAt'>>(
    '/api/v1/warranties',
    async ({ request }) => {
      const body = await request.json()
      const endDate = new Date(body.endDate)
      const now = new Date()
      const diffMs = endDate.getTime() - now.getTime()
      const daysUntilExpiry = Math.ceil(diffMs / (1000 * 60 * 60 * 24))

      const newWarranty: Warranty = {
        id: Math.max(...warranties.map((w) => w.id)) + 1,
        siteId: body.siteId,
        insuranceCompany: body.insuranceCompany,
        policyNumber: body.policyNumber,
        coverageAmount: body.coverageAmount,
        startDate: body.startDate,
        endDate: body.endDate,
        memo: body.memo || '',
        daysUntilExpiry,
        expired: daysUntilExpiry < 0,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      }
      warranties = [...warranties, newWarranty]
      return HttpResponse.json<ApiResponse<Warranty>>(
        { success: true, data: newWarranty, error: null },
        { status: 201 },
      )
    },
  ),

  // 수정 — PUT /api/v1/warranties/:id
  http.put<{ id: string }>('/api/v1/warranties/:id', async ({ params, request }) => {
    const index = warranties.findIndex((w) => w.id === Number(params.id))
    if (index === -1) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '하자보증보험을 찾을 수 없습니다.' },
        { status: 404 },
      )
    }

    const body = (await request.json()) as Partial<Warranty>
    const endDate = new Date(body.endDate ?? warranties[index].endDate)
    const now = new Date()
    const diffMs = endDate.getTime() - now.getTime()
    const daysUntilExpiry = Math.ceil(diffMs / (1000 * 60 * 60 * 24))

    warranties[index] = {
      ...warranties[index],
      ...body,
      daysUntilExpiry,
      expired: daysUntilExpiry < 0,
      updatedAt: new Date().toISOString(),
    }
    return HttpResponse.json<ApiResponse<Warranty>>({ success: true, data: warranties[index], error: null })
  }),

  // 삭제 — DELETE /api/v1/warranties/:id
  http.delete<{ id: string }>('/api/v1/warranties/:id', ({ params }) => {
    warranties = warranties.filter((w) => w.id !== Number(params.id))
    return HttpResponse.json<ApiResponse<null>>({ success: true, data: null, error: null })
  }),
]
