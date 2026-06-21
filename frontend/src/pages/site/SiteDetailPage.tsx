import { useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Select, Tabs, message } from 'antd'
import { motion } from 'motion/react'
import {
  ArrowLeft,
  Building2,
  CalendarDays,
  FileText,
  HardHat,
  MapPin,
  ShieldCheck,
  ShoppingBag,
  TrendingUp,
  Wallet,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import ErrorState from '../../components/ErrorState'
import {
  useSite,
  useSiteProfit,
  useUpdateSiteStatus,
} from '../../api/sites.api'
import { useEstimates } from '../../api/estimates.api'
import { usePurchases } from '../../api/purchases.api'
import { useTaxes } from '../../api/taxes.api'
import { useWarranties } from '../../api/warranties.api'
import type { Site, SiteStatus } from '../../types'

const STATUS_LABEL: Record<SiteStatus, string> = {
  IN_PROGRESS: '시공 중',
  SETTLING: '정산 중',
  WARRANTY: '하자보증',
  COMPLETED: '완료',
}
const STATUS_COLOR: Record<SiteStatus, string> = {
  IN_PROGRESS: '#3b82f6',
  SETTLING: '#f59e0b',
  WARRANTY: '#8b5cf6',
  COMPLETED: '#52525b',
}
const STATUS_OPTIONS = (Object.keys(STATUS_LABEL) as SiteStatus[]).map((s) => ({
  value: s,
  label: STATUS_LABEL[s],
}))

const ESTIMATE_STATUS_LABEL: Record<string, string> = {
  DRAFT: '작성 중',
  CONFIRMED: '확정',
}
const ESTIMATE_STATUS_COLOR: Record<string, string> = {
  DRAFT: '#71717a',
  CONFIRMED: '#22c55e',
}

function formatKRW(value: number) {
  return `₩${value.toLocaleString('ko-KR')}`
}

function formatCompactKRW(value: number) {
  if (value === 0) return '₩0'
  if (Math.abs(value) >= 100_000_000) return `₩${(value / 100_000_000).toFixed(1)}억`
  if (Math.abs(value) >= 1_000_000) return `₩${(value / 1_000_000).toFixed(1)}M`
  return formatKRW(value)
}

function formatDate(value?: string | null) {
  if (!value) return '-'
  return value.split('T')[0]
}

function InfoBadge({
  color,
  children,
}: {
  color: string
  children: React.ReactNode
}) {
  return (
    <span
      style={{
        fontSize: 11,
        fontWeight: 600,
        padding: '4px 8px',
        borderRadius: 20,
        background: `${color}18`,
        color,
        border: `1px solid ${color}30`,
        whiteSpace: 'nowrap',
      }}
    >
      {children}
    </span>
  )
}

function SummaryCard({
  icon: Icon,
  title,
  value,
  description,
  color,
}: {
  icon: LucideIcon
  title: string
  value: string
  description: string
  color: string
}) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      style={{
        background: 'var(--bg-card)',
        border: '1px solid var(--border)',
        borderRadius: 12,
        padding: 16,
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12 }}>
        <div>
          <div style={{ fontSize: 12, color: 'var(--text-muted)', marginBottom: 8 }}>{title}</div>
          <div
            style={{
              fontSize: 20,
              fontWeight: 700,
              color: 'var(--text-primary)',
              letterSpacing: '-0.03em',
            }}
          >
            {value}
          </div>
          <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 6 }}>{description}</div>
        </div>
        <div
          style={{
            width: 34,
            height: 34,
            borderRadius: 10,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            background: `${color}16`,
            border: `1px solid ${color}28`,
            flexShrink: 0,
          }}
        >
          <Icon size={16} color={color} strokeWidth={2.2} />
        </div>
      </div>
    </motion.div>
  )
}

function MetaRow({
  icon: Icon,
  label,
  value,
}: {
  icon: LucideIcon
  label: string
  value: string
}) {
  return (
    <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
      <Icon size={14} color="rgba(255,255,255,0.55)" strokeWidth={2} style={{ marginTop: 2, flexShrink: 0 }} />
      <div>
        <div style={{ fontSize: 11, color: 'rgba(255,255,255,0.55)', marginBottom: 2 }}>{label}</div>
        <div style={{ fontSize: 13, color: 'rgba(255,255,255,0.92)' }}>{value || '-'}</div>
      </div>
    </div>
  )
}

