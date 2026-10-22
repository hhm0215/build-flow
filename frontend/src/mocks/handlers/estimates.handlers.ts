import { http, HttpResponse, delay } from 'msw'
import { archivedSiteCreateError, archivedSiteLockError } from './sites.handlers'
import { mockEstimates } from '../data/estimates.data'
import { ApiResponse, Estimate, EstimateCreateRequest, EstimateUpdateRequest, ParseResult } from '../../types'
import { validateEstimateAmounts } from '../../utils/estimate'

let estimates = [...mockEstimates]

const SUPERSEDED_ERROR = '수정본으로 대체된 견적서는 이력으로 보존되어 수정·확정·삭제할 수 없습니다.'
const RELATION_ERROR = '수정본·추가공사는 같은 현장의 확정(또는 대체된) 원 견적을 선택해야 합니다.'
const conflict = (error: string) =>
  HttpResponse.json<ApiResponse<null>>({ success: false, data: null, error }, { status: 409 })

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
    const archived = archivedSiteCreateError(body.siteId)
    if (archived) return archived
    const amountError = validateEstimateAmounts(body.items ?? [])
    if (amountError) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: amountError },
        { status: 400 },
      )
    }
    // 서버 EstimateService.resolveRelationSource와 같은 관계 규칙
    const kind = body.kind ?? (body.revisionOfId == null ? 'ORIGINAL' : undefined)
    const source = body.revisionOfId == null ? undefined : estimates.find((e) => e.id === body.revisionOfId)
    const validRelation = kind === 'ORIGINAL'
      ? body.revisionOfId == null
      : kind != null && source != null && source.siteId === body.siteId && source.status !== 'DRAFT'
    if (!kind || !validRelation) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: RELATION_ERROR },
        { status: 400 },
      )
    }
    const items = (body.items || []).map((item, idx) => ({
      id: Date.now() + idx,
      ...item,
    }))
    const totalAmount = items.reduce((sum, item) => sum + item.amount, 0)

    const id = Math.max(...estimates.map((e) => e.id)) + 1
    const newEstimate: Estimate = {
      id,
      siteId: body.siteId,
      title: body.title,
      status: 'DRAFT',
      estimateDate: body.estimateDate,
      totalAmount,
      memo: body.memo || '',
      kind,
      revisionOfId: source?.id ?? null,
      chainRootId: kind === 'REVISION' && source ? source.chainRootId : id,
      supersededById: null,
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

  http.put<{ id: string }, EstimateUpdateRequest>('/api/v1/estimates/:id', async ({ params, request }) => {
    const index = estimates.findIndex((estimate) => estimate.id === Number(params.id))
    if (index === -1) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '견적서를 찾을 수 없습니다.' },
        { status: 404 },
      )
    }
    const locked = archivedSiteLockError(estimates[index].siteId)
    if (locked) return locked
    if (estimates[index].status === 'CONFIRMED') return conflict('이미 확정된 견적서입니다.')
    if (estimates[index].status === 'SUPERSEDED') return conflict(SUPERSEDED_ERROR)
    const body = await request.json()
    const amountError = validateEstimateAmounts(body.items ?? [])
    if (!body.title?.trim() || !body.estimateDate || amountError) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: amountError ?? '견적 제목·견적일을 입력하세요.' },
        { status: 400 },
      )
    }
    const items = body.items.map((item, itemIndex) => ({
      id: Date.now() + itemIndex,
      ...item,
      amount: item.quantity * item.unitPrice,
    }))
    const totalAmount = items.reduce((sum, item) => sum + item.amount, 0)
    estimates[index] = {
      ...estimates[index],
      title: body.title,
      estimateDate: body.estimateDate,
      memo: body.memo ?? '',
      items,
      totalAmount,
      updatedAt: new Date().toISOString(),
    }
    return HttpResponse.json<ApiResponse<Estimate>>({ success: true, data: estimates[index], error: null })
  }),

  http.delete<{ id: string }>('/api/v1/estimates/:id', ({ params }) => {
    const estimate = estimates.find((item) => item.id === Number(params.id))
    if (!estimate) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '견적서를 찾을 수 없습니다.' },
        { status: 404 },
      )
    }
    const deleteLocked = archivedSiteLockError(estimate.siteId)
    if (deleteLocked) return deleteLocked
    if (estimate.status === 'CONFIRMED') return conflict('확정된 견적서는 삭제할 수 없습니다.')
    if (estimate.status === 'SUPERSEDED') return conflict(SUPERSEDED_ERROR)
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
    const locked = archivedSiteLockError(estimates[index].siteId)
    if (locked) return locked
    const target = estimates[index]
    if (target.status === 'CONFIRMED') return conflict('이미 확정된 견적서입니다.')
    if (target.status === 'SUPERSEDED') return conflict(SUPERSEDED_ERROR)
    const now = new Date().toISOString()
    // 같은 수정 체인의 기존 확정본은 대체됨으로 남기고 손익에서 뺀다(서버: estimate.deleted reason=SUPERSEDED).
    estimates = estimates.map((e): Estimate => (
      e.id !== target.id && e.chainRootId === target.chainRootId && e.status === 'CONFIRMED'
        ? { ...e, status: 'SUPERSEDED', supersededById: target.id, updatedAt: now }
        : e))
    estimates[index] = { ...target, status: 'CONFIRMED', updatedAt: now }
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
