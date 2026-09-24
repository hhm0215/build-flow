import { http, HttpResponse } from 'msw'
import dayjs from 'dayjs'
import { mockTaxInvoices } from '../data/taxes.data'
import { ApiResponse, TaxInvoice, TaxInvoiceCreateRequest, TaxInvoiceUpdateRequest } from '../../types'

let taxInvoices = [...mockTaxInvoices]

const MAX_AMOUNT_CENTS = 999_999_999_999_999n

function toCents(value: number): bigint | null {
  if (!Number.isFinite(value) || value < 0) return null
  const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(value.toString())
  if (!match) return null
  return BigInt(match[1]) * 100n + BigInt((match[2] ?? '').padEnd(2, '0'))
}

function taxAmountError(supplyAmount: number, taxAmount: number): string | null {
  if (supplyAmount < 0) return 'supplyAmount: 공급가액은 0 이상이어야 합니다.'
  if (taxAmount < 0) return 'taxAmount: 세액은 0 이상이어야 합니다.'
  const supplyCents = toCents(supplyAmount)
  const taxCents = toCents(taxAmount)
  if (supplyCents == null || supplyCents > MAX_AMOUNT_CENTS) {
    return 'supplyAmount: 공급가액은 정수 13자리·소수 2자리 이하여야 합니다.'
  }
  if (taxCents == null || taxCents > MAX_AMOUNT_CENTS) {
    return 'taxAmount: 세액은 정수 13자리·소수 2자리 이하여야 합니다.'
  }
  if (supplyCents + taxCents > MAX_AMOUNT_CENTS) {
    return '세금계산서 금액이 허용 범위를 벗어났습니다.'
  }
  return null
}

