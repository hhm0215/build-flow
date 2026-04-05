import { http, HttpResponse } from 'msw'
import { mockEstimates } from '../data/estimates.data'
import { ApiResponse, PagedData, Estimate } from '../../types'

let estimates = [...mockEstimates]

export const estimatesHandlers = [
  http.get('/api/v1/estimates', ({ request }) => {
    const url = new URL(request.url)
    const siteId = url.searchParams.get('siteId')
    const status = url.searchParams.get('status')

    let filtered = estimates
    if (siteId) filtered = filtered.filter((e) => e.siteId === Number(siteId))
    if (status) filtered = filtered.filter((e) => e.status === status)

    return HttpResponse.json<ApiResponse<PagedData<Estimate>>>({
      success: true,
      data: { content: filtered, totalElements: filtered.length, totalPages: 1, number: 0, size: 20 },
      error: null,
    })
  }),

  http.get<{ id: string }>('/api/v1/estimates/:id', ({ params }) => {
    const estimate = estimates.find((e) => e.id === Number(params.id))
    if (!estimate) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '견적서를 찾을 수 없습니다.' },
        { status: 404 },
      )
    }
    return HttpResponse.json<ApiResponse<Estimate>>({ success: true, data: estimate, error: null })
  }),

  http.post<never, Partial<Estimate>>('/api/v1/estimates', async ({ request }) => {
    const body = await request.json()
    const newEstimate: Estimate = {
      id: Math.max(...estimates.map((e) => e.id)) + 1,
      siteId: body.siteId!,
      siteName: body.siteName ?? '',
      title: body.title ?? '새 견적서',
      status: 'DRAFT',
      totalAmount: body.totalAmount ?? 0,
      items: body.items ?? [],
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
    estimates = [...estimates, newEstimate]
    return HttpResponse.json<ApiResponse<Estimate>>(
      { success: true, data: newEstimate, error: null },
      { status: 201 },
    )
  }),
]
