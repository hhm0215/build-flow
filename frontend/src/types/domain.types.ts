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
  representative: string | null
  businessNo: string | null
  phone: string | null
  email: string | null
  address: string | null
  memo: string | null
  /** 보관 시각. null이면 미보관. 보관 거래처는 현장에 새로 연결할 수 없다 */
  archivedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface ClientCreateRequest {
  companyName: string
  representative?: string
  businessNo?: string
  phone?: string
  email?: string
  address?: string
  memo?: string
}

export interface ClientUpdateRequest {
  companyName: string
  representative: string | null
  businessNo: string | null
  phone: string | null
  email: string | null
  address: string | null
  memo: string | null
}

// ── Site (현장) ───────────────────────────────
/** BIDDING = 수주 전·견적 중. 수주하면 IN_PROGRESS로 바꾸고 수주 실패는 보관한다 */
export type SiteStatus = 'BIDDING' | 'IN_PROGRESS' | 'SETTLING' | 'WARRANTY' | 'COMPLETED'

export interface Site {
  id: number
  siteName: string
  client: Client | null
  address: string | null
  status: SiteStatus
  startDate: string | null
  endDate: string | null
  memo: string | null
  /** 보관 시각. null이면 미보관 (업무 단계 status와 별개) */
  archivedAt: string | null
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
  /** 생략 시 서버 기본값 IN_PROGRESS. BIDDING/IN_PROGRESS만 허용 */
  status?: SiteStatus
}

export interface SiteUpdateRequest {
  siteName: string
  clientId: number | null
  address: string | null
  startDate: string | null
  endDate: string | null
  memo: string | null
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
// SUPERSEDED: 같은 수정 체인의 새 확정본으로 대체됨 — 이력만 남고 손익에서 빠진다.
export type EstimateStatus = 'DRAFT' | 'CONFIRMED' | 'SUPERSEDED'
// REVISION은 revisionOfId 견적을 대체(같은 체인), ADDITIONAL은 별도 금액으로 합산(새 체인).
export type EstimateKind = 'ORIGINAL' | 'REVISION' | 'ADDITIONAL'

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
  kind: EstimateKind
  revisionOfId: number | null
  /** 수정 체인 루트 id (원본·추가공사·기존 견적은 자기 id) */
  chainRootId: number
  supersededById: number | null
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
  /** 생략하면 ORIGINAL. REVISION·ADDITIONAL은 revisionOfId 필수 */
  kind?: EstimateKind
  revisionOfId?: number
}

export interface EstimateUpdateRequest {
  title: string
  estimateDate: string
  items: Pick<EstimateItem, 'itemName' | 'unit' | 'quantity' | 'unitPrice'>[]
  memo?: string
}

// ── 공내역서 파싱 (Ollama) ─────────────────
export interface ParsedItemResult {
  itemName: string
  unit: string
  quantity: number
  unitPrice: number
  amount: number
}

export interface ParseResult {
  fileName: string
  itemCount: number
  items: ParsedItemResult[]
}

// ── Purchase (매입) ───────────────────────────
export interface Purchase {
  id: number
  siteId: number
  itemName: string
  quantity: number
  unitPrice: number
  totalAmount: number
  supplier: string | null
  purchaseDate: string | null
  memo: string | null
  /** 증빙 매입 세금계산서 ID. 미연결이면 null. 원가 계산에는 영향 없음 */
  evidenceTaxInvoiceId: number | null
  createdAt: string
  updatedAt: string
}

export interface PurchaseEvidenceLinkRequest {
  taxInvoiceId: number
}

/** 매입 계산서 1건 기준 연결 매입 대조. 차이 = 계산서 금액 − 연결 매입 합계 */
export interface PurchaseInvoiceEvidence {
  taxInvoiceId: number
  counterparty: string | null
  issueDate: string | null
  supplyAmount: number
  totalAmount: number
  linkedPurchaseCount: number
  linkedPurchaseAmount: number
  supplyDifference: number
  totalDifference: number
}

