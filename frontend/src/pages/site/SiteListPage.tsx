import { useEffect, useState } from 'react'
import { Input } from 'antd'
import { motion } from 'motion/react'
import {
  Building2,
  CalendarDays,
  FileText,
  HardHat,
  Plus,
  Receipt,
  Search,
  ShoppingBag,
  TrendingUp,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import PageHeader from '../../components/PageHeader'
import { useSites } from '../../api/sites.api'
import { useEstimates } from '../../api/estimates.api'
import { usePurchases } from '../../api/purchases.api'
import { useTaxes } from '../../api/taxes.api'
import { Site } from '../../types'

const STATUS_LABEL: Record<Site['status'], string> = {
  IN_PROGRESS: '시공 중',
  SETTLING: '정산 중',
  WARRANTY: '하자보증',
  COMPLETED: '완료',
}
const STATUS_COLOR: Record<Site['status'], string> = {
  IN_PROGRESS: '#3b82f6',
  SETTLING: '#f59e0b',
  WARRANTY: '#8b5cf6',
  COMPLETED: '#52525b',
}

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
  if (Math.abs(value) >= 100_000_000) {
    return `₩${(value / 100_000_000).toFixed(1)}억`
  }
  if (Math.abs(value) >= 1_000_000) {
    return `₩${(value / 1_000_000).toFixed(1)}M`
  }
  return formatKRW(value)
}

