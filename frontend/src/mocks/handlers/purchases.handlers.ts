import { http, HttpResponse } from 'msw'
import { mockPurchases } from '../data/purchases.data'
import { ApiResponse, Purchase, PurchaseCreateRequest, PurchaseUpdateRequest } from '../../types'

let purchases = [...mockPurchases]

function purchaseRequestError(quantity: number, unitPrice: number): string | null {
  if (!Number.isInteger(quantity) || quantity > 2_147_483_647) {
    return '요청 본문이 올바르지 않습니다.'
  }
  if (quantity < 1) return 'quantity: 수량은 1 이상이어야 합니다.'
  if (unitPrice < 0) return 'unitPrice: 단가는 0 이상이어야 합니다.'
  if (unitPrice > 9_999_999_999.99
      || Math.abs(unitPrice * 100 - Math.round(unitPrice * 100)) >= 1e-7) {
    return 'unitPrice: 단가는 정수 10자리·소수 2자리 이하여야 합니다.'
  }
  if (quantity * unitPrice > 9_999_999_999_999.99) {
    return '매입 금액이 허용 범위를 벗어났습니다.'
  }
  return null
}

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

  http.get<{ id: string }>('/api/v1/purchases/:id', ({ params }) => {
    const purchase = purchases.find((item) => item.id === Number(params.id))
    if (!purchase) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '매입 내역을 찾을 수 없습니다.' },
        { status: 404 },
      )
    }
    return HttpResponse.json<ApiResponse<Purchase>>({ success: true, data: purchase, error: null })
  }),

  http.post<never, PurchaseCreateRequest>('/api/v1/purchases', async ({ request }) => {
    const body = await request.json()
    const amountError = purchaseRequestError(body.quantity, body.unitPrice)
    if (amountError) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: amountError },
        { status: 400 },
      )
    }
    const newPurchase: Purchase = {
      id: Math.max(0, ...purchases.map((p) => p.id)) + 1,
      siteId: body.siteId,
      itemName: body.itemName.trim(),
      quantity: body.quantity,
      unitPrice: body.unitPrice,
      totalAmount: body.quantity * body.unitPrice,
      supplier: body.supplier?.trim() || null,
      purchaseDate: body.purchaseDate || null,
      memo: body.memo?.trim() || null,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
    purchases = [...purchases, newPurchase]
    return HttpResponse.json<ApiResponse<Purchase>>(
      { success: true, data: newPurchase, error: null },
      { status: 201 },
    )
  }),

  http.put<{ id: string }, PurchaseUpdateRequest>('/api/v1/purchases/:id', async ({ params, request }) => {
    const id = Number(params.id)
    const index = purchases.findIndex((purchase) => purchase.id === id)
    if (index < 0) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '매입 내역을 찾을 수 없습니다.' },
        { status: 404 },
      )
    }
    const body = await request.json()
    const amountError = purchaseRequestError(body.quantity, body.unitPrice)
    if (amountError) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: amountError },
        { status: 400 },
      )
    }
    const current = purchases[index]
    const updated: Purchase = {
      ...current,
      itemName: body.itemName.trim(),
      quantity: body.quantity,
      unitPrice: body.unitPrice,
      totalAmount: body.quantity * body.unitPrice,
      supplier: body.supplier?.trim() || null,
      purchaseDate: body.purchaseDate || null,
      memo: body.memo?.trim() || null,
      updatedAt: new Date().toISOString(),
    }
    purchases = purchases.map((purchase) => purchase.id === id ? updated : purchase)
    return HttpResponse.json<ApiResponse<Purchase>>({ success: true, data: updated, error: null })
  }),

  http.delete<{ id: string }>('/api/v1/purchases/:id', ({ params }) => {
    const id = Number(params.id)
    if (!purchases.some((purchase) => purchase.id === id)) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '매입 내역을 찾을 수 없습니다.' },
        { status: 404 },
      )
    }
    purchases = purchases.filter((p) => p.id !== id)
    return HttpResponse.json<ApiResponse<null>>({ success: true, data: null, error: null })
  }),
]
