import { http, HttpResponse } from 'msw'
import type { ApiResponse, Client } from '../../types'
import { mockSites } from '../data/sites.data'

const clients = mockSites.flatMap((site) => site.client ? [site.client] : [])

export const clientsHandlers = [
  http.get('/api/v1/clients', () => HttpResponse.json<ApiResponse<Client[]>>({
    success: true,
    data: clients,
    error: null,
  })),
]
