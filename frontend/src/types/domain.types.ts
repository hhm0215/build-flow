// ─────────────────────────────────────────────
// 도메인 타입 — 백엔드 Response DTO와 1:1 대응
// ─────────────────────────────────────────────

// ── Auth ──────────────────────────────────────
export interface LoginResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
}

// ── Client (거래처) ──────────────────────────
export interface Client {
  id: number
  companyName: string
  representative: string
  businessNo: string
  phone: string
  email: string
  address: string
  memo: string
  createdAt: string
  updatedAt: string
}

// ── Site (현장) ───────────────────────────────
export type SiteStatus = 'IN_PROGRESS' | 'SETTLING' | 'WARRANTY' | 'COMPLETED'

export interface Site {
  id: number
  siteName: string
  client: Client | null
  address: string
  status: SiteStatus
  startDate: string
  endDate: string
  memo: string
  createdAt: string
  updatedAt: string
}

export interface SiteCreateRequest {
  siteName: string
  clientId?: number
  address?: string
  startDate?: string
  endDate?: string
  memo?: string
}

// ── Profit (손익) ─────────────────────────────
export interface Profit {
  siteId: number
  totalEstimateAmount: number
  totalPurchaseAmount: number
  margin: number
  marginRate: number
}

// ── Estimate (견적서) ─────────────────────────
export type EstimateStatus = 'DRAFT' | 'CONFIRMED'

export interface EstimateItem {
  id: number
  itemName: string
  unit: string
  quantity: number
  unitPrice: number
  amount: number
}

export interface Estimate {
  id: number
  siteId: number
  title: string
  status: EstimateStatus
  estimateDate: string
  totalAmount: number
  memo: string
  items: EstimateItem[]
  createdAt: string
  updatedAt: string
}

export interface EstimateCreateRequest {
  siteId: number
  title: string
  estimateDate: string
  items: Omit<EstimateItem, 'id'>[]
  memo?: string
}

// ── Purchase (매입) ───────────────────────────
export interface Purchase {
  id: number
  siteId: number
  itemName: string
  quantity: number
  unitPrice: number
  totalAmount: number
  supplier: string
  purchaseDate: string
  memo: string
  createdAt: string
  updatedAt: string
}

export interface PurchaseCreateRequest {
  siteId: number
  itemName: string
  quantity: number
  unitPrice: number
  supplier?: string
  purchaseDate?: string
  memo?: string
}

// ── Tax Invoice (세금계산서) ───────────────────
export type TaxInvoiceType = 'SALES' | 'PURCHASE'

export interface TaxInvoice {
  id: number
  siteId: number
  type: TaxInvoiceType
  supplyAmount: number
  taxAmount: number
  totalAmount: number
  counterparty: string
  issueDate: string
  paymentConfirmed: boolean
  paymentDate: string | null
  memo: string
  createdAt: string
  updatedAt: string
}

export interface TaxInvoiceCreateRequest {
  siteId: number
  type: TaxInvoiceType
  supplyAmount: number
  taxAmount: number
  counterparty: string
  issueDate: string
  memo?: string
}

// ── Dashboard (대시보드) ──────────────────────
export interface DashboardStats {
  totalSites: number
  sitesByStatus: Record<string, number>
  totalEstimateAmount: number
  totalPurchaseAmount: number
  totalMargin: number
  averageMarginRate: number
  siteProfits: SiteProfitSummary[]
}

export interface SiteProfitSummary {
  siteId: number
  siteName: string
  status: string
  estimateAmount: number
  purchaseAmount: number
  margin: number
  marginRate: number
}

export interface DashboardSummary {
  summary: string
  generatedAt: string
}

// ── Notification (알림) ───────────────────────
export interface Notification {
  id: number
  type: string
  message: string
  siteId: number | null
  read: boolean
  createdAt: string
}

// ── Warranty (하자보증보험) ───────────────────
export interface Warranty {
  id: number
  siteId: number
  insuranceCompany: string
  policyNumber: string
  coverageAmount: number
  startDate: string
  endDate: string
  memo: string
  daysUntilExpiry: number
  expired: boolean
  createdAt: string
  updatedAt: string
}
