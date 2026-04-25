import { http, HttpResponse } from 'msw'
import { mockPurchases } from '../data/purchases.data'
import { ApiResponse, Purchase, PurchaseCreateRequest } from '../../types'

let purchases = [...mockPurchases]

export const purchasesHandlers = [
  http.get('/api/v1/purchases', ({ request }) => {
    const url = new URL(request.url)
    const siteId = url.searchParams.get('siteId')

    let filtered = purchases
    if (siteId) filtered = filtered.filter((p) => p.siteId === Number(siteId))

    return HttpResponse.json<ApiResponse<Purchase[]>>({
      success: true,
      data: filtered,
      error: null,
    })
  }),

  http.post<never, PurchaseCreateRequest>('/api/v1/purchases', async ({ request }) => {
    const body = await request.json()
    const newPurchase: Purchase = {
      id: Math.max(...purchases.map((p) => p.id)) + 1,
      siteId: body.siteId,
      itemName: body.itemName,
      quantity: body.quantity,
      unitPrice: body.unitPrice,
      totalAmount: body.quantity * body.unitPrice,
      supplier: body.supplier || '',
      purchaseDate: body.purchaseDate || new Date().toISOString().split('T')[0],
      memo: body.memo || '',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
    purchases = [...purchases, newPurchase]
    return HttpResponse.json<ApiResponse<Purchase>>(
      { success: true, data: newPurchase, error: null },
      { status: 201 },
    )
  }),

  http.delete<{ id: string }>('/api/v1/purchases/:id', ({ params }) => {
    purchases = purchases.filter((p) => p.id !== Number(params.id))
    return HttpResponse.json<ApiResponse<null>>({ success: true, data: null, error: null })
  }),
]
