import { http, HttpResponse } from 'msw'
import type { ApiResponse, Client, ClientCreateRequest } from '../../types'
import { mockSites } from '../data/sites.data'

let clients = mockSites.flatMap((site) => site.client ? [site.client] : [])

export function findMockClientById(id?: number): Client | null {
  return clients.find((client) => client.id === id) ?? null
}

export const clientsHandlers = [
  http.get('/api/v1/clients', () => HttpResponse.json<ApiResponse<Client[]>>({
    success: true,
    data: clients,
    error: null,
  })),
  http.post<never, ClientCreateRequest>('/api/v1/clients', async ({ request }) => {
    const body = await request.json()
    if (!body.companyName?.trim()) {
      return HttpResponse.json(
        { success: false, error: '업체명은 필수입니다.' },
        { status: 400 },
      )
    }
    const now = new Date().toISOString()
    const client: Client = {
      id: Math.max(0, ...clients.map((item) => item.id)) + 1,
      companyName: body.companyName,
      representative: body.representative ?? null,
      businessNo: body.businessNo ?? null,
      phone: body.phone ?? null,
      email: body.email ?? null,
      address: body.address ?? null,
      memo: body.memo ?? null,
      createdAt: now,
      updatedAt: now,
    }
    clients = [...clients, client]
    return HttpResponse.json<ApiResponse<Client>>(
      { success: true, data: client, error: null },
      { status: 201 },
    )
  }),
]