/** 현장별 매입 항목 ↔ 매입 세금계산서 증빙 대조. 원가는 매입 항목만 사용한다 */
export interface PurchaseEvidenceSummary {
  siteId: number
  purchaseCount: number
  purchaseAmount: number
  linkedPurchaseCount: number
  linkedPurchaseAmount: number
  /** 증빙 미연결(끊어진 연결 포함) */
  unlinkedPurchaseCount: number
  unlinkedPurchaseAmount: number
  /** 연결된 계산서가 삭제됐거나 더 이상 같은 현장 매입 계산서가 아닌 매입 */
  brokenLinkCount: number
  invoiceCount: number
  invoiceSupplyAmount: number
  invoiceTotalAmount: number
  invoiceWithoutPurchaseCount: number
  supplyDifference: number
  totalDifference: number
  invoices: PurchaseInvoiceEvidence[]
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

export interface PurchaseUpdateRequest {
  itemName: string
  quantity: number
  unitPrice: number
  supplier?: string
  purchaseDate?: string
  memo?: string
}

// ── Tax Invoice (세금계산서) ───────────────────
export type TaxInvoiceType = 'SALES' | 'PURCHASE'
/** 매출 수금 상태. 매입은 수금 대상이 아니어서 null. */
export type TaxPaymentStatus = 'UNPAID' | 'PARTIAL' | 'PAID'

export interface TaxInvoice {
  id: number
  siteId: number
  type: TaxInvoiceType
  supplyAmount: number
  taxAmount: number
  totalAmount: number
  counterparty: string | null
  issueDate: string | null
  /** 전액 입금 완료(유효 입금 합계 = 총액) */
  paymentConfirmed: boolean
  /** 완료 시 마지막 유효 입금일 */
  paymentDate: string | null
  /** 유효 입금 합계(취소 제외). 매입은 null */
  paidAmount: number | null
  /** 총액 − 유효 입금 합계. 매입은 null */
  remainingAmount: number | null
  paymentStatus: TaxPaymentStatus | null
  memo: string | null
  createdAt: string
  updatedAt: string
}

/** 매출 세금계산서 입금 1건. 오입금은 삭제하지 않고 취소 시각을 남긴다. */
export interface TaxPayment {
  id: number
  taxInvoiceId: number
  amount: number
  paymentDate: string
  memo: string | null
  canceled: boolean
  canceledAt: string | null
  cancelReason: string | null
  createdAt: string
}

export interface TaxPaymentCreateRequest {
  amount: number
  paymentDate: string
  memo?: string
}

export interface TaxPaymentCancelRequest {
  reason?: string
}

export interface TaxInvoiceCreateRequest {
  siteId: number
  type: TaxInvoiceType
  supplyAmount: number
  taxAmount: number
  counterparty?: string
  issueDate?: string
  memo?: string
}

export interface TaxInvoiceUpdateRequest {
  type: TaxInvoiceType
  supplyAmount: number
  taxAmount: number
  counterparty?: string
  issueDate?: string
  memo?: string
}

export interface PaymentConfirmRequest {
  id: number
  paymentDate: string
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
export type OcrStatus = 'PENDING' | 'SUCCESS' | 'FAILED' | 'MANUAL'

export interface Warranty {
  id: number
  siteId: number
  insuranceCompany: string | null
  policyNumber: string | null
  coverageAmount: number | null
  startDate: string | null
  endDate: string | null
  memo: string | null
  daysUntilExpiry: number
  expired: boolean
  /** 만료 판단은 사용자 확인/수기 저장 이후에만 확정한다. */
  expiryConfirmed?: boolean
  expiryConfirmedAt?: string | null
  ocrStatus?: OcrStatus
  /** 업로드 사본의 DB 연결 여부. 실제 파일이 없으면 다운로드는 404로 거부된다. */
  hasUploadedPdf?: boolean
  createdAt: string
  updatedAt: string
}

export interface WarrantyCreateRequest {
  siteId: number
  insuranceCompany: string
  policyNumber: string
  coverageAmount?: number
  startDate: string
  endDate: string
  memo?: string
}

export interface WarrantyUpdateRequest {
  insuranceCompany: string
  policyNumber: string | null
  coverageAmount: number | null
  startDate: string
  endDate: string
  memo: string | null
}

/** 보증 값 변경 감사 이력(추가 전용). actor는 공유 관리자 계정 단위이며 실제 사람을 증명하지 않는다 */
export type WarrantyAuditAction = 'CREATED' | 'UPLOADED' | 'OCR_APPLIED' | 'OCR_FAILED' | 'MANUAL_UPDATED' | 'DELETED'

export interface WarrantyAuditEvent {
  id: number
  warrantyId: number
  action: WarrantyAuditAction
  actor: string
  insuranceCompany: string | null
  policyNumber: string | null
  startDate: string | null
  endDate: string | null
  coverageAmount: number | null
  ocrStatus: OcrStatus | null
  expiryConfirmedAt: string | null
  occurredAt: string
}
