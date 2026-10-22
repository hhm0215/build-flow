import { http, HttpResponse } from 'msw'
import { archivedSiteCreateError, archivedSiteLockError } from './sites.handlers'
import dayjs from 'dayjs'
import { mockTaxInvoices, mockTaxPayments } from '../data/taxes.data'
import {
  ApiResponse, TaxInvoice, TaxInvoiceCreateRequest, TaxInvoiceUpdateRequest, TaxPayment,
} from '../../types'

let taxInvoices = [...mockTaxInvoices]
let taxPayments = [...mockTaxPayments]

const MAX_AMOUNT_CENTS = 999_999_999_999_999n
const PAYMENT_TEXT_MAX = 500
const IMMUTABLE_MESSAGE = '입금 내역이 있는 세금계산서는 수정하거나 삭제할 수 없습니다. 입금을 취소한 뒤 다시 시도해 주세요.'

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

function isIsoDate(value: unknown): value is string {
  return typeof value === 'string' &&
    /^\d{4}-\d{2}-\d{2}$/.test(value) &&
    !Number.isNaN(Date.parse(value)) &&
    new Date(value).toISOString().slice(0, 10) === value
}

function fail(status: number, error: string) {
  return HttpResponse.json<ApiResponse<null>>({ success: false, data: null, error }, { status })
}

function activePaidCents(taxInvoiceId: number): bigint {
  return taxPayments
    .filter((p) => p.taxInvoiceId === taxInvoiceId && !p.canceled)
    .reduce((sum, p) => sum + toCents(p.amount)!, 0n)
}

/** 서버와 같은 규칙으로 유효 입금 합계·잔액·완료 상태를 다시 계산한다. */
function withPaymentSummary(invoice: TaxInvoice): TaxInvoice {
  if (invoice.type !== 'SALES') {
    return { ...invoice, paymentConfirmed: false, paymentDate: null, paidAmount: null, remainingAmount: null, paymentStatus: null }
  }
  const paidCents = activePaidCents(invoice.id)
  const totalCents = toCents(invoice.totalAmount)!
  const completed = paidCents > 0n && paidCents === totalCents
  const latest = taxPayments
    .filter((p) => p.taxInvoiceId === invoice.id && !p.canceled)
    .map((p) => p.paymentDate)
    .sort()
    .pop() ?? null
  return {
    ...invoice,
    paymentConfirmed: completed,
    paymentDate: completed ? latest : null,
    paidAmount: Number(paidCents) / 100,
    remainingAmount: Number(totalCents - paidCents) / 100,
    paymentStatus: completed ? 'PAID' : paidCents > 0n ? 'PARTIAL' : 'UNPAID',
  }
}

function refreshInvoice(index: number) {
  taxInvoices[index] = { ...withPaymentSummary(taxInvoices[index]), updatedAt: new Date().toISOString() }
}

function hasActivePayment(invoice: TaxInvoice): boolean {
  return invoice.paymentConfirmed || activePaidCents(invoice.id) > 0n
}

function nextPaymentId(): number {
  return Math.max(0, ...taxPayments.map((p) => p.id)) + 1
}

