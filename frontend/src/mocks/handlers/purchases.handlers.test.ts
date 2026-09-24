import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { setupServer } from 'msw/node'
import type { ApiResponse, Purchase } from '../../types'
import { purchasesHandlers } from './purchases.handlers'

const server = setupServer(...purchasesHandlers)
const url = new URL('/api/v1/purchases', document.baseURI)
const headers = { 'Content-Type': 'application/json' }

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

describe('매입 MSW 수정·삭제 계약', () => {
  it('PUT은 현장을 유지하고 총액·nullable 필드를 다시 계산한다', async () => {
    const createdResponse = await fetch(url, {
      method: 'POST', headers,
      body: JSON.stringify({ siteId: 3, itemName: '수정 전', quantity: 1, unitPrice: 1000 }),
    })
    const created = (await createdResponse.json() as ApiResponse<Purchase>).data
    const itemUrl = new URL(`/api/v1/purchases/${created.id}`, document.baseURI)

    const response = await fetch(itemUrl, {
      method: 'PUT', headers,
      body: JSON.stringify({
        siteId: 999, itemName: ' 수정 후 ', quantity: 2, unitPrice: 3000,
        supplier: '', purchaseDate: null, memo: '',
      }),
    })

    expect(response.status).toBe(200)
    const updated = (await response.json() as ApiResponse<Purchase>).data
    expect(updated).toMatchObject({
      id: created.id, siteId: 3, itemName: '수정 후', quantity: 2,
      unitPrice: 3000, totalAmount: 6000,
      supplier: null, purchaseDate: null, memo: null,
    })
    expect((await fetch(itemUrl).then((result) => result.json()) as ApiResponse<Purchase>).data)
      .toMatchObject(updated)
  })

  it('POST와 PUT은 잘못된 금액을 400으로 거절한다', async () => {
    const negative = await fetch(url, {
      method: 'POST', headers,
      body: JSON.stringify({ siteId: 3, itemName: '음수', quantity: 1, unitPrice: -1 }),
    })
    expect(negative.status).toBe(400)
    expect((await negative.json() as ApiResponse<null>).error)
      .toBe('unitPrice: 단가는 0 이상이어야 합니다.')

    const createdResponse = await fetch(url, {
      method: 'POST', headers,
      body: JSON.stringify({ siteId: 3, itemName: '경계', quantity: 1, unitPrice: 1000 }),
    })
    const created = (await createdResponse.json() as ApiResponse<Purchase>).data
    const itemUrl = new URL(`/api/v1/purchases/${created.id}`, document.baseURI)
    const fractionalQuantity = await fetch(itemUrl, {
      method: 'PUT', headers,
      body: JSON.stringify({ itemName: '소수 수량', quantity: 1.5, unitPrice: 1000 }),
    })
    expect(fractionalQuantity.status).toBe(400)
    expect((await fractionalQuantity.json() as ApiResponse<null>).error)
      .toBe('요청 본문이 올바르지 않습니다.')
    const overflow = await fetch(itemUrl, {
      method: 'PUT', headers,
      body: JSON.stringify({ itemName: '초과', quantity: 1001, unitPrice: 9_999_999_999.99 }),
    })
    expect(overflow.status).toBe(400)
    expect((await overflow.json() as ApiResponse<null>).error)
      .toBe('매입 금액이 허용 범위를 벗어났습니다.')
  })

  it('큰 정상 2자리 단가를 부동소수 오차 없이 허용한다', async () => {
    const createdResponse = await fetch(url, {
      method: 'POST', headers,
      body: JSON.stringify({ siteId: 3, itemName: '큰 단가', quantity: 1, unitPrice: 9_999_999_999.97 }),
    })
    expect(createdResponse.status).toBe(201)
    const created = (await createdResponse.json() as ApiResponse<Purchase>).data
    const itemUrl = new URL(`/api/v1/purchases/${created.id}`, document.baseURI)

    const updatedResponse = await fetch(itemUrl, {
      method: 'PUT', headers,
      body: JSON.stringify({ itemName: '수정 큰 단가', quantity: 1, unitPrice: 9_999_999_999.12 }),
    })
    expect(updatedResponse.status).toBe(200)
    expect((await updatedResponse.json() as ApiResponse<Purchase>).data.unitPrice)
      .toBe(9_999_999_999.12)
  })

  it('소수 단가 총액을 실서버 BigDecimal과 같은 값으로 계산한다', async () => {
    const response = await fetch(url, {
      method: 'POST', headers,
      body: JSON.stringify({ siteId: 3, itemName: '소수 단가', quantity: 3, unitPrice: 0.1 }),
    })
    expect(response.status).toBe(201)
    expect((await response.json() as ApiResponse<Purchase>).data.totalAmount).toBe(0.3)
  })

  it('DELETE 후 조회와 반복 PUT·DELETE는 404를 반환한다', async () => {
    const createdResponse = await fetch(url, {
      method: 'POST', headers,
      body: JSON.stringify({ siteId: 3, itemName: '삭제 대상', quantity: 1, unitPrice: 1000 }),
    })
    const created = (await createdResponse.json() as ApiResponse<Purchase>).data
    const itemUrl = new URL(`/api/v1/purchases/${created.id}`, document.baseURI)

    expect((await fetch(itemUrl, { method: 'DELETE' })).status).toBe(200)
    expect((await fetch(itemUrl)).status).toBe(404)
    expect((await fetch(itemUrl, {
      method: 'PUT', headers,
      body: JSON.stringify({ itemName: '없음', quantity: 1, unitPrice: 1000 }),
    })).status).toBe(404)
    expect((await fetch(itemUrl, { method: 'DELETE' })).status).toBe(404)
  })
})