function EmptyTab({ message }: { message: string }) {
  return (
    <div
      style={{
        border: '1px dashed var(--border-strong)',
        borderRadius: 10,
        padding: '24px 16px',
        fontSize: 13,
        color: 'var(--text-muted)',
        background: 'rgba(255,255,255,0.02)',
        textAlign: 'center',
      }}
    >
      {message}
    </div>
  )
}

function ListSkeleton() {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      {[1, 2].map((i) => (
        <div key={i} className="shimmer" style={{ height: 74, borderRadius: 10 }} />
      ))}
    </div>
  )
}

function ItemCard({ children }: { children: React.ReactNode }) {
  return (
    <div
      style={{
        padding: 14,
        borderRadius: 10,
        border: '1px solid var(--border)',
        background: 'rgba(255,255,255,0.02)',
      }}
    >
      {children}
    </div>
  )
}

export default function SiteDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const siteId = Number(id)
  const invalidId = !id || Number.isNaN(siteId)

  const { data: site, isLoading: siteLoading, isError: siteError, refetch: siteRefetch } = useSite(siteId)
  const { data: profit } = useSiteProfit(siteId)
  const updateStatus = useUpdateSiteStatus()

  const params = useMemo(() => ({ siteId: String(siteId) }), [siteId])
  const { data: estimatesData, isLoading: estimatesLoading } = useEstimates(params)
  const { data: purchasesData, isLoading: purchasesLoading } = usePurchases(params)
  const { data: taxesData, isLoading: taxesLoading } = useTaxes(params)
  const { data: warrantiesData, isLoading: warrantiesLoading } = useWarranties(params)

  const estimates = (estimatesData ?? []).filter((e) => e.siteId === siteId)
  const purchases = (purchasesData ?? []).filter((p) => p.siteId === siteId)
  const taxes = (taxesData ?? []).filter((t) => t.siteId === siteId)
  const warranties = (warrantiesData ?? []).filter((w) => w.siteId === siteId)

  const sortedEstimates = [...estimates].sort((a, b) => b.createdAt.localeCompare(a.createdAt))
  const sortedPurchases = [...purchases].sort((a, b) => b.purchaseDate.localeCompare(a.purchaseDate))
  const sortedTaxes = [...taxes].sort((a, b) => b.issueDate.localeCompare(a.issueDate))
  const sortedWarranties = [...warranties].sort((a, b) => a.endDate.localeCompare(b.endDate))

  const estimateTotal = profit?.totalEstimateAmount ?? estimates.reduce((sum, e) => sum + e.totalAmount, 0)
  const purchaseTotal = profit?.totalPurchaseAmount ?? purchases.reduce((sum, p) => sum + p.totalAmount, 0)
  const margin = profit?.margin ?? estimateTotal - purchaseTotal
  const marginRate =
    profit?.marginRate ?? (estimateTotal > 0 ? (margin / estimateTotal) * 100 : 0)
  const unpaidTotal = taxes
    .filter((t) => t.type === 'SALES' && !t.paymentConfirmed)
    .reduce((sum, t) => sum + t.totalAmount, 0)

  const [tab, setTab] = useState<string>('estimates')

  const handleStatusChange = (next: SiteStatus) => {
    if (!site || next === site.status) return
    updateStatus.mutate(
      { id: siteId, status: next },
      {
        onSuccess: () => message.success(`현장 상태를 '${STATUS_LABEL[next]}'(으)로 변경했습니다.`),
        onError: () => message.error('상태 변경에 실패했습니다. 잠시 후 다시 시도해 주세요.'),
      },
    )
  }

  if (invalidId) {
    return (
      <ErrorState
        message="잘못된 현장 ID입니다. 목록에서 다시 선택해 주세요."
        onRetry={() => navigate('/sites')}
      />
    )
  }

  if (siteError) {
    return <ErrorState onRetry={siteRefetch} />
  }

  if (siteLoading || !site) {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        <div className="shimmer" style={{ height: 40, width: 120, borderRadius: 8 }} />
        <div className="shimmer" style={{ height: 180, borderRadius: 16 }} />
        <div className="shimmer" style={{ height: 88, borderRadius: 12 }} />
        <div className="shimmer" style={{ height: 320, borderRadius: 12 }} />
      </div>
    )
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <motion.button
        whileHover={{ x: -2 }}
        whileTap={{ scale: 0.97 }}
        onClick={() => navigate('/sites')}
        style={{
          alignSelf: 'flex-start',
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          padding: '6px 12px',
          background: 'var(--bg-card)',
          border: '1px solid var(--border)',
          borderRadius: 8,
          color: 'var(--text-secondary)',
          fontSize: 13,
          cursor: 'pointer',
        }}
      >
        <ArrowLeft size={14} strokeWidth={2} />
        현장 목록
      </motion.button>

      <SiteHeaderHero
        site={site}
        onStatusChange={handleStatusChange}
        statusChanging={updateStatus.isPending}
      />

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(4, minmax(0, 1fr))',
          gap: 12,
        }}
      >
        <SummaryCard
          icon={FileText}
          title="매출 (견적서 합계)"
          value={formatCompactKRW(estimateTotal)}
          description={`${estimates.length}건의 견적서`}
          color="#8b5cf6"
        />
        <SummaryCard
          icon={ShoppingBag}
          title="매입 합계"
          value={formatCompactKRW(purchaseTotal)}
          description={`${purchases.length}건의 매입`}
          color="#f59e0b"
        />
        <SummaryCard
          icon={TrendingUp}
          title="마진"
          value={formatCompactKRW(margin)}
          description={estimateTotal > 0 ? `마진율 ${marginRate.toFixed(1)}%` : '아직 산출 전'}
          color={margin >= 0 ? '#22c55e' : '#ef4444'}
        />
        <SummaryCard
          icon={Wallet}
          title="미수금"
          value={formatCompactKRW(unpaidTotal)}
          description={unpaidTotal > 0 ? '입금 미확인 매출' : '미수금 없음'}
          color={unpaidTotal > 0 ? '#ef4444' : '#52525b'}
        />
      </div>

      <motion.section
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        style={{
          background: 'var(--bg-card)',
          border: '1px solid var(--border)',
          borderRadius: 12,
          padding: '12px 18px 18px',
        }}
      >
        <Tabs
          activeKey={tab}
          onChange={setTab}
          items={[
            {
              key: 'estimates',
              label: `견적서 (${estimates.length})`,
              children: estimatesLoading ? (
                <ListSkeleton />
              ) : sortedEstimates.length > 0 ? (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                  {sortedEstimates.map((estimate) => (
                    <ItemCard key={estimate.id}>
                      <div
                        style={{
                          display: 'flex',
                          alignItems: 'flex-start',
                          justifyContent: 'space-between',
                          gap: 12,
                          marginBottom: 10,
                          flexWrap: 'wrap',
                        }}
                      >
                        <div>
                          <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' }}>
                            {estimate.title}
                          </div>
                          <div style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 4 }}>
                            작성일 {formatDate(estimate.createdAt)} · 항목 {estimate.items.length}건
                          </div>
                        </div>
                        <InfoBadge color={ESTIMATE_STATUS_COLOR[estimate.status]}>
                          {ESTIMATE_STATUS_LABEL[estimate.status]}
                        </InfoBadge>
                      </div>
                      <div
                        style={{
                          display: 'grid',
                          gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
                          gap: 10,
                        }}
                      >
                        <div>
                          <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 4 }}>총액</div>
                          <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--text-primary)' }}>
                            {estimate.totalAmount > 0 ? formatKRW(estimate.totalAmount) : '미정'}
                          </div>
                        </div>
                        <div>
                          <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 4 }}>최근 수정</div>
                          <div style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
                            {formatDate(estimate.updatedAt)}
                          </div>
                        </div>
                      </div>
                    </ItemCard>
                  ))}
                </div>
              ) : (
                <EmptyTab message="이 현장에 연결된 견적서가 아직 없습니다." />
              ),
            },
            {
              key: 'purchases',
              label: `매입 (${purchases.length})`,
              children: purchasesLoading ? (
                <ListSkeleton />
              ) : sortedPurchases.length > 0 ? (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                  {sortedPurchases.map((purchase) => (
                    <ItemCard key={purchase.id}>
                      <div
                        style={{
                          display: 'flex',
                          alignItems: 'flex-start',
                          justifyContent: 'space-between',
                          gap: 12,
                          marginBottom: 10,
                          flexWrap: 'wrap',
                        }}
                      >
                        <div>
                          <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' }}>
                            {purchase.itemName}
                          </div>
                          <div style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 4 }}>
                            거래처 {purchase.supplier || '-'} · 매입일 {purchase.purchaseDate}
                          </div>
                        </div>
                      </div>
                      <div
                        style={{
                          display: 'grid',
                          gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
                          gap: 10,
                        }}
                      >
                        <div>
                          <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 4 }}>금액</div>
                          <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--text-primary)' }}>
                            {formatKRW(purchase.totalAmount)}
                          </div>
                        </div>
                        <div>
                          <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 4 }}>수량 × 단가</div>
                          <div style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
                            {purchase.quantity} × {formatKRW(purchase.unitPrice)}
                          </div>
                        </div>
                      </div>
                    </ItemCard>
                  ))}
                </div>
              ) : (
                <EmptyTab message="이 현장에 연결된 매입 내역이 아직 없습니다." />
              ),
            },
            {
              key: 'taxes',
              label: `세금계산서 (${taxes.length})`,
              children: taxesLoading ? (
                <ListSkeleton />
              ) : sortedTaxes.length > 0 ? (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                  {sortedTaxes.map((invoice) => (
                    <ItemCard key={invoice.id}>
                      <div
                        style={{
                          display: 'flex',
                          alignItems: 'flex-start',
                          justifyContent: 'space-between',
                          gap: 12,
                          marginBottom: 10,
                          flexWrap: 'wrap',
                        }}
                      >
                        <div>
                          <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' }}>
                            {invoice.counterparty}
                          </div>
                          <div style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 4 }}>
                            발행일 {invoice.issueDate}
                          </div>
                        </div>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                          <InfoBadge color={invoice.type === 'SALES' ? '#22c55e' : '#ef4444'}>
                            {invoice.type === 'SALES' ? '매출' : '매입'}
                          </InfoBadge>
                          <InfoBadge color={invoice.paymentConfirmed ? '#22c55e' : '#f59e0b'}>
                            {invoice.paymentConfirmed ? '입금 완료' : '미입금'}
                          </InfoBadge>
                        </div>
                      </div>
                      <div
                        style={{
                          display: 'grid',
                          gridTemplateColumns: 'repeat(3, minmax(0, 1fr))',
                          gap: 10,
                        }}
                      >
                        <div>
                          <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 4 }}>합계</div>
                          <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--text-primary)' }}>
                            {formatKRW(invoice.totalAmount)}
                          </div>
                        </div>
                        <div>
                          <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 4 }}>공급가 / 세액</div>
                          <div style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
                            {formatKRW(invoice.supplyAmount)} / {formatKRW(invoice.taxAmount)}
                          </div>
                        </div>
                        <div>
                          <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 4 }}>입금 상태</div>
                          <div
                            style={{
                              fontSize: 13,
                              fontWeight: 700,
                              color: invoice.paymentConfirmed ? 'var(--text-secondary)' : '#ef4444',
                            }}
                          >
                            {invoice.paymentConfirmed
                              ? `입금일 ${formatDate(invoice.paymentDate)}`
                              : '미입금'}
                          </div>
                        </div>
                      </div>
                    </ItemCard>
                  ))}
                </div>
              ) : (
                <EmptyTab message="이 현장에 연결된 세금계산서가 아직 없습니다." />
              ),
            },
            {
              key: 'warranties',
              label: `보증보험 (${warranties.length})`,
              children: warrantiesLoading ? (
                <ListSkeleton />
              ) : sortedWarranties.length > 0 ? (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                  {sortedWarranties.map((w) => {
                    const expiringColor = w.expired
                      ? '#ef4444'
                      : w.daysUntilExpiry <= 30
                        ? '#f59e0b'
                        : '#22c55e'
                    const expiringLabel = w.expired
                      ? '만료됨'
                      : `D-${w.daysUntilExpiry}`
                    return (
                      <ItemCard key={w.id}>
                        <div
                          style={{
                            display: 'flex',
                            alignItems: 'flex-start',
                            justifyContent: 'space-between',
                            gap: 12,
                            marginBottom: 10,
                            flexWrap: 'wrap',
                          }}
                        >
                          <div>
                            <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' }}>
                              {w.insuranceCompany}
                            </div>
                            <div style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 4 }}>
                              증권번호 {w.policyNumber}
                            </div>
                          </div>
                          <InfoBadge color={expiringColor}>{expiringLabel}</InfoBadge>
                        </div>
                        <div
                          style={{
                            display: 'grid',
                            gridTemplateColumns: 'repeat(3, minmax(0, 1fr))',
                            gap: 10,
                          }}
                        >
                          <div>
                            <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 4 }}>보증금액</div>
                            <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--text-primary)' }}>
                              {w.coverageAmount != null ? formatKRW(w.coverageAmount) : '—'}
                            </div>
                          </div>
                          <div>
                            <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 4 }}>보증 기간</div>
                            <div style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
                              {w.startDate} ~ {w.endDate}
                            </div>
                          </div>
                          <div>
                            <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 4 }}>메모</div>
                            <div style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
                              {w.memo || '-'}
                            </div>
                          </div>
                        </div>
                      </ItemCard>
                    )
                  })}
                </div>
              ) : (
                <EmptyTab message="이 현장에 등록된 하자보증보험이 아직 없습니다." />
              ),
            },
          ]}
        />
      </motion.section>
    </div>
  )
}

