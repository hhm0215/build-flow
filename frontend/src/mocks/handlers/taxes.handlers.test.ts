import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { setupServer } from 'msw/node'
import type { ApiResponse, TaxInvoice } from '../../types'
import { taxesHandlers } from './taxes.handlers'

const server = setupServer(...taxesHandlers)
const headers = { 'Content-Type': 'application/json' }
const url = new URL('/api/v1/taxes/2/confirm-payment', document.baseURI)
const collectionUrl = new URL('/api/v1/taxes', document.baseURI)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

describe('세금계산서 입금 확인 MSW 계약', () => {
  it('본문 누락 실패, 날짜 저장, 중복 409와 없는 ID 404를 반영한다', async () => {
    const missingBody = await fetch(url, { method: 'PATCH' })
    expect(missingBody.status).toBe(400)

    const invalidDate = await fetch(url, {
      method: 'PATCH', headers,
      body: JSON.stringify({ paymentDate: '2026-02-31' }),
    })
    expect(invalidDate.status).toBe(400)

    const confirmed = await fetch(url, {
      method: 'PATCH', headers,
      body: JSON.stringify({ paymentDate: '2026-09-20' }),
    })
    expect(confirmed.status).toBe(200)
    const result = (await confirmed.json() as ApiResponse<TaxInvoice>).data
    expect(result).toMatchObject({ id: 2, paymentConfirmed: true, paymentDate: '2026-09-20' })

    const duplicate = await fetch(url, {
      method: 'PATCH', headers,
      body: JSON.stringify({ paymentDate: '2026-09-21' }),
    })
    expect(duplicate.status).toBe(409)

    const missing = await fetch(new URL('/api/v1/taxes/999999/confirm-payment', document.baseURI), {
      method: 'PATCH', headers,
      body: JSON.stringify({ paymentDate: '2026-09-20' }),
    })
    expect(missing.status).toBe(404)

    const purchaseUrl = new URL('/api/v1/taxes/4', document.baseURI)
    const purchaseBefore = (await fetch(purchaseUrl).then((response) => response.json()) as ApiResponse<TaxInvoice>).data
    const purchase = await fetch(new URL('/api/v1/taxes/4/confirm-payment', document.baseURI), {
      method: 'PATCH', headers,
      body: JSON.stringify({ paymentDate: '2026-09-20' }),
    })
    expect(purchase.status).toBe(409)
    expect((await purchase.json() as ApiResponse<null>).error)
      .toBe('매입 세금계산서는 입금 확인할 수 없습니다.')
    const purchaseAfter = (await fetch(purchaseUrl).then((response) => response.json()) as ApiResponse<TaxInvoice>).data
    expect(purchaseAfter).toEqual(purchaseBefore)
    expect(purchaseAfter).toMatchObject({ paymentConfirmed: false, paymentDate: null })
  })
})

describe('세금계산서 수정·삭제 MSW 계약', () => {
  it('정적 outstanding 경로와 소수 금액 합계를 정확히 처리한다', async () => {
    for (const supplyAmount of [0.1, 0.2]) {
      const response = await fetch(collectionUrl, {
        method: 'POST', headers,
        body: JSON.stringify({ siteId: 77, type: 'SALES', supplyAmount, taxAmount: 0 }),
      })
      expect(response.status).toBe(201)
    }

    const response = await fetch(new URL('/api/v1/taxes/outstanding?siteId=77', document.baseURI))
    expect(response.status).toBe(200)
    expect((await response.json() as ApiResponse<{ outstandingAmount: number }>).data.outstandingAmount)
      .toBe(0.3)
  })

  it('PUT은 현장을 유지하고 합계·nullable 필드를 다시 계산한다', async () => {
    const createdResponse = await fetch(collectionUrl, {
      method: 'POST', headers,
      body: JSON.stringify({
        siteId: 3, type: 'SALES', supplyAmount: 9_999_999_999_999.12,
        taxAmount: 0.87, counterparty: '생성 거래처', issueDate: '2026-09-24',
      }),
    })
    expect(createdResponse.status).toBe(201)
    const created = (await createdResponse.json() as ApiResponse<TaxInvoice>).data
    const itemUrl = new URL(`/api/v1/taxes/${created.id}`, document.baseURI)

    const response = await fetch(itemUrl, {
      method: 'PUT', headers,
      body: JSON.stringify({
        siteId: 999, type: 'PURCHASE', supplyAmount: 0.12, taxAmount: 0.97,
        counterparty: '', issueDate: null, memo: '',
      }),
    })

    expect(response.status).toBe(200)
    const updated = (await response.json() as ApiResponse<TaxInvoice>).data
    expect(updated).toMatchObject({
      id: created.id, siteId: 3, type: 'PURCHASE', supplyAmount: 0.12,
      taxAmount: 0.97, totalAmount: 1.09,
      counterparty: null, issueDate: null, memo: null,
    })
    expect((await fetch(itemUrl).then((result) => result.json()) as ApiResponse<TaxInvoice>).data)
      .toMatchObject(updated)
  })

  it('잘못된 금액을 400으로 거절하고 기존 값을 보존한다', async () => {
    const negative = await fetch(collectionUrl, {
      method: 'POST', headers,
      body: JSON.stringify({ siteId: 3, type: 'SALES', supplyAmount: -1, taxAmount: 0 }),
    })
    expect(negative.status).toBe(400)

    const createdResponse = await fetch(collectionUrl, {
      method: 'POST', headers,
      body: JSON.stringify({ siteId: 3, type: 'SALES', supplyAmount: 100, taxAmount: 10 }),
    })
    const created = (await createdResponse.json() as ApiResponse<TaxInvoice>).data
    const itemUrl = new URL(`/api/v1/taxes/${created.id}`, document.baseURI)
    const overflow = await fetch(itemUrl, {
      method: 'PUT', headers,
      body: JSON.stringify({
        type: 'SALES', supplyAmount: 9_999_999_999_999.99, taxAmount: 0.01,
      }),
    })
    expect(overflow.status).toBe(400)
    expect((await fetch(itemUrl).then((result) => result.json()) as ApiResponse<TaxInvoice>).data)
      .toMatchObject({ supplyAmount: 100, taxAmount: 10, totalAmount: 110 })
  })

  it('확정 건은 PUT·DELETE 409로 보존하고 미확정 건 삭제 후 404를 반환한다', async () => {
    const confirmedUrl = new URL('/api/v1/taxes/1', document.baseURI)
    const confirmedPut = await fetch(confirmedUrl, {
      method: 'PUT', headers,
      body: JSON.stringify({ type: 'SALES', supplyAmount: 1, taxAmount: 0 }),
    })
    expect(confirmedPut.status).toBe(409)
    expect((await fetch(confirmedUrl, { method: 'DELETE' })).status).toBe(409)

    const createdResponse = await fetch(collectionUrl, {
      method: 'POST', headers,
      body: JSON.stringify({ siteId: 3, type: 'PURCHASE', supplyAmount: 100, taxAmount: 10 }),
    })
    const created = (await createdResponse.json() as ApiResponse<TaxInvoice>).data
    const itemUrl = new URL(`/api/v1/taxes/${created.id}`, document.baseURI)
    expect((await fetch(itemUrl, { method: 'DELETE' })).status).toBe(200)
    expect((await fetch(itemUrl)).status).toBe(404)
    expect((await fetch(itemUrl, { method: 'DELETE' })).status).toBe(404)
  })
})
