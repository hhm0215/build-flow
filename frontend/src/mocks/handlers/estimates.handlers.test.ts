import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { setupServer } from 'msw/node'
import type { ApiResponse, Estimate } from '../../types'
import { estimatesHandlers } from './estimates.handlers'

const server = setupServer(...estimatesHandlers)
const url = new URL('/api/v1/estimates', document.baseURI)
const headers = { 'Content-Type': 'application/json' }

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

describe('견적 MSW 수정·삭제 계약', () => {
  it('초안 PUT 후 GET에 항목·총액이 반영되고 확정 후 PUT은 거절한다', async () => {
    const created = await fetch(url, {
      method: 'POST', headers,
      body: JSON.stringify({
        siteId: 3, title: '수정 전', estimateDate: '2026-09-19',
        items: [{ itemName: '자재', unit: 'EA', quantity: 1, unitPrice: 1000, amount: 1000 }],
      }),
    })
    const estimate = (await created.json() as ApiResponse<Estimate>).data
    const itemUrl = new URL(`/api/v1/estimates/${estimate.id}`, document.baseURI)
    const updated = await fetch(itemUrl, {
      method: 'PUT', headers,
      body: JSON.stringify({
        title: '수정 후', estimateDate: '2026-09-20', memo: '메모',
        items: [{ itemName: '추가 자재', unit: 'EA', quantity: 2, unitPrice: 3000 }],
      }),
    })
    expect(updated.status).toBe(200)
    const result = (await updated.json() as ApiResponse<Estimate>).data
    expect(result).toMatchObject({ title: '수정 후', totalAmount: 6000, siteId: 3 })
    expect(result.items[0]).toMatchObject({ itemName: '추가 자재', amount: 6000 })
    const fetched = await fetch(itemUrl)
    expect((await fetched.json() as ApiResponse<Estimate>).data).toMatchObject(result)

    await fetch(new URL(`/api/v1/estimates/${estimate.id}/confirm`, document.baseURI), { method: 'PATCH' })
    const blocked = await fetch(itemUrl, {
      method: 'PUT', headers,
      body: JSON.stringify({ title: '확정 후 수정', estimateDate: '2026-09-21', items: result.items }),
    })
    expect(blocked.status).toBe(409)
  })

  it('초안 DELETE 후 조회는 404를 반환한다', async () => {
    const created = await fetch(url, {
      method: 'POST', headers,
      body: JSON.stringify({
        siteId: 3, title: '삭제할 초안', estimateDate: '2026-09-19',
        items: [{ itemName: '자재', unit: 'EA', quantity: 1, unitPrice: 1000, amount: 1000 }],
      }),
    })
    const estimate = (await created.json() as ApiResponse<Estimate>).data
    const itemUrl = new URL(`/api/v1/estimates/${estimate.id}`, document.baseURI)
    expect((await fetch(itemUrl, { method: 'DELETE' })).status).toBe(200)
    expect((await fetch(itemUrl)).status).toBe(404)
    expect((await fetch(itemUrl, { method: 'DELETE' })).status).toBe(404)
  })

  it('확정 견적 DELETE는 409이고 원본은 유지한다', async () => {
    const created = await fetch(url, {
      method: 'POST', headers,
      body: JSON.stringify({
        siteId: 3, title: '삭제 금지 견적', estimateDate: '2026-09-19',
        items: [{ itemName: '자재', unit: 'EA', quantity: 1, unitPrice: 1000, amount: 1000 }],
      }),
    })
    const estimate = (await created.json() as ApiResponse<Estimate>).data
    const itemUrl = new URL(`/api/v1/estimates/${estimate.id}`, document.baseURI)
    await fetch(new URL(`/api/v1/estimates/${estimate.id}/confirm`, document.baseURI), { method: 'PATCH' })

    const blocked = await fetch(itemUrl, { method: 'DELETE' })
    expect(blocked.status).toBe(409)
    expect((await blocked.json() as ApiResponse<null>).error).toBe('확정된 견적서는 삭제할 수 없습니다.')
    expect((await fetch(itemUrl)).status).toBe(200)
  })

  it('DB 정밀도에서 항목 금액이 달라지는 요청은 400으로 거절한다', async () => {
    const invalid = await fetch(url, {
      method: 'POST', headers,
      body: JSON.stringify({
        siteId: 3, title: '잘못된 소수', estimateDate: '2026-09-19',
        items: [{ itemName: '자재', unit: 'EA', quantity: 0.004, unitPrice: 1000, amount: 4 }],
      }),
    })
    expect(invalid.status).toBe(400)
  })
})
