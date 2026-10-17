import { http, HttpResponse } from 'msw'
import { archivedSiteCreateError, archivedSiteLockError } from './sites.handlers'
import { mockWarranties } from '../data/warranties.data'
import { ApiResponse, Warranty, WarrantyUpdateRequest, WarrantyAuditEvent } from '../../types'

let warranties: Warranty[] = [...mockWarranties]
const uploadedPdfs = new Map<number, File>()
// 백엔드 warranty_audit_events와 같은 추가 전용 이력(MSW는 기록자를 metal로 고정)
let audits: WarrantyAuditEvent[] = []

function audit(action: WarrantyAuditEvent['action'], w: Warranty, actor = 'metal') {
  audits = [...audits, {
    id: audits.length + 1, warrantyId: w.id, action, actor,
    insuranceCompany: w.insuranceCompany, policyNumber: w.policyNumber ?? null,
    startDate: w.startDate, endDate: w.endDate, coverageAmount: w.coverageAmount ?? null,
    ocrStatus: w.ocrStatus ?? null, expiryConfirmedAt: null, occurredAt: new Date().toISOString().slice(0, 19),
  }]
}

// OCR 시뮬용 가짜 보험사·증권번호 풀
const FAKE_INSURERS = ['서울보증보험', '한화손해보험', '삼성화재', 'DB손해보험', 'KB손해보험']
function randomPolicyNumber() {
  return 'AUTO-' + Math.random().toString(36).slice(2, 10).toUpperCase()
}

