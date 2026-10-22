import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { setupServer } from 'msw/node'
import type { ApiResponse, Purchase, PurchaseEvidenceSummary, TaxInvoice } from '../../types'
import { purchasesHandlers } from './purchases.handlers'
import { taxesHandlers } from './taxes.handlers'

const server = setupServer(...purchasesHandlers, ...taxesHandlers)
const headers = { 'Content-Type': 'application/json' }
const api = (path: string) => new URL(`/api/v1${path}`, document.baseURI)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

async function summary(siteId: number) {
  const response = await fetch(api(`/purchases/evidence-summary?siteId=${siteId}`))
  expect(response.status).toBe(200)
  return (await response.json() as ApiResponse<PurchaseEvidenceSummary>).data
}

function link(purchaseId: number, taxInvoiceId: number) {
  return fetch(api(`/purchases/${purchaseId}/evidence`), {
    method: 'PUT', headers, body: JSON.stringify({ taxInvoiceId }),
  })
}

describe('매입 증빙 MSW 계약', () => {
  it('대조 요약은 미연결 금액과 계산서−연결 매입 차이를 서버 규칙대로 계산한다', async () => {
    const data = await summary(1)
    expect(data).toMatchObject({
      siteId: 1,
      purchaseCount: 2,
      purchaseAmount: 9_700_000,
      linkedPurchaseCount: 1,
      linkedPurchaseAmount: 8_500_000,
      unlinkedPurchaseCount: 1,
      unlinkedPurchaseAmount: 1_200_000,
      brokenLinkCount: 0,
      invoiceCount: 1,
      invoiceSupplyAmount: 8_500_000,
      invoiceTotalAmount: 9_350_000,
      invoiceWithoutPurchaseCount: 0,
      supplyDifference: 0,
      totalDifference: 850_000,
    })
    expect(data.invoices[0]).toMatchObject({ taxInvoiceId: 4, linkedPurchaseCount: 1, supplyDifference: 0 })
  })

  it('매출 계산서·다른 현장 계산서·없는 계산서 연결은 400', async () => {
    const sales = await link(2, 1)
    expect(sales.status).toBe(400)
    expect((await sales.json() as ApiResponse<null>).error).toBe('매입 세금계산서만 매입 증빙으로 연결할 수 있습니다.')

    const created = await fetch(api('/taxes'), {
      method: 'POST', headers,
      body: JSON.stringify({ siteId: 2, type: 'PURCHASE', supplyAmount: 1000, taxAmount: 100 }),
    })
    const otherSite = (await created.json() as ApiResponse<TaxInvoice>).data
    const mismatch = await link(2, otherSite.id)
    expect(mismatch.status).toBe(400)
    expect((await mismatch.json() as ApiResponse<null>).error).toBe('같은 현장의 세금계산서만 증빙으로 연결할 수 있습니다.')

    expect((await link(2, 9_999)).status).toBe(400)
    expect((await summary(1)).linkedPurchaseCount).toBe(1)
  })

  it('연결된 매입 계산서는 삭제·매출 전환이 409이고 연결 해제 후 삭제된다', async () => {
    const linked = await link(2, 4)
    expect(linked.status).toBe(200)
    expect((await linked.json() as ApiResponse<Purchase>).data.evidenceTaxInvoiceId).toBe(4)
    expect((await summary(1)).invoices[0]).toMatchObject({ linkedPurchaseCount: 2, supplyDifference: -1_200_000 })

    const blockedDelete = await fetch(api('/taxes/4'), { method: 'DELETE' })
    expect(blockedDelete.status).toBe(409)
    const blockedType = await fetch(api('/taxes/4'), {
      method: 'PUT', headers,
      body: JSON.stringify({ type: 'SALES', supplyAmount: 8_500_000, taxAmount: 850_000 }),
    })
    expect(blockedType.status).toBe(409)

    for (const purchaseId of [1, 2]) {
      const response = await fetch(api(`/purchases/${purchaseId}/evidence`), { method: 'DELETE' })
      expect(response.status).toBe(200)
      expect((await response.json() as ApiResponse<Purchase>).data.evidenceTaxInvoiceId).toBeNull()
    }
    const links = await fetch(api('/purchases/evidence-links?taxInvoiceId=4'))
    expect((await links.json() as ApiResponse<{ linkedPurchaseCount: number }>).data.linkedPurchaseCount).toBe(0)
    expect((await fetch(api('/taxes/4'), { method: 'DELETE' })).status).toBe(200)
    expect(await summary(1)).toMatchObject({ unlinkedPurchaseAmount: 9_700_000, invoiceCount: 0 })
  })
})
