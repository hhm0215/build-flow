import { http, HttpResponse } from 'msw'
import { mockWarranties } from '../data/warranties.data'
import { ApiResponse, Warranty } from '../../types'

let warranties: Warranty[] = [...mockWarranties]

// OCR 시뮬용 가짜 보험사·증권번호 풀
const FAKE_INSURERS = ['서울보증보험', '한화손해보험', '삼성화재', 'DB손해보험', 'KB손해보험']
function randomPolicyNumber() {
  return 'AUTO-' + Math.random().toString(36).slice(2, 10).toUpperCase()
}

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

  // PDF 업로드 + OCR 비동기 시뮬 — POST /api/v1/warranties/upload (multipart)
  http.post<never, never, ApiResponse<Warranty | null>>('/api/v1/warranties/upload', async ({ request }) => {
    const formData = await request.formData()
    const siteId = Number(formData.get('siteId') ?? 0)
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
      filePath: `/uploads/warranties/${newId}-${file.name}`,
      ocrStatus: 'PENDING',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
    warranties = [pending, ...warranties]

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
]
