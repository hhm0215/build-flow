import { http, HttpResponse } from 'msw'
import { mockEstimates } from '../data/estimates.data'
import { ApiResponse, Estimate, EstimateCreateRequest } from '../../types'

let estimates = [...mockEstimates]

export const estimatesHandlers = [
  http.get('/api/v1/estimates', ({ request }) => {
    const url = new URL(request.url)
    const siteId = url.searchParams.get('siteId')
    const status = url.searchParams.get('status')

    let filtered = estimates
    if (siteId) filtered = filtered.filter((e) => e.siteId === Number(siteId))
    if (status) filtered = filtered.filter((e) => e.status === status)

    return HttpResponse.json<ApiResponse<Estimate[]>>({
      success: true,
      data: filtered,
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

  http.post<never, EstimateCreateRequest>('/api/v1/estimates', async ({ request }) => {
    const body = await request.json()
    const items = (body.items || []).map((item, idx) => ({
      id: Date.now() + idx,
      ...item,
    }))
    const totalAmount = items.reduce((sum, item) => sum + item.amount, 0)

    const newEstimate: Estimate = {
      id: Math.max(...estimates.map((e) => e.id)) + 1,
      siteId: body.siteId,
      title: body.title,
      status: 'DRAFT',
      estimateDate: body.estimateDate,
      totalAmount,
      memo: body.memo || '',
      items,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
    estimates = [...estimates, newEstimate]
    return HttpResponse.json<ApiResponse<Estimate>>(
      { success: true, data: newEstimate, error: null },
      { status: 201 },
    )
  }),

  http.delete<{ id: string }>('/api/v1/estimates/:id', ({ params }) => {
    estimates = estimates.filter((e) => e.id !== Number(params.id))
    return HttpResponse.json<ApiResponse<null>>({ success: true, data: null, error: null })
  }),

  http.patch<{ id: string }>('/api/v1/estimates/:id/confirm', ({ params }) => {
    const index = estimates.findIndex((e) => e.id === Number(params.id))
    if (index === -1) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '견적서를 찾을 수 없습니다.' },
        { status: 404 },
      )
    }
    estimates[index] = { ...estimates[index], status: 'CONFIRMED', updatedAt: new Date().toISOString() }
    return HttpResponse.json<ApiResponse<Estimate>>({ success: true, data: estimates[index], error: null })
  }),
]