function formatDate(value: string) {
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
          <div style={{ fontSize: 20, fontWeight: 700, color: 'var(--text-primary)', letterSpacing: '-0.03em' }}>
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

function RelationSection({
  icon: Icon,
  title,
  caption,
  children,
}: {
  icon: LucideIcon
  title: string
  caption: string
  children: React.ReactNode
}) {
  return (
    <motion.section
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      style={{
        background: 'var(--bg-card)',
        border: '1px solid var(--border)',
        borderRadius: 12,
        padding: 18,
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 12,
          marginBottom: 14,
          flexWrap: 'wrap',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <div
            style={{
              width: 32,
              height: 32,
              borderRadius: 10,
              background: 'rgba(59,130,246,0.12)',
              border: '1px solid rgba(59,130,246,0.2)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Icon size={15} color="var(--accent)" strokeWidth={2.3} />
          </div>
          <div>
            <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--text-primary)' }}>{title}</div>
            <div style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 2 }}>{caption}</div>
          </div>
        </div>
      </div>
      {children}
    </motion.section>
  )
}

function EmptyRelation({ message }: { message: string }) {
  return (
    <div
      style={{
        border: '1px dashed var(--border-strong)',
        borderRadius: 10,
        padding: '18px 16px',
        fontSize: 13,
        color: 'var(--text-muted)',
        background: 'rgba(255,255,255,0.02)',
      }}
    >
      {message}
    </div>
  )
}

export default function SiteListPage() {
  const { data: sitesData, isLoading: sitesLoading } = useSites()
  const { data: estimatesData, isLoading: estimatesLoading } = useEstimates()
  const { data: purchasesData, isLoading: purchasesLoading } = usePurchases()
  const { data: taxesData, isLoading: taxesLoading } = useTaxes()

  const sites = sitesData ?? []
  const estimates = estimatesData ?? []
  const purchases = purchasesData ?? []
  const taxes = taxesData ?? []

  const [query, setQuery] = useState('')
  const [selectedSiteId, setSelectedSiteId] = useState<number | null>(null)

  const normalizedQuery = query.trim().toLowerCase()
  const filteredSites = sites.filter((site) => {
    if (!normalizedQuery) return true
    return `${site.siteName} ${site.client?.companyName ?? ''}`.toLowerCase().includes(normalizedQuery)
  })

  useEffect(() => {
    if (!filteredSites.length) {
      if (selectedSiteId !== null) setSelectedSiteId(null)
      return
    }

    if (!filteredSites.some((site) => site.id === selectedSiteId)) {
      setSelectedSiteId(filteredSites[0].id)
    }
  }, [filteredSites, selectedSiteId])

  const selectedSite = filteredSites.find((site) => site.id === selectedSiteId) ?? null

  const siteEstimates = selectedSite
    ? estimates
      .filter((estimate) => estimate.siteId === selectedSite.id)
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    : []

  const sitePurchases = selectedSite
    ? purchases
      .filter((purchase) => purchase.siteId === selectedSite.id)
      .sort((a, b) => b.purchaseDate.localeCompare(a.purchaseDate))
    : []

  const siteTaxes = selectedSite
    ? taxes
      .filter((tax) => tax.siteId === selectedSite.id)
      .sort((a, b) => b.issueDate.localeCompare(a.issueDate))
    : []

  const estimateTotal = siteEstimates.reduce((sum, estimate) => sum + estimate.totalAmount, 0)
  const purchaseTotal = sitePurchases.reduce((sum, purchase) => sum + purchase.totalAmount, 0)
  const taxTotal = siteTaxes.reduce((sum, tax) => sum + tax.totalAmount, 0)
  const unpaidTotal = siteTaxes.filter((t) => !t.paymentConfirmed && t.type === 'SALES').reduce((sum, t) => sum + t.totalAmount, 0)

  return (
    <div>
      <PageHeader
        icon={HardHat}
        title="현장 관리"
        description="현장을 검색하고 선택한 현장의 견적서, 세금계산서, 매입 내역을 한 번에 확인합니다."
        action={
          <motion.button
            whileHover={{ scale: 1.02 }}
            whileTap={{ scale: 0.98 }}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 6,
              padding: '8px 16px',
              background: 'var(--accent-gradient)',
              border: 'none',
              borderRadius: 'var(--radius-sm)',
              color: 'white',
              fontSize: 13,
              fontWeight: 600,
              cursor: 'pointer',
              boxShadow: '0 0 16px rgba(59,130,246,0.2)',
            }}
          >
            <Plus size={14} strokeWidth={2.5} />
            현장 추가
          </motion.button>
        }
      />

      {sitesLoading ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {[1, 2, 3, 4].map((i) => (
            <div key={i} className="shimmer" style={{ height: i === 1 ? 180 : 88, borderRadius: 12 }} />
          ))}
        </div>
      ) : (
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: '320px minmax(0, 1fr)',
            gap: 16,
            alignItems: 'start',
          }}
        >
          <aside
            style={{
              background: 'var(--bg-card)',
              border: '1px solid var(--border)',
              borderRadius: 12,
              padding: 16,
              position: 'sticky',
              top: 0,
            }}
          >
            <div style={{ marginBottom: 12 }}>
              <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--text-primary)', marginBottom: 4 }}>
                현장 탐색
              </div>
              <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>
                현장명 또는 발주처로 검색해서 바로 관련 문서를 확인하세요.
              </div>
            </div>

            <Input
              allowClear
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              prefix={<Search size={15} color="var(--text-muted)" strokeWidth={2} />}
              placeholder="현장명, 발주처 검색"
            />

            <div
              style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
                gap: 8,
                marginTop: 12,
                marginBottom: 14,
              }}
            >
              <div
                style={{
                  padding: '10px 12px',
                  borderRadius: 10,
                  background: 'rgba(59,130,246,0.08)',
                  border: '1px solid rgba(59,130,246,0.16)',
                }}
              >
                <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 4 }}>전체 현장</div>
                <div style={{ fontSize: 18, fontWeight: 700, color: 'var(--text-primary)' }}>{sites.length}</div>
              </div>
              <div
                style={{
                  padding: '10px 12px',
                  borderRadius: 10,
                  background: 'rgba(34,197,94,0.08)',
                  border: '1px solid rgba(34,197,94,0.16)',
                }}
              >
                <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 4 }}>검색 결과</div>
                <div style={{ fontSize: 18, fontWeight: 700, color: 'var(--text-primary)' }}>{filteredSites.length}</div>
              </div>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              {filteredSites.length > 0 ? (
                filteredSites.map((site) => {
                  const isSelected = site.id === selectedSiteId
                  return (
                    <motion.button
                      key={site.id}
                      whileHover={{ scale: 1.01 }}
                      whileTap={{ scale: 0.99 }}
                      onClick={() => setSelectedSiteId(site.id)}
                      style={{
                        width: '100%',
                        textAlign: 'left',
                        padding: 14,
                        borderRadius: 12,
                        border: isSelected ? '1px solid rgba(59,130,246,0.4)' : '1px solid var(--border)',
                        background: isSelected ? 'rgba(59,130,246,0.12)' : 'rgba(255,255,255,0.02)',
                        cursor: 'pointer',
                        transition: 'background 0.15s, border-color 0.15s',
                      }}
                    >
                      <div
                        style={{
                          display: 'flex',
                          alignItems: 'flex-start',
                          justifyContent: 'space-between',
                          gap: 10,
                          marginBottom: 10,
                        }}
                      >
                        <div>
                          <div
                            style={{
                              fontSize: 14,
                              fontWeight: 700,
                              color: 'var(--text-primary)',
                              marginBottom: 4,
                            }}
                          >
                            {site.siteName}
                          </div>
                          <div style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{site.client?.companyName ?? '-'}</div>
                        </div>
                        <InfoBadge color={STATUS_COLOR[site.status]}>{STATUS_LABEL[site.status]}</InfoBadge>
                      </div>

                      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--text-muted)' }}>
                          <CalendarDays size={13} strokeWidth={2} />
                          {site.startDate} ~ {site.endDate}
                        </div>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--text-secondary)' }}>
                          <TrendingUp size={13} strokeWidth={2} color="var(--text-muted)" />
                          {site.address || '주소 미등록'}
                        </div>
                      </div>
                    </motion.button>
                  )
                })
              ) : (
                <EmptyRelation message="검색 조건에 맞는 현장이 없습니다." />
              )}
            </div>
          </aside>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            {selectedSite ? (
              <>
                <motion.section
                  initial={{ opacity: 0, y: 8 }}
                  animate={{ opacity: 1, y: 0 }}
                  style={{
                    background:
                      'linear-gradient(135deg, rgba(59,130,246,0.18) 0%, rgba(15,23,42,0.92) 45%, rgba(9,9,11,1) 100%)',
                    border: '1px solid rgba(59,130,246,0.18)',
                    borderRadius: 16,
                    padding: 20,
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
                    <div>
                      <div style={{ fontSize: 12, color: 'rgba(255,255,255,0.6)', marginBottom: 8 }}>
                        선택된 현장
                      </div>
                      <div
                        style={{
                          fontSize: 26,
                          fontWeight: 800,
                          color: 'white',
                          letterSpacing: '-0.04em',
                          marginBottom: 8,
                        }}
                      >
                        {selectedSite.siteName}
                      </div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                        <InfoBadge color="#fafafa">{selectedSite.client?.companyName ?? '거래처 미지정'}</InfoBadge>
                        <InfoBadge color={STATUS_COLOR[selectedSite.status]}>{STATUS_LABEL[selectedSite.status]}</InfoBadge>
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
                      <div style={{ fontSize: 11, color: 'rgba(255,255,255,0.55)', marginBottom: 6 }}>진행 기간</div>
                      <div style={{ fontSize: 14, fontWeight: 600, color: 'white', marginBottom: 10 }}>
                        {selectedSite.startDate} ~ {selectedSite.endDate}
                      </div>
                      <div style={{ fontSize: 11, color: 'rgba(255,255,255,0.55)', marginBottom: 6 }}>현장 요약</div>
                      <div style={{ fontSize: 13, color: 'rgba(255,255,255,0.85)' }}>
                        선택한 현장의 견적, 세금계산서, 매입 내역을 현장 기준으로 묶어서 보고 있습니다.
                      </div>
                    </div>
                  </div>
                </motion.section>

                <div
                  style={{
                    display: 'grid',
                    gridTemplateColumns: 'repeat(4, minmax(0, 1fr))',
                    gap: 12,
                  }}
                >
                  <SummaryCard
                    icon={FileText}
                    title="견적서 합계"
                    value={formatCompactKRW(estimateTotal)}
                    description={`${siteEstimates.length}건의 견적서`}
                    color="#8b5cf6"
                  />
                  <SummaryCard
                    icon={Receipt}
                    title="세금계산서 합계"
                    value={formatCompactKRW(taxTotal)}
                    description={`${siteTaxes.length}건의 계산서`}
                    color="#3b82f6"
                  />
                  <SummaryCard
                    icon={ShoppingBag}
                    title="매입 합계"
                    value={formatCompactKRW(purchaseTotal)}
                    description={`${sitePurchases.length}건의 매입`}
                    color="#f59e0b"
                  />
                  <SummaryCard
                    icon={TrendingUp}
                    title="마진"
                    value={formatCompactKRW(estimateTotal - purchaseTotal)}
                    description={estimateTotal > 0 ? `마진율 ${((estimateTotal - purchaseTotal) / estimateTotal * 100).toFixed(1)}%` : '아직 산출 전'}
                    color={estimateTotal - purchaseTotal >= 0 ? '#22c55e' : '#ef4444'}
                  />
                </div>

                <RelationSection
                  icon={FileText}
                  title="견적서"
                  caption={`선택 현장 기준 ${siteEstimates.length}건`}
                >
                  {estimatesLoading ? (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                      {[1, 2].map((i) => (
                        <div key={i} className="shimmer" style={{ height: 74, borderRadius: 10 }} />
                      ))}
                    </div>
                  ) : siteEstimates.length > 0 ? (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                      {siteEstimates.map((estimate) => (
                        <div
                          key={estimate.id}
                          style={{
                            padding: 14,
                            borderRadius: 10,
                            border: '1px solid var(--border)',
                            background: 'rgba(255,255,255,0.02)',
                          }}
                        >
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
                        </div>
                      ))}
                    </div>
                  ) : (
                    <EmptyRelation message="이 현장에 연결된 견적서가 아직 없습니다." />
                  )}
                </RelationSection>

                <RelationSection
                  icon={Receipt}
                  title="세금계산서"
                  caption={`선택 현장 기준 ${siteTaxes.length}건 · 미수금 ${formatCompactKRW(unpaidTotal)}`}
                >
                  {taxesLoading ? (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                      {[1, 2].map((i) => (
                        <div key={i} className="shimmer" style={{ height: 74, borderRadius: 10 }} />
                      ))}
                    </div>
                  ) : siteTaxes.length > 0 ? (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                      {siteTaxes.map((invoice) => (
                        <div
                          key={invoice.id}
                          style={{
                            padding: 14,
                            borderRadius: 10,
                            border: '1px solid var(--border)',
                            background: 'rgba(255,255,255,0.02)',
                          }}
                        >
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
                              <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 4 }}>합계 금액</div>
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
                                {invoice.paymentConfirmed ? `입금일 ${invoice.paymentDate}` : '미입금'}
                              </div>
                            </div>
                          </div>
                        </div>
                      ))}
                    </div>
                  ) : (
                    <EmptyRelation message="이 현장에 연결된 세금계산서가 아직 없습니다." />
                  )}
                </RelationSection>

                <RelationSection
                  icon={ShoppingBag}
                  title="공내역 / 매입 내역"
                  caption={`선택 현장 기준 ${sitePurchases.length}건`}
                >
                  {purchasesLoading ? (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                      {[1, 2].map((i) => (
                        <div key={i} className="shimmer" style={{ height: 74, borderRadius: 10 }} />
                      ))}
                    </div>
                  ) : sitePurchases.length > 0 ? (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                      {sitePurchases.map((purchase) => (
                        <div
                          key={purchase.id}
                          style={{
                            padding: 14,
                            borderRadius: 10,
                            border: '1px solid var(--border)',
                            background: 'rgba(255,255,255,0.02)',
                          }}
                        >
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
                        </div>
                      ))}
                    </div>
                  ) : (
                    <EmptyRelation message="이 현장에 연결된 공내역 또는 매입 내역이 아직 없습니다." />
                  )}
                </RelationSection>
              </>
            ) : (
              <motion.div
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                style={{
                  background: 'var(--bg-card)',
                  border: '1px dashed var(--border-strong)',
                  borderRadius: 12,
                  padding: '40px 28px',
                  textAlign: 'center',
                }}
              >
                <div
                  style={{
                    width: 48,
                    height: 48,
                    borderRadius: 14,
                    background: 'rgba(59,130,246,0.12)',
                    border: '1px solid rgba(59,130,246,0.2)',
                    display: 'inline-flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    marginBottom: 14,
                  }}
                >
                  <Building2 size={22} color="var(--accent)" strokeWidth={2.2} />
                </div>
                <div style={{ fontSize: 18, fontWeight: 700, color: 'var(--text-primary)', marginBottom: 8 }}>
                  선택된 현장이 없습니다
                </div>
                <div style={{ fontSize: 13, color: 'var(--text-muted)' }}>
                  검색 결과에서 현장을 하나 선택하면 관련 견적서, 세금계산서, 매입 내역을 바로 확인할 수 있습니다.
                </div>
              </motion.div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
