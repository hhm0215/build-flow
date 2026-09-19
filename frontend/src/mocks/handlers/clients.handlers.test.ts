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
})
