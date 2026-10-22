import { http, HttpResponse } from 'msw'
import { archivedSiteCreateError, archivedSiteLockError } from './sites.handlers'
import { mockPurchases } from '../data/purchases.data'
import { findMockTaxInvoice, listMockTaxInvoices } from './taxes.handlers'
import {
  ApiResponse, Purchase, PurchaseCreateRequest, PurchaseEvidenceLinkRequest, PurchaseEvidenceSummary,
  PurchaseInvoiceEvidence, PurchaseUpdateRequest,
} from '../../types'

let purchases = [...mockPurchases]

function toCents(value: number): bigint | null {
  if (!Number.isFinite(value) || value < 0) return null
  const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(value.toString())
  if (!match) return null
  const fraction = (match[2] ?? '').padEnd(2, '0')
  return BigInt(match[1]) * 100n + BigInt(fraction)
}

function purchaseRequestError(quantity: number, unitPrice: number): string | null {
  if (!Number.isInteger(quantity) || quantity > 2_147_483_647) {
    return '요청 본문이 올바르지 않습니다.'
  }
  if (quantity < 1) return 'quantity: 수량은 1 이상이어야 합니다.'
  if (unitPrice < 0) return 'unitPrice: 단가는 0 이상이어야 합니다.'
  const unitPriceCents = toCents(unitPrice)
  if (unitPriceCents == null || unitPriceCents > 999_999_999_999n) {
    return 'unitPrice: 단가는 정수 10자리·소수 2자리 이하여야 합니다.'
  }
  if (BigInt(quantity) * unitPriceCents > 999_999_999_999_999n) {
    return '매입 금액이 허용 범위를 벗어났습니다.'
  }
  return null
}

function purchaseTotal(quantity: number, unitPrice: number): number {
  return Number(BigInt(quantity) * toCents(unitPrice)!) / 100
}

function fail(status: number, error: string) {
  return HttpResponse.json<ApiResponse<null>>({ success: false, data: null, error }, { status })
}

/** tax-service가 매입 계산서 삭제·유형 변경 전에 확인하는 연결 건수(서버의 tax→purchase Feign 조회). */
export function countMockEvidenceLinks(taxInvoiceId: number): number {
  return purchases.filter((purchase) => purchase.evidenceTaxInvoiceId === taxInvoiceId).length
}

function cents(value: number): bigint {
  return BigInt(Math.round(value * 100))
}

function amount(value: bigint): number {
  return Number(value) / 100
}

/** 서버 PurchaseEvidenceService.summarize와 같은 규칙. 차이 = 계산서 금액 − 연결 매입 합계 */
function evidenceSummary(siteId: number): PurchaseEvidenceSummary {
  const sitePurchases = purchases.filter((purchase) => purchase.siteId === siteId)
  const invoices = listMockTaxInvoices()
    .filter((invoice) => invoice.siteId === siteId && invoice.type === 'PURCHASE')
  const linked = new Map<number, { count: number, cents: bigint }>(
    invoices.map((invoice) => [invoice.id, { count: 0, cents: 0n }]))
  let purchaseCents = 0n
  let linkedCents = 0n
  let linkedCount = 0
  let brokenCount = 0
  for (const purchase of sitePurchases) {
    purchaseCents += cents(purchase.totalAmount)
    if (purchase.evidenceTaxInvoiceId == null) continue
    const entry = linked.get(purchase.evidenceTaxInvoiceId)
    if (!entry) {
      brokenCount += 1
      continue
    }
    entry.count += 1
    entry.cents += cents(purchase.totalAmount)
    linkedCount += 1
    linkedCents += cents(purchase.totalAmount)
  }
  let supplyCents = 0n
  let totalCents = 0n
  const rows: PurchaseInvoiceEvidence[] = invoices.map((invoice) => {
    const entry = linked.get(invoice.id)!
    supplyCents += cents(invoice.supplyAmount)
    totalCents += cents(invoice.totalAmount)
    return {
      taxInvoiceId: invoice.id,
      counterparty: invoice.counterparty,
      issueDate: invoice.issueDate,
      supplyAmount: invoice.supplyAmount,
      totalAmount: invoice.totalAmount,
      linkedPurchaseCount: entry.count,
      linkedPurchaseAmount: amount(entry.cents),
      supplyDifference: amount(cents(invoice.supplyAmount) - entry.cents),
      totalDifference: amount(cents(invoice.totalAmount) - entry.cents),
    }
  })
  return {
    siteId,
    purchaseCount: sitePurchases.length,
    purchaseAmount: amount(purchaseCents),
    linkedPurchaseCount: linkedCount,
    linkedPurchaseAmount: amount(linkedCents),
    unlinkedPurchaseCount: sitePurchases.length - linkedCount,
    unlinkedPurchaseAmount: amount(purchaseCents - linkedCents),
    brokenLinkCount: brokenCount,
    invoiceCount: invoices.length,
    invoiceSupplyAmount: amount(supplyCents),
    invoiceTotalAmount: amount(totalCents),
    invoiceWithoutPurchaseCount: rows.filter((row) => row.linkedPurchaseCount === 0).length,
    supplyDifference: amount(supplyCents - linkedCents),
    totalDifference: amount(totalCents - linkedCents),
    invoices: rows,
  }
}

