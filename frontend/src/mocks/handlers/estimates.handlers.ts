import { http, HttpResponse, delay } from 'msw'
import { mockEstimates } from '../data/estimates.data'
import { ApiResponse, Estimate, EstimateCreateRequest, ParseResult } from '../../types'

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

  http.post<never, never, ApiResponse<ParseResult | null>>('/api/v1/estimates/parse', async ({ request }) => {
    const formData = await request.formData()
    const file = formData.get('file')
    if (!(file instanceof File)) {
      return HttpResponse.json(
        { success: false, data: null, error: '업로드된 파일이 없습니다.' },
        { status: 400 },
      )
    }

    await delay(1500)

    const items = [
      { itemName: '시멘트', unit: 'EA', quantity: 50, unitPrice: 8000, amount: 400000 },
      { itemName: '모래', unit: 'm³', quantity: 12, unitPrice: 35000, amount: 420000 },
      { itemName: '자갈', unit: 'm³', quantity: 8, unitPrice: 42000, amount: 336000 },
      { itemName: '철근 10mm', unit: 'TON', quantity: 2, unitPrice: 980000, amount: 1960000 },
      { itemName: '합판 12mm', unit: '장', quantity: 30, unitPrice: 18000, amount: 540000 },
      { itemName: '각재 30x30', unit: 'EA', quantity: 100, unitPrice: 4500, amount: 450000 },
      { itemName: '단열재', unit: 'm²', quantity: 80, unitPrice: 12000, amount: 960000 },
      { itemName: '석고보드 9.5mm', unit: '장', quantity: 60, unitPrice: 8500, amount: 510000 },
      { itemName: '도장 인건비', unit: '일', quantity: 5, unitPrice: 180000, amount: 900000 },
      { itemName: '미장 인건비', unit: '일', quantity: 4, unitPrice: 200000, amount: 800000 },
    ]

    return HttpResponse.json({
      success: true,
      data: { fileName: file.name, itemCount: items.length, items },
      error: null,
    })
  }),
]