async function readJson(request: Request): Promise<Record<string, unknown> | null> {
  const raw = await request.text()
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as unknown
    return parsed != null && typeof parsed === 'object' && !Array.isArray(parsed)
      ? parsed as Record<string, unknown>
      : null
  } catch {
    return null
  }
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

  // 미수금 조회 — 정적 경로를 동적 /:id보다 먼저 등록한다. 미수금 = 총액 − 유효 입금 합계.
  http.get('/api/v1/taxes/outstanding', ({ request }) => {
    const url = new URL(request.url)
    const siteId = url.searchParams.get('siteId')

    let salesTaxes = taxInvoices.filter((t) => t.type === 'SALES')
    if (siteId) salesTaxes = salesTaxes.filter((t) => t.siteId === Number(siteId))

    const outstandingCents = salesTaxes
      .reduce((sum, t) => sum + toCents(t.totalAmount)! - activePaidCents(t.id), 0n)
    const outstandingAmount = Number(outstandingCents) / 100

    return HttpResponse.json<ApiResponse<{ outstandingAmount: number }>>({
      success: true,
      data: { outstandingAmount },
      error: null,
    })
  }),

  http.get<{ id: string }>('/api/v1/taxes/:id', ({ params }) => {
    const invoice = taxInvoices.find((item) => item.id === Number(params.id))
    if (!invoice) return fail(404, '세금계산서를 찾을 수 없습니다.')
    return HttpResponse.json<ApiResponse<TaxInvoice>>({ success: true, data: invoice, error: null })
  }),

  http.post<never, TaxInvoiceCreateRequest>('/api/v1/taxes', async ({ request }) => {
    const body = await request.json()
    const archived = archivedSiteCreateError(body.siteId)
    if (archived) return archived
    const amountError = taxAmountError(body.supplyAmount, body.taxAmount)
    if (amountError) return fail(400, amountError)
    const newTax: TaxInvoice = withPaymentSummary({
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
      paidAmount: null,
      remainingAmount: null,
      paymentStatus: null,
      memo: body.memo?.trim() || null,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    })
    taxInvoices = [...taxInvoices, newTax]
    return HttpResponse.json<ApiResponse<TaxInvoice>>(
      { success: true, data: newTax, error: null },
      { status: 201 },
    )
  }),

  http.put<{ id: string }, TaxInvoiceUpdateRequest>('/api/v1/taxes/:id', async ({ params, request }) => {
    const id = Number(params.id)
    const index = taxInvoices.findIndex((invoice) => invoice.id === id)
    if (index === -1) return fail(404, '세금계산서를 찾을 수 없습니다.')
    const locked = archivedSiteLockError(taxInvoices[index].siteId)
    if (locked) return locked
    if (hasActivePayment(taxInvoices[index])) return fail(409, IMMUTABLE_MESSAGE)
    const body = await request.json()
    const amountError = taxAmountError(body.supplyAmount, body.taxAmount)
    if (amountError) return fail(400, amountError)
    taxInvoices[index] = withPaymentSummary({
      ...taxInvoices[index],
      type: body.type,
      supplyAmount: body.supplyAmount,
      taxAmount: body.taxAmount,
      totalAmount: taxTotal(body.supplyAmount, body.taxAmount),
      counterparty: body.counterparty?.trim() || null,
      issueDate: body.issueDate || null,
      memo: body.memo?.trim() || null,
      updatedAt: new Date().toISOString(),
    })
    return HttpResponse.json<ApiResponse<TaxInvoice>>({ success: true, data: taxInvoices[index], error: null })
  }),

  // 하위 호환 입금 확인 — 남은 잔액 전부를 입금 1건으로 기록한다.
  http.patch<{ id: string }>('/api/v1/taxes/:id/confirm-payment', async ({ params, request }) => {
    const body = await readJson(request)
    if (body == null || (body.paymentDate != null && !isIsoDate(body.paymentDate))) {
      return fail(400, '요청 본문이 올바르지 않습니다.')
    }
    const index = taxInvoices.findIndex((t) => t.id === Number(params.id))
    if (index === -1) return fail(404, '세금계산서를 찾을 수 없습니다.')
    const invoice = taxInvoices[index]
    if (invoice.type !== 'SALES') return fail(409, '매입 세금계산서는 입금 확인할 수 없습니다.')
    if (invoice.paymentConfirmed) return fail(409, '이미 입금 확인된 세금계산서입니다.')
    const remainingCents = toCents(invoice.totalAmount)! - activePaidCents(invoice.id)
    if (remainingCents <= 0n) return fail(400, '입금할 잔액이 없습니다.')
    taxPayments = [...taxPayments, {
      id: nextPaymentId(),
      taxInvoiceId: invoice.id,
      amount: Number(remainingCents) / 100,
      paymentDate: (body.paymentDate as string | undefined) ?? dayjs().format('YYYY-MM-DD'),
      memo: '잔액 전액 입금 확인',
      canceled: false,
      canceledAt: null,
      cancelReason: null,
      createdAt: new Date().toISOString(),
    }]
    refreshInvoice(index)
    return HttpResponse.json<ApiResponse<TaxInvoice>>({ success: true, data: taxInvoices[index], error: null })
  }),

  http.get<{ id: string }>('/api/v1/taxes/:id/payments', ({ params }) => {
    const id = Number(params.id)
    if (!taxInvoices.some((t) => t.id === id)) return fail(404, '세금계산서를 찾을 수 없습니다.')
    const payments = taxPayments
      .filter((p) => p.taxInvoiceId === id)
      .sort((a, b) => a.paymentDate.localeCompare(b.paymentDate) || a.id - b.id)
    return HttpResponse.json<ApiResponse<TaxPayment[]>>({ success: true, data: payments, error: null })
  }),

  // 입금 기록 — 보관 현장도 허용(ADR-021 2차). 초과 입금은 400.
  http.post<{ id: string }>('/api/v1/taxes/:id/payments', async ({ params, request }) => {
    const body = await readJson(request)
    if (body == null) return fail(400, '요청 본문이 올바르지 않습니다.')
    const { amount, paymentDate, memo } = body
    if (amount == null) return fail(400, 'amount: 입금액은 필수입니다.')
    if (typeof amount !== 'number') return fail(400, '요청 본문이 올바르지 않습니다.')
    if (amount <= 0) return fail(400, 'amount: 입금액은 0보다 커야 합니다.')
    const cents = toCents(amount)
    if (cents == null || cents > MAX_AMOUNT_CENTS) {
      return fail(400, 'amount: 입금액은 정수 13자리·소수 2자리 이하여야 합니다.')
    }
    if (paymentDate == null) return fail(400, 'paymentDate: 입금일은 필수입니다.')
    if (!isIsoDate(paymentDate)) return fail(400, '요청 본문이 올바르지 않습니다.')
    if (memo != null && (typeof memo !== 'string' || memo.length > PAYMENT_TEXT_MAX)) {
      return fail(400, 'memo: 입금 메모는 500자 이하여야 합니다.')
    }
    const index = taxInvoices.findIndex((t) => t.id === Number(params.id))
    if (index === -1) return fail(404, '세금계산서를 찾을 수 없습니다.')
    const invoice = taxInvoices[index]
    if (invoice.type !== 'SALES') return fail(409, '매입 세금계산서는 입금 확인할 수 없습니다.')
    const remainingCents = toCents(invoice.totalAmount)! - activePaidCents(invoice.id)
    if (cents > remainingCents) return fail(400, '입금액이 남은 미수금을 초과합니다.')
    const payment: TaxPayment = {
      id: nextPaymentId(),
      taxInvoiceId: invoice.id,
      amount: Number(cents) / 100,
      paymentDate,
      memo: typeof memo === 'string' && memo.trim() ? memo.trim() : null,
      canceled: false,
      canceledAt: null,
      cancelReason: null,
      createdAt: new Date().toISOString(),
    }
    taxPayments = [...taxPayments, payment]
    refreshInvoice(index)
    return HttpResponse.json<ApiResponse<TaxPayment>>({ success: true, data: payment, error: null }, { status: 201 })
  }),

  // 입금 취소 — 삭제하지 않고 취소 시각을 남긴다. 보관 현장은 409(복원 후 정정).
  http.patch<{ id: string; paymentId: string }>(
    '/api/v1/taxes/:id/payments/:paymentId/cancel',
    async ({ params, request }) => {
      const raw = await request.text()
      let reason: unknown = undefined
      if (raw) {
        try {
          reason = (JSON.parse(raw) as { reason?: unknown })?.reason
        } catch {
          return fail(400, '요청 본문이 올바르지 않습니다.')
        }
      }
      if (reason != null && (typeof reason !== 'string' || reason.length > PAYMENT_TEXT_MAX)) {
        return fail(400, 'reason: 취소 사유는 500자 이하여야 합니다.')
      }
      const index = taxInvoices.findIndex((t) => t.id === Number(params.id))
      if (index === -1) return fail(404, '세금계산서를 찾을 수 없습니다.')
      const locked = archivedSiteLockError(taxInvoices[index].siteId)
      if (locked) return locked
      const paymentIndex = taxPayments.findIndex(
        (p) => p.id === Number(params.paymentId) && p.taxInvoiceId === taxInvoices[index].id)
      if (paymentIndex === -1) return fail(404, '입금 내역을 찾을 수 없습니다.')
      if (taxPayments[paymentIndex].canceled) return fail(409, '이미 취소된 입금 내역입니다.')
      const canceled: TaxPayment = {
        ...taxPayments[paymentIndex],
        canceled: true,
        canceledAt: new Date().toISOString(),
        cancelReason: typeof reason === 'string' && reason.trim() ? reason.trim() : null,
      }
      taxPayments = taxPayments.map((p, i) => (i === paymentIndex ? canceled : p))
      refreshInvoice(index)
      return HttpResponse.json<ApiResponse<TaxPayment>>({ success: true, data: canceled, error: null })
    },
  ),

  http.delete<{ id: string }>('/api/v1/taxes/:id', ({ params }) => {
    const index = taxInvoices.findIndex((invoice) => invoice.id === Number(params.id))
    if (index === -1) return fail(404, '세금계산서를 찾을 수 없습니다.')
    const locked = archivedSiteLockError(taxInvoices[index].siteId)
    if (locked) return locked
    if (hasActivePayment(taxInvoices[index])) return fail(409, IMMUTABLE_MESSAGE)
    const id = Number(params.id)
    taxPayments = taxPayments.filter((p) => p.taxInvoiceId !== id)
    taxInvoices = taxInvoices.filter((t) => t.id !== id)
    return HttpResponse.json<ApiResponse<null>>({ success: true, data: null, error: null })
  }),
]