function taxTotal(supplyAmount: number, taxAmount: number): number {
  return Number(toCents(supplyAmount)! + toCents(taxAmount)!) / 100
}

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

  // 미수금 조회 — 정적 경로를 동적 /:id보다 먼저 등록한다.
  http.get('/api/v1/taxes/outstanding', ({ request }) => {
    const url = new URL(request.url)
    const siteId = url.searchParams.get('siteId')

    let salesTaxes = taxInvoices.filter((t) => t.type === 'SALES')
    if (siteId) salesTaxes = salesTaxes.filter((t) => t.siteId === Number(siteId))

    const outstandingCents = salesTaxes
      .filter((t) => !t.paymentConfirmed)
      .reduce((sum, t) => sum + toCents(t.totalAmount)!, 0n)
    const outstandingAmount = Number(outstandingCents) / 100

    return HttpResponse.json<ApiResponse<{ outstandingAmount: number }>>({
      success: true,
      data: { outstandingAmount },
      error: null,
    })
  }),

  http.get<{ id: string }>('/api/v1/taxes/:id', ({ params }) => {
    const invoice = taxInvoices.find((item) => item.id === Number(params.id))
    if (!invoice) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '세금계산서를 찾을 수 없습니다.' },
        { status: 404 },
      )
    }
    return HttpResponse.json<ApiResponse<TaxInvoice>>({ success: true, data: invoice, error: null })
  }),

  http.post<never, TaxInvoiceCreateRequest>('/api/v1/taxes', async ({ request }) => {
    const body = await request.json()
    const amountError = taxAmountError(body.supplyAmount, body.taxAmount)
    if (amountError) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: amountError },
        { status: 400 },
      )
    }
    const newTax: TaxInvoice = {
      id: Math.max(0, ...taxInvoices.map((t) => t.id)) + 1,
      siteId: body.siteId,
      type: body.type,
      supplyAmount: body.supplyAmount,
      taxAmount: body.taxAmount,
      totalAmount: taxTotal(body.supplyAmount, body.taxAmount),
      counterparty: body.counterparty?.trim() || null,
      issueDate: body.issueDate || null,
      paymentConfirmed: false,
      paymentDate: null,
      memo: body.memo?.trim() || null,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
    taxInvoices = [...taxInvoices, newTax]
    return HttpResponse.json<ApiResponse<TaxInvoice>>(
      { success: true, data: newTax, error: null },
      { status: 201 },
    )
  }),

  http.put<{ id: string }, TaxInvoiceUpdateRequest>('/api/v1/taxes/:id', async ({ params, request }) => {
    const id = Number(params.id)
    const index = taxInvoices.findIndex((invoice) => invoice.id === id)
    if (index === -1) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '세금계산서를 찾을 수 없습니다.' },
        { status: 404 },
      )
    }
    if (taxInvoices[index].paymentConfirmed) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '입금 확인된 세금계산서는 수정하거나 삭제할 수 없습니다.' },
        { status: 409 },
      )
    }
    const body = await request.json()
    const amountError = taxAmountError(body.supplyAmount, body.taxAmount)
    if (amountError) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: amountError },
        { status: 400 },
      )
    }
    taxInvoices[index] = {
      ...taxInvoices[index],
      type: body.type,
      supplyAmount: body.supplyAmount,
      taxAmount: body.taxAmount,
      totalAmount: taxTotal(body.supplyAmount, body.taxAmount),
      counterparty: body.counterparty?.trim() || null,
      issueDate: body.issueDate || null,
      memo: body.memo?.trim() || null,
      updatedAt: new Date().toISOString(),
    }
    return HttpResponse.json<ApiResponse<TaxInvoice>>({ success: true, data: taxInvoices[index], error: null })
  }),

  // 입금 확인 — PATCH /api/v1/taxes/:id/confirm-payment
  http.patch<{ id: string }>('/api/v1/taxes/:id/confirm-payment', async ({ params, request }) => {
    const rawBody = await request.text()
    if (!rawBody) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '요청 본문이 올바르지 않습니다.' },
        { status: 400 },
      )
    }
    let body: { paymentDate?: string | null }
    try {
      body = JSON.parse(rawBody) as { paymentDate?: string | null }
    } catch {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '요청 본문이 올바르지 않습니다.' },
        { status: 400 },
      )
    }
    if (body == null || typeof body !== 'object' ||
        (body.paymentDate != null && (
          typeof body.paymentDate !== 'string' ||
          !/^\d{4}-\d{2}-\d{2}$/.test(body.paymentDate) ||
          Number.isNaN(Date.parse(body.paymentDate)) ||
          new Date(body.paymentDate).toISOString().slice(0, 10) !== body.paymentDate
        ))) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '요청 본문이 올바르지 않습니다.' },
        { status: 400 },
      )
    }
    const index = taxInvoices.findIndex((t) => t.id === Number(params.id))
    if (index === -1) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '세금계산서를 찾을 수 없습니다.' },
        { status: 404 },
      )
    }
    if (taxInvoices[index].type !== 'SALES') {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '매입 세금계산서는 입금 확인할 수 없습니다.' },
        { status: 409 },
      )
    }
    if (taxInvoices[index].paymentConfirmed) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '이미 입금 확인된 세금계산서입니다.' },
        { status: 409 },
      )
    }
    taxInvoices[index] = {
      ...taxInvoices[index],
      paymentConfirmed: true,
      paymentDate: body.paymentDate ?? dayjs().format('YYYY-MM-DD'),
      updatedAt: new Date().toISOString(),
    }
    return HttpResponse.json<ApiResponse<TaxInvoice>>({ success: true, data: taxInvoices[index], error: null })
  }),

  http.delete<{ id: string }>('/api/v1/taxes/:id', ({ params }) => {
    const index = taxInvoices.findIndex((invoice) => invoice.id === Number(params.id))
    if (index === -1) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '세금계산서를 찾을 수 없습니다.' },
        { status: 404 },
      )
    }
    if (taxInvoices[index].paymentConfirmed) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '입금 확인된 세금계산서는 수정하거나 삭제할 수 없습니다.' },
        { status: 409 },
      )
    }
    taxInvoices = taxInvoices.filter((t) => t.id !== Number(params.id))
    return HttpResponse.json<ApiResponse<null>>({ success: true, data: null, error: null })
  }),
]
