import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { setupServer } from 'msw/node'
import type { ApiResponse, TaxInvoice } from '../../types'
import { taxesHandlers } from './taxes.handlers'

const server = setupServer(...taxesHandlers)
const headers = { 'Content-Type': 'application/json' }
const url = new URL('/api/v1/taxes/2/confirm-payment', document.baseURI)

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
  })
})