export const purchasesHandlers = [
  // '/:id'보다 먼저 등록해야 고정 경로가 ID로 해석되지 않는다.
  http.get<never, never, ApiResponse<PurchaseEvidenceSummary | null>>('/api/v1/purchases/evidence-summary', ({ request }) => {
    const siteId = Number(new URL(request.url).searchParams.get('siteId'))
    if (!Number.isInteger(siteId) || siteId <= 0) return fail(400, '유효한 현장을 찾을 수 없습니다.')
    return HttpResponse.json<ApiResponse<PurchaseEvidenceSummary>>({
      success: true, data: evidenceSummary(siteId), error: null,
    })
  }),

  http.get('/api/v1/purchases/evidence-links', ({ request }) => {
    const taxInvoiceId = Number(new URL(request.url).searchParams.get('taxInvoiceId'))
    return HttpResponse.json<ApiResponse<{ taxInvoiceId: number, linkedPurchaseCount: number }>>({
      success: true,
      data: { taxInvoiceId, linkedPurchaseCount: countMockEvidenceLinks(taxInvoiceId) },
      error: null,
    })
  }),

  http.put<{ id: string }, PurchaseEvidenceLinkRequest>('/api/v1/purchases/:id/evidence', async ({ params, request }) => {
    const id = Number(params.id)
    const current = purchases.find((purchase) => purchase.id === id)
    if (!current) return fail(404, '매입 내역을 찾을 수 없습니다.')
    const locked = archivedSiteLockError(current.siteId)
    if (locked) return locked
    const body = await request.json()
    if (!Number.isInteger(body?.taxInvoiceId) || body.taxInvoiceId <= 0) {
      return fail(400, 'taxInvoiceId: 증빙 세금계산서 ID가 올바르지 않습니다.')
    }
    const invoice = findMockTaxInvoice(body.taxInvoiceId)
    if (!invoice) return fail(400, '증빙으로 연결할 세금계산서를 찾을 수 없습니다.')
    if (invoice.type !== 'PURCHASE') return fail(400, '매입 세금계산서만 매입 증빙으로 연결할 수 있습니다.')
    if (invoice.siteId !== current.siteId) return fail(400, '같은 현장의 세금계산서만 증빙으로 연결할 수 있습니다.')
    const updated: Purchase = { ...current, evidenceTaxInvoiceId: invoice.id, updatedAt: new Date().toISOString() }
    purchases = purchases.map((purchase) => purchase.id === id ? updated : purchase)
    return HttpResponse.json<ApiResponse<Purchase>>({ success: true, data: updated, error: null })
  }),

  http.delete<{ id: string }>('/api/v1/purchases/:id/evidence', ({ params }) => {
    const id = Number(params.id)
    const current = purchases.find((purchase) => purchase.id === id)
    if (!current) return fail(404, '매입 내역을 찾을 수 없습니다.')
    const locked = archivedSiteLockError(current.siteId)
    if (locked) return locked
    const updated: Purchase = { ...current, evidenceTaxInvoiceId: null, updatedAt: new Date().toISOString() }
    purchases = purchases.map((purchase) => purchase.id === id ? updated : purchase)
    return HttpResponse.json<ApiResponse<Purchase>>({ success: true, data: updated, error: null })
  }),

  http.get('/api/v1/purchases', ({ request }) => {
    const url = new URL(request.url)
    const siteId = url.searchParams.get('siteId')

    let filtered = purchases
    if (siteId) filtered = filtered.filter((p) => p.siteId === Number(siteId))

    return HttpResponse.json<ApiResponse<Purchase[]>>({
      success: true,
      data: filtered,
      error: null,
    })
  }),

  http.get<{ id: string }>('/api/v1/purchases/:id', ({ params }) => {
    const purchase = purchases.find((item) => item.id === Number(params.id))
    if (!purchase) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '매입 내역을 찾을 수 없습니다.' },
        { status: 404 },
      )
    }
    return HttpResponse.json<ApiResponse<Purchase>>({ success: true, data: purchase, error: null })
  }),

  http.post<never, PurchaseCreateRequest>('/api/v1/purchases', async ({ request }) => {
    const body = await request.json()
    const archived = archivedSiteCreateError(body.siteId)
    if (archived) return archived
    const amountError = purchaseRequestError(body.quantity, body.unitPrice)
    if (amountError) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: amountError },
        { status: 400 },
      )
    }
    const newPurchase: Purchase = {
      id: Math.max(0, ...purchases.map((p) => p.id)) + 1,
      siteId: body.siteId,
      itemName: body.itemName.trim(),
      quantity: body.quantity,
      unitPrice: body.unitPrice,
      totalAmount: purchaseTotal(body.quantity, body.unitPrice),
      supplier: body.supplier?.trim() || null,
      purchaseDate: body.purchaseDate || null,
      memo: body.memo?.trim() || null,
      evidenceTaxInvoiceId: null,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
    purchases = [...purchases, newPurchase]
    return HttpResponse.json<ApiResponse<Purchase>>(
      { success: true, data: newPurchase, error: null },
      { status: 201 },
    )
  }),

  http.put<{ id: string }, PurchaseUpdateRequest>('/api/v1/purchases/:id', async ({ params, request }) => {
    const id = Number(params.id)
    const index = purchases.findIndex((purchase) => purchase.id === id)
    if (index < 0) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '매입 내역을 찾을 수 없습니다.' },
        { status: 404 },
      )
    }
    const locked = archivedSiteLockError(purchases[index].siteId)
    if (locked) return locked
    const body = await request.json()
    const amountError = purchaseRequestError(body.quantity, body.unitPrice)
    if (amountError) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: amountError },
        { status: 400 },
      )
    }
    const current = purchases[index]
    const updated: Purchase = {
      ...current,
      itemName: body.itemName.trim(),
      quantity: body.quantity,
      unitPrice: body.unitPrice,
      totalAmount: purchaseTotal(body.quantity, body.unitPrice),
      supplier: body.supplier?.trim() || null,
      purchaseDate: body.purchaseDate || null,
      memo: body.memo?.trim() || null,
      updatedAt: new Date().toISOString(),
    }
    purchases = purchases.map((purchase) => purchase.id === id ? updated : purchase)
    return HttpResponse.json<ApiResponse<Purchase>>({ success: true, data: updated, error: null })
  }),

  http.delete<{ id: string }>('/api/v1/purchases/:id', ({ params }) => {
    const id = Number(params.id)
    if (!purchases.some((purchase) => purchase.id === id)) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '매입 내역을 찾을 수 없습니다.' },
        { status: 404 },
      )
    }
    const locked = archivedSiteLockError(purchases.find((purchase) => purchase.id === id)?.siteId)
    if (locked) return locked
    purchases = purchases.filter((p) => p.id !== id)
    return HttpResponse.json<ApiResponse<null>>({ success: true, data: null, error: null })
  }),
]