function SiteHeaderHero({
  site,
  onStatusChange,
  statusChanging,
}: {
  site: Site
  onStatusChange: (status: SiteStatus) => void
  statusChanging: boolean
}) {
  return (
    <motion.section
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      style={{
        background:
          'linear-gradient(135deg, rgba(59,130,246,0.18) 0%, rgba(15,23,42,0.92) 45%, rgba(9,9,11,1) 100%)',
        border: '1px solid rgba(59,130,246,0.18)',
        borderRadius: 16,
        padding: 22,
        boxShadow: 'var(--shadow-lg)',
      }}
    >
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'flex-start',
          gap: 16,
          flexWrap: 'wrap',
        }}
      >
        <div style={{ minWidth: 0, flex: 1 }}>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 10,
              marginBottom: 10,
              color: 'rgba(255,255,255,0.6)',
              fontSize: 12,
            }}
          >
            <HardHat size={14} strokeWidth={2} />
            현장 상세
          </div>
          <div
            style={{
              fontSize: 28,
              fontWeight: 800,
              color: 'white',
              letterSpacing: '-0.04em',
              marginBottom: 12,
              wordBreak: 'keep-all',
            }}
          >
            {site.siteName}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', marginBottom: 16 }}>
            <InfoBadge color="#fafafa">
              {site.client?.companyName ?? '거래처 미지정'}
            </InfoBadge>
            <InfoBadge color={STATUS_COLOR[site.status]}>{STATUS_LABEL[site.status]}</InfoBadge>
          </div>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
              gap: 14,
            }}
          >
            <MetaRow icon={Building2} label="발주처" value={site.client?.companyName ?? '-'} />
            <MetaRow
              icon={CalendarDays}
              label="공사 기간"
              value={`${site.startDate || '-'} ~ ${site.endDate || '-'}`}
            />
            <MetaRow icon={MapPin} label="현장 주소" value={site.address} />
            <MetaRow icon={ShieldCheck} label="메모" value={site.memo} />
          </div>
        </div>

        <div
          style={{
            minWidth: 220,
            padding: '14px 16px',
            borderRadius: 12,
            background: 'rgba(255,255,255,0.05)',
            border: '1px solid rgba(255,255,255,0.08)',
          }}
        >
          <div style={{ fontSize: 11, color: 'rgba(255,255,255,0.55)', marginBottom: 6 }}>현장 상태</div>
          <Select
            value={site.status}
            options={STATUS_OPTIONS}
            onChange={onStatusChange}
            disabled={statusChanging}
            loading={statusChanging}
            style={{ width: '100%' }}
          />
          <div style={{ fontSize: 11, color: 'rgba(255,255,255,0.55)', marginTop: 12, marginBottom: 4 }}>
            등록일
          </div>
          <div style={{ fontSize: 13, color: 'rgba(255,255,255,0.85)' }}>
            {formatDate(site.createdAt)}
          </div>
        </div>
      </div>
    </motion.section>
  )
}

