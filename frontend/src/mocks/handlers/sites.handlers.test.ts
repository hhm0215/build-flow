import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { setupServer } from 'msw/node'
import type { ApiResponse, Site } from '../../types'
import { clientsHandlers } from './clients.handlers'
import { sitesHandlers } from './sites.handlers'
import { estimatesHandlers } from './estimates.handlers'
import { taxesHandlers } from './taxes.handlers'

const server = setupServer(...clientsHandlers, ...sitesHandlers, ...estimatesHandlers, ...taxesHandlers)
const url = (path: string) => new URL(path, document.baseURI)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

async function createSite(siteName: string): Promise<Site> {
  const res = await fetch(url('/api/v1/sites'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ siteName }),
  })
  return (await res.json() as ApiResponse<Site>).data
}

describe('현장 보관 MSW 계약', () => {
  it('보관하면 archivedAt이 생기고 재보관해도 최초 시각을 유지하며 복원하면 null이다', async () => {
    const site = await createSite('[QA] 보관 대상')
    expect(site.archivedAt).toBeNull()

    const archived = (await (await fetch(url(`/api/v1/sites/${site.id}/archive`), { method: 'PATCH' })).json() as ApiResponse<Site>).data
    expect(archived.archivedAt).not.toBeNull()
    const again = (await (await fetch(url(`/api/v1/sites/${site.id}/archive`), { method: 'PATCH' })).json() as ApiResponse<Site>).data
    expect(again.archivedAt).toBe(archived.archivedAt)

    const restored = (await (await fetch(url(`/api/v1/sites/${site.id}/restore`), { method: 'PATCH' })).json() as ApiResponse<Site>).data
    expect(restored.archivedAt).toBeNull()
  })

  it('보관된 현장의 수정·상태 변경은 409이고 archived 필터로 구분된다', async () => {
    const site = await createSite('[QA] 보관 필터')
    await fetch(url(`/api/v1/sites/${site.id}/archive`), { method: 'PATCH' })

    const put = await fetch(url(`/api/v1/sites/${site.id}`), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ siteName: '수정 시도' }),
    })
    expect(put.status).toBe(409)
    const patch = await fetch(url(`/api/v1/sites/${site.id}/status`), {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status: 'COMPLETED' }),
    })
    expect(patch.status).toBe(409)

    const ids = async (query: string) =>
      ((await (await fetch(url(`/api/v1/sites${query}`))).json() as ApiResponse<Site[]>).data).map((s) => s.id)
    expect(await ids('?archived=true')).toContain(site.id)
    expect(await ids('?archived=false')).not.toContain(site.id)
    expect(await ids('')).toContain(site.id)
  })

  it('없는 현장의 보관·복원은 404다', async () => {
    expect((await fetch(url('/api/v1/sites/999999/archive'), { method: 'PATCH' })).status).toBe(404)
    expect((await fetch(url('/api/v1/sites/999999/restore'), { method: 'PATCH' })).status).toBe(404)
  })

  it('보관 현장은 신규 등록 400, 기존 자료 수정·삭제 409이고 입금 확인은 허용한다', async () => {
    const site = await createSite('[QA] 문서 잠금')
    const json = { 'Content-Type': 'application/json' }
    const taxRes = await fetch(url('/api/v1/taxes'), {
      method: 'POST', headers: json,
      body: JSON.stringify({ siteId: site.id, type: 'SALES', supplyAmount: 1000, taxAmount: 100 }),
    })
    const tax = (await taxRes.json() as ApiResponse<{ id: number }>).data
    const estRes = await fetch(url('/api/v1/estimates'), {
      method: 'POST', headers: json,
      body: JSON.stringify({ siteId: site.id, title: '[QA] 견적', estimateDate: '2026-10-17', items: [{ itemName: 'x', unit: '식', quantity: 1, unitPrice: 1000 }] }),
    })
    const estimate = (await estRes.json() as ApiResponse<{ id: number }>).data
    await fetch(url(`/api/v1/sites/${site.id}/archive`), { method: 'PATCH' })

    const newEstimate = await fetch(url('/api/v1/estimates'), {
      method: 'POST', headers: json,
      body: JSON.stringify({ siteId: site.id, title: '[QA] 신규', estimateDate: '2026-10-17', items: [{ itemName: 'x', unit: '식', quantity: 1, unitPrice: 1000 }] }),
    })
    expect(newEstimate.status).toBe(400)
    expect((await fetch(url(`/api/v1/estimates/${estimate.id}`), { method: 'DELETE' })).status).toBe(409)
    expect((await fetch(url(`/api/v1/estimates/${estimate.id}/confirm`), { method: 'PATCH' })).status).toBe(409)
    expect((await fetch(url(`/api/v1/taxes/${tax.id}`), { method: 'DELETE' })).status).toBe(409)
    const payment = await fetch(url(`/api/v1/taxes/${tax.id}/confirm-payment`), {
      method: 'PATCH', headers: json, body: JSON.stringify({ paymentDate: '2026-10-17' }),
    })
    expect(payment.status).toBe(200)
  })
})
