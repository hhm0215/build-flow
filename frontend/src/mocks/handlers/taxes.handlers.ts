import { http, HttpResponse } from 'msw'
import { mockTaxInvoices } from '../data/taxes.data'
import { ApiResponse, PagedData, TaxInvoice } from '../../types'

let taxInvoices = [...mockTaxInvoices]

export const taxesHandlers = [
  http.get('/api/v1/taxes', ({ request }) => {
    const url = new URL(request.url)
    const siteId = url.searchParams.get('siteId')
    const type = url.searchParams.get('type')
    const status = url.searchParams.get('status')

    let filtered = taxInvoices
    if (siteId) filtered = filtered.filter((t) => t.siteId === Number(siteId))
    if (type) filtered = filtered.filter((t) => t.type === type)
    if (status) filtered = filtered.filter((t) => t.status === status)

    return HttpResponse.json<ApiResponse<PagedData<TaxInvoice>>>({
      success: true,
      data: { content: filtered, totalElements: filtered.length, totalPages: 1, number: 0, size: 20 },
      error: null,
    })
  }),

  // 입금 확인 처리 — PATCH /api/v1/taxes/:id/paid
  http.patch<{ id: string }>('/api/v1/taxes/:id/paid', ({ params }) => {
    taxInvoices = taxInvoices.map((t) =>
      t.id === Number(params.id)
        ? { ...t, status: 'PAID', paidDate: new Date().toISOString().split('T')[0], unpaidAmount: 0 }
        : t,
    )
    const updated = taxInvoices.find((t) => t.id === Number(params.id))
    return HttpResponse.json<ApiResponse<TaxInvoice>>({ success: true, data: updated!, error: null })
  }),
]
