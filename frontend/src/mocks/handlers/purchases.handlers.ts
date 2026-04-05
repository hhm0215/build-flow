import { http, HttpResponse } from 'msw'
import { mockPurchases } from '../data/purchases.data'
import { ApiResponse, PagedData, Purchase } from '../../types'

let purchases = [...mockPurchases]

export const purchasesHandlers = [
  http.get('/api/v1/purchases', ({ request }) => {
    const url = new URL(request.url)
    const siteId = url.searchParams.get('siteId')
    const category = url.searchParams.get('category')

    let filtered = purchases
    if (siteId) filtered = filtered.filter((p) => p.siteId === Number(siteId))
    if (category) filtered = filtered.filter((p) => p.category === category)

    return HttpResponse.json<ApiResponse<PagedData<Purchase>>>({
      success: true,
      data: { content: filtered, totalElements: filtered.length, totalPages: 1, number: 0, size: 20 },
      error: null,
    })
  }),

  http.post<never, Partial<Purchase>>('/api/v1/purchases', async ({ request }) => {
    const body = await request.json()
    const newPurchase: Purchase = {
      id: Math.max(...purchases.map((p) => p.id)) + 1,
      siteId: body.siteId!,
      siteName: body.siteName ?? '',
      category: body.category ?? 'OTHER',
      description: body.description ?? '',
      vendor: body.vendor ?? '',
      amount: body.amount ?? 0,
      purchaseDate: body.purchaseDate ?? new Date().toISOString().split('T')[0],
      createdAt: new Date().toISOString(),
    }
    purchases = [...purchases, newPurchase]
    return HttpResponse.json<ApiResponse<Purchase>>(
      { success: true, data: newPurchase, error: null },
      { status: 201 },
    )
  }),
]
