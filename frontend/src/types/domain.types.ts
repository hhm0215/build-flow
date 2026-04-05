// ─────────────────────────────────────────────
// 도메인 타입 — 백엔드 Response DTO와 1:1 대응
// ─────────────────────────────────────────────

// ── Auth ──────────────────────────────────────
export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
}

// ── Site (현장) ───────────────────────────────
export type SiteStatus = 'PREPARATION' | 'IN_PROGRESS' | 'FINISHING' | 'COMPLETED'

export interface Site {
  id: number
  name: string
  client: string         // 발주처
  status: SiteStatus
  startDate: string      // ISO date
  endDate: string
  totalRevenue: number   // 매출 합계 (견적서 기준)
  totalCost: number      // 매입 합계
  margin: number         // totalRevenue - totalCost
  marginRate: number     // margin / totalRevenue * 100
}

// ── Estimate (견적서) ─────────────────────────
export type EstimateStatus = 'DRAFT' | 'REVIEWING' | 'CONFIRMED'

export interface EstimateItem {
  id: number
  name: string           // 품목명
  unit: string           // 단위
  quantity: number
  unitPrice: number
  totalPrice: number
}

export interface Estimate {
  id: number
  siteId: number
  siteName: string
  title: string
  status: EstimateStatus
  totalAmount: number
  items: EstimateItem[]
  createdAt: string
  updatedAt: string
}

// ── Purchase (매입) ───────────────────────────
export type PurchaseCategory = 'MATERIAL' | 'LABOR' | 'EQUIPMENT' | 'OTHER'

export interface Purchase {
  id: number
  siteId: number
  siteName: string
  category: PurchaseCategory
  description: string
  vendor: string         // 거래처
  amount: number
  purchaseDate: string
  createdAt: string
}

// ── Tax Invoice (세금계산서) ───────────────────
export type TaxType = 'SALES' | 'PURCHASE'
export type TaxStatus = 'ISSUED' | 'RECEIVED' | 'OVERDUE' | 'PAID'

export interface TaxInvoice {
  id: number
  siteId: number
  siteName: string
  type: TaxType
  status: TaxStatus
  vendor: string
  amount: number
  taxAmount: number      // 부가세
  totalAmount: number    // amount + taxAmount
  issueDate: string
  dueDate: string
  paidDate: string | null
  unpaidAmount: number   // 미수금
}