export const warrantiesHandlers = [
  // 변경·확인 이력 — GET /api/v1/warranties/:id/history (삭제된 보증도 이력이 있으면 반환)
  http.get<{ id: string }>('/api/v1/warranties/:id/history', ({ params }) =>
    HttpResponse.json<ApiResponse<WarrantyAuditEvent[]>>({
      success: true, data: audits.filter((a) => a.warrantyId === Number(params.id)), error: null,
    })),

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

    const expiring = warranties.filter((w) => !!w.endDate && !w.expired && w.daysUntilExpiry <= days)

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
      const archived = archivedSiteCreateError(body.siteId)
      if (archived) return archived
      if (!body.siteId || !body.insuranceCompany?.trim() || !body.startDate || !body.endDate
          || body.startDate > body.endDate || (body.coverageAmount != null && body.coverageAmount < 0)) {
        return HttpResponse.json<ApiResponse<null>>(
          { success: false, data: null, error: '보증보험 입력값이 올바르지 않습니다.' },
          { status: 400 },
        )
      }
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
        daysUntilExpiry: Math.max(daysUntilExpiry, 0),
        expired: daysUntilExpiry < 0,
        ocrStatus: 'MANUAL',
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      }
      warranties = [...warranties, newWarranty]
      audit('CREATED', newWarranty)
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
    const locked = archivedSiteLockError(warranties[index].siteId)
    if (locked) return locked

    if (warranties[index].ocrStatus === 'PENDING') {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: 'AI 분석이 끝난 뒤 수정할 수 있습니다.' },
        { status: 409 },
      )
    }
    const body = (await request.json()) as WarrantyUpdateRequest
    if (!body.insuranceCompany?.trim() || !body.startDate || !body.endDate) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '보험사·시작일·종료일은 필수입니다.' },
        { status: 400 },
      )
    }
    if (body.startDate > body.endDate || (body.coverageAmount != null && body.coverageAmount < 0)) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '보증기간 또는 보증금액이 올바르지 않습니다.' },
        { status: 400 },
      )
    }
    const endDate = new Date(body.endDate)
    const now = new Date()
    const diffMs = endDate.getTime() - now.getTime()
    const daysUntilExpiry = Math.ceil(diffMs / (1000 * 60 * 60 * 24))

    warranties[index] = {
      ...warranties[index],
      insuranceCompany: body.insuranceCompany,
      policyNumber: body.policyNumber === undefined ? warranties[index].policyNumber : body.policyNumber,
      coverageAmount: body.coverageAmount === undefined ? warranties[index].coverageAmount : body.coverageAmount,
      startDate: body.startDate,
      endDate: body.endDate,
      memo: body.memo === undefined ? warranties[index].memo : body.memo,
      daysUntilExpiry: Math.max(daysUntilExpiry, 0),
      expired: daysUntilExpiry < 0,
      ocrStatus: 'MANUAL',
      updatedAt: new Date().toISOString(),
    }
    audit('MANUAL_UPDATED', warranties[index])
    return HttpResponse.json<ApiResponse<Warranty>>({ success: true, data: warranties[index], error: null })
  }),

  // 삭제 — DELETE /api/v1/warranties/:id
  http.delete<{ id: string }>('/api/v1/warranties/:id', ({ params }) => {
    const locked = archivedSiteLockError(warranties.find((w) => w.id === Number(params.id))?.siteId)
    if (locked) return locked
    const deleting = warranties.find((w) => w.id === Number(params.id))
    if (deleting) audit('DELETED', deleting)
    uploadedPdfs.delete(Number(params.id))
    warranties = warranties.filter((w) => w.id !== Number(params.id))
    return HttpResponse.json<ApiResponse<null>>({ success: true, data: null, error: null })
  }),

  // PDF 업로드 + OCR 비동기 시뮬 — POST /api/v1/warranties/upload (multipart)
  http.post<never, never, ApiResponse<Warranty | null>>('/api/v1/warranties/upload', async ({ request }) => {
    const formData = await request.formData()
    const siteId = Number(formData.get('siteId') ?? 0)
    const archived = archivedSiteCreateError(siteId)
    if (archived) return archived
    const file = formData.get('file') as File | null
    if (!file) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '파일이 없습니다.' },
        { status: 400 },
      )
    }

    const newId = warranties.length > 0 ? Math.max(...warranties.map((w) => w.id)) + 1 : 1
    const pending: Warranty = {
      id: newId,
      siteId,
      insuranceCompany: '',
      policyNumber: '',
      coverageAmount: 0,
      startDate: '',
      endDate: '',
      memo: `업로드 파일: ${file.name}`,
      daysUntilExpiry: 0,
      expired: false,
      ocrStatus: 'PENDING',
      hasUploadedPdf: true,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
    warranties = [pending, ...warranties]
    uploadedPdfs.set(newId, file)
    audit('UPLOADED', pending)

    // 10초 후 자동 SUCCESS — 폴링이 잡아냄
    setTimeout(() => {
      const idx = warranties.findIndex((w) => w.id === newId)
      if (idx === -1) return
      const today = new Date()
      const end = new Date(today)
      end.setFullYear(end.getFullYear() + 2)
      const days = Math.ceil((end.getTime() - today.getTime()) / (1000 * 60 * 60 * 24))
      warranties[idx] = {
        ...warranties[idx],
        insuranceCompany: FAKE_INSURERS[Math.floor(Math.random() * FAKE_INSURERS.length)],
        policyNumber: randomPolicyNumber(),
        startDate: today.toISOString().slice(0, 10),
        endDate: end.toISOString().slice(0, 10),
        daysUntilExpiry: days,
        ocrStatus: 'SUCCESS',
        updatedAt: new Date().toISOString(),
      }
    }, 10_000)

    return HttpResponse.json<ApiResponse<Warranty>>(
      { success: true, data: pending, error: null },
      { status: 202 },
    )
  }),

  http.get<{ id: string }>('/api/v1/warranties/:id/file', async ({ params }) => {
    const file = uploadedPdfs.get(Number(params.id))
    if (!file) return new HttpResponse(null, { status: 404 })
    return new HttpResponse(await file.arrayBuffer(), {
      status: 200,
      headers: {
        'Content-Type': 'application/pdf',
        'Content-Disposition': `attachment; filename="warranty-${params.id}.pdf"`,
        'Cache-Control': 'no-store',
        'X-Content-Type-Options': 'nosniff',
      },
    })
  }),
]
