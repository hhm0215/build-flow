import { http, HttpResponse } from 'msw'
import { mockTaxInvoices } from '../data/taxes.data'
import { ApiResponse, TaxInvoice, TaxInvoiceCreateRequest } from '../../types'

let taxInvoices = [...mockTaxInvoices]

export const taxesHandlers = [
  http.get('/api/v1/taxes', ({ request }) => {
    const url = new URL(request.url)
    const siteId = url.searchParams.get('siteId')
    const type = url.searchParams.get('type')

    let filtered = taxInvoices
    if (siteId) filtered = filtered.filter((t) => t.siteId === Number(siteId))
    if (type) filtered = filtered.filter((t) => t.type === type)

    return HttpResponse.json<ApiResponse<TaxInvoice[]>>({
      success: true,
      data: filtered,
      error: null,
    })
  }),

  http.post<never, TaxInvoiceCreateRequest>('/api/v1/taxes', async ({ request }) => {
    const body = await request.json()
    const newTax: TaxInvoice = {
      id: Math.max(...taxInvoices.map((t) => t.id)) + 1,
      siteId: body.siteId,
      type: body.type,
      supplyAmount: body.supplyAmount,
      taxAmount: body.taxAmount,
      totalAmount: body.supplyAmount + body.taxAmount,
      counterparty: body.counterparty,
      issueDate: body.issueDate,
      paymentConfirmed: false,
      paymentDate: null,
      memo: body.memo || '',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
    taxInvoices = [...taxInvoices, newTax]
    return HttpResponse.json<ApiResponse<TaxInvoice>>(
      { success: true, data: newTax, error: null },
      { status: 201 },
    )
  }),

  // 입금 확인 — PATCH /api/v1/taxes/:id/confirm-payment
  http.patch<{ id: string }>('/api/v1/taxes/:id/confirm-payment', ({ params }) => {
    const index = taxInvoices.findIndex((t) => t.id === Number(params.id))
    if (index === -1) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '세금계산서를 찾을 수 없습니다.' },
        { status: 404 },
      )
    }
    taxInvoices[index] = {
      ...taxInvoices[index],
      paymentConfirmed: true,
      paymentDate: new Date().toISOString().split('T')[0],
      updatedAt: new Date().toISOString(),
    }
    return HttpResponse.json<ApiResponse<TaxInvoice>>({ success: true, data: taxInvoices[index], error: null })
  }),

  // 미수금 조회 — GET /api/v1/taxes/outstanding
  http.get('/api/v1/taxes/outstanding', ({ request }) => {
    const url = new URL(request.url)
    const siteId = url.searchParams.get('siteId')

    let salesTaxes = taxInvoices.filter((t) => t.type === 'SALES')
    if (siteId) salesTaxes = salesTaxes.filter((t) => t.siteId === Number(siteId))

    const outstandingAmount = salesTaxes
      .filter((t) => !t.paymentConfirmed)
      .reduce((sum, t) => sum + t.totalAmount, 0)

    return HttpResponse.json<ApiResponse<{ outstandingAmount: number }>>({
      success: true,
      data: { outstandingAmount },
      error: null,
    })
  }),

  http.delete<{ id: string }>('/api/v1/taxes/:id', ({ params }) => {
    taxInvoices = taxInvoices.filter((t) => t.id !== Number(params.id))
    return HttpResponse.json<ApiResponse<null>>({ success: true, data: null, error: null })
  }),
]
