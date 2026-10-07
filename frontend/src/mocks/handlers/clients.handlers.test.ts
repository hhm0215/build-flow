import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { setupServer } from 'msw/node'
import type { ApiResponse, Client, Site } from '../../types'
import { clientsHandlers } from './clients.handlers'
import { sitesHandlers } from './sites.handlers'

const server = setupServer(...clientsHandlers, ...sitesHandlers)
const clientsUrl = new URL('/api/v1/clients', document.baseURI)
const sitesUrl = new URL('/api/v1/sites', document.baseURI)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

describe('거래처·현장 MSW 계약', () => {
  it('거래처 수정은 연결된 현장 조회에도 반영하고 존재하지 않는 ID를 거부한다', async () => {
    const created = await fetch(clientsUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ companyName: '수정 전 거래처' }),
    })
    const client = (await created.json() as ApiResponse<Client>).data
    const siteCreated = await fetch(sitesUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ siteName: '연결 현장', clientId: client.id }),
    })
    const site = (await siteCreated.json() as ApiResponse<Site>).data
    const updateBody = {
      companyName: '수정 후 거래처', representative: null, businessNo: null,
      phone: null, email: null, address: null, memo: null,
    }
    const updated = await fetch(new URL(`/api/v1/clients/${client.id}`, document.baseURI), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(updateBody),
    })
    expect(updated.status).toBe(200)
    expect((await updated.json() as ApiResponse<Client>).data.companyName).toBe('수정 후 거래처')

    const siteRead = await fetch(new URL(`/api/v1/sites/${site.id}`, document.baseURI))
    expect((await siteRead.json() as ApiResponse<Site>).data.client?.companyName).toBe('수정 후 거래처')

    const missing = await fetch(new URL('/api/v1/clients/999999', document.baseURI), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(updateBody),
    })
    expect(missing.status).toBe(404)
  })

  it('회사명 필수 검증 후 생성한 거래처를 목록·현장 생성에서 사용한다', async () => {
    const invalid = await fetch(clientsUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ companyName: '   ' }),
    })
    expect(invalid.status).toBe(400)

    const created = await fetch(clientsUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ companyName: '신규 업체' }),
    })
    expect(created.status).toBe(201)
    const client = (await created.json() as ApiResponse<Client>).data
    expect(client.companyName).toBe('신규 업체')
    expect(client.representative).toBeNull()

    const listed = await fetch(clientsUrl)
    const clients = (await listed.json() as ApiResponse<Client[]>).data
    expect(clients).toContainEqual(client)

    const siteResponse = await fetch(sitesUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ siteName: '새 현장', clientId: client.id }),
    })
    expect(siteResponse.status).toBe(201)
    const site = (await siteResponse.json() as ApiResponse<Site>).data
    expect(site.client?.id).toBe(client.id)

    const unknownClient = await fetch(sitesUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ siteName: '잘못된 현장', clientId: 999999 }),
    })
    expect(unknownClient.status).toBe(404)
  })

  it('현장 PUT은 거래처 연결을 해제하고 비운 필드를 null로 교체한다', async () => {
    const created = await fetch(sitesUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ siteName: '수정 전 현장', address: '서울' }),
    })
    const site = (await created.json() as ApiResponse<Site>).data
    const siteUrl = new URL(`/api/v1/sites/${site.id}`, document.baseURI)

    const updated = await fetch(siteUrl, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        siteName: '수정 후 현장', clientId: null, address: null,
        startDate: null, endDate: null, memo: null,
      }),
    })
    expect(updated.status).toBe(200)
    const result = (await updated.json() as ApiResponse<Site>).data
    expect(result).toMatchObject({
      siteName: '수정 후 현장', client: null, address: null,
      startDate: null, endDate: null, memo: null,
    })
    const fetched = await fetch(siteUrl)
    expect((await fetched.json() as ApiResponse<Site>).data).toMatchObject(result)

    const unknownClient = await fetch(siteUrl, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ siteName: '무효', clientId: 999999 }),
    })
    expect(unknownClient.status).toBe(404)
  })
})
