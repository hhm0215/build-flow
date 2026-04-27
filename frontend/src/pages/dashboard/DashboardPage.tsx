import { motion } from 'motion/react'
import {
  HardHat,
  TrendingUp,
  TrendingDown,
  DollarSign,
  ShoppingCart,
  ArrowUpRight,
  Activity,
  Layers,
  Percent,
  Sparkles,
  Loader2,
} from 'lucide-react'
import ReactMarkdown from 'react-markdown'
import { useDashboardStats, useDashboardSummary } from '../../api/dashboard.api'
import type { SiteProfitSummary } from '../../types'

// ── Helpers ──────────────────────────────────────

const STATUS_LABEL: Record<string, string> = {
  IN_PROGRESS: '시공 중',
  SETTLING: '정산 중',
  WARRANTY: '하자보증',
  COMPLETED: '완료',
}

const STATUS_COLOR: Record<string, string> = {
  IN_PROGRESS: '#3b82f6',
  SETTLING: '#f59e0b',
  WARRANTY: '#8b5cf6',
  COMPLETED: '#22c55e',
}

function formatAmount(value: number): string {
  const abs = Math.abs(value)
  if (abs >= 1_0000_0000) return `${(value / 1_0000_0000).toFixed(1)}억`
  if (abs >= 1_0000) return `${(value / 1_0000).toFixed(0)}만`
  return value.toLocaleString()
}

function formatAmountTable(value: number): string {
  const abs = Math.abs(value)
  if (abs >= 1_0000_0000) return `${(value / 1_0000_0000).toFixed(2)}억`
  if (abs >= 1_0000) return `${(value / 1_0000).toFixed(0)}만`
  return value.toLocaleString()
}

// ── Skeleton ─────────────────────────────────────

function SkeletonBlock({ width, height = 16 }: { width: number | string; height?: number }) {
  return (
    <motion.div
      animate={{ opacity: [0.3, 0.6, 0.3] }}
      transition={{ duration: 1.5, repeat: Infinity, ease: 'easeInOut' }}
      style={{
        width,
        height,
        borderRadius: 6,
        background: 'rgba(255,255,255,0.06)',
      }}
    />
  )
}

function StatCardSkeleton({ index }: { index: number }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.4, delay: index * 0.07 }}
      style={{
        background: 'var(--bg-card)',
        border: '1px solid var(--border)',
        borderRadius: 12,
        padding: 20,
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 10 }}>
          <SkeletonBlock width={80} height={12} />
          <SkeletonBlock width={100} height={26} />
          <SkeletonBlock width={60} height={12} />
        </div>
        <SkeletonBlock width={40} height={40} />
      </div>
    </motion.div>
  )
}

// ── StatCard ─────────────────────────────────────

interface StatCardProps {
  title: string
  value: string
  change: string
  changeType: 'up' | 'down' | 'neutral'
  icon: React.ElementType
  accentColor: string
  index: number
}

function StatCard({ title, value, change, changeType, icon: Icon, accentColor, index }: StatCardProps) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.4, delay: index * 0.07, ease: [0.25, 0.46, 0.45, 0.94] }}
      whileHover={{ y: -2, transition: { duration: 0.15 } }}
      style={{
        background: 'var(--bg-card)',
        border: '1px solid var(--border)',
        borderRadius: 12,
        padding: 20,
        cursor: 'default',
        position: 'relative',
        overflow: 'hidden',
        transition: 'border-color 0.2s, box-shadow 0.2s',
      }}
      onMouseEnter={(e) => {
        const el = e.currentTarget as HTMLElement
        el.style.borderColor = 'rgba(255,255,255,0.14)'
        el.style.boxShadow = `0 8px 32px rgba(0,0,0,0.3), 0 0 0 1px ${accentColor}22`
      }}
      onMouseLeave={(e) => {
        const el = e.currentTarget as HTMLElement
        el.style.borderColor = 'var(--border)'
        el.style.boxShadow = 'none'
      }}
    >
      {/* Gradient accent top-right */}
      <div style={{
        position: 'absolute',
        top: 0,
        right: 0,
        width: 100,
        height: 100,
        background: `radial-gradient(circle at top right, ${accentColor}18 0%, transparent 70%)`,
        pointerEvents: 'none',
      }} />

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <div style={{ flex: 1 }}>
          <div style={{ fontSize: 12, color: 'var(--text-muted)', fontWeight: 500, marginBottom: 8, letterSpacing: '0.02em' }}>
            {title}
          </div>
          <div style={{ fontSize: 26, fontWeight: 700, color: 'var(--text-primary)', letterSpacing: '-0.04em', lineHeight: 1 }}>
            {value}
          </div>
          {change && (
            <div style={{
              display: 'flex',
              alignItems: 'center',
              gap: 4,
              marginTop: 10,
              color: changeType === 'up' ? 'var(--success)' : changeType === 'down' ? 'var(--danger)' : 'var(--text-muted)',
              fontSize: 12,
              fontWeight: 500,
            }}>
              {changeType === 'up' && <TrendingUp size={12} strokeWidth={2.5} />}
              {changeType === 'down' && <TrendingDown size={12} strokeWidth={2.5} />}
              {change}
            </div>
          )}
        </div>
        <div style={{
          width: 40,
          height: 40,
          borderRadius: 10,
          background: `${accentColor}18`,
          border: `1px solid ${accentColor}30`,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          flexShrink: 0,
        }}>
          <Icon size={18} color={accentColor} strokeWidth={2} />
        </div>
      </div>
    </motion.div>
  )
}

// ── Helpers: build stat cards from API data ──────

function buildStatCards(stats: {
  sitesByStatus: Record<string, number>
  totalEstimateAmount: number
  totalPurchaseAmount: number
  totalMargin: number
  averageMarginRate: number
  totalSites: number
}): Omit<StatCardProps, 'index'>[] {
  const inProgress = stats.sitesByStatus['IN_PROGRESS'] || 0

  return [
    {
      title: '진행 중인 현장',
      value: `${inProgress}`,
      change: `전체 ${stats.totalSites}개 현장`,
      changeType: 'neutral',
      icon: HardHat,
      accentColor: '#3b82f6',
    },
    {
      title: '총 매출 (견적)',
      value: `\u20A9${formatAmount(stats.totalEstimateAmount)}`,
      change: `${stats.totalSites}개 현장 합산`,
      changeType: 'neutral',
      icon: DollarSign,
      accentColor: '#22c55e',
    },
    {
      title: '총 매입',
      value: `\u20A9${formatAmount(stats.totalPurchaseAmount)}`,
      change: `매출 대비 ${stats.totalEstimateAmount > 0 ? ((stats.totalPurchaseAmount / stats.totalEstimateAmount) * 100).toFixed(0) : 0}%`,
      changeType: 'neutral',
      icon: ShoppingCart,
      accentColor: '#f59e0b',
    },
    {
      title: '총 마진',
      value: `\u20A9${formatAmount(stats.totalMargin)}`,
      change: stats.totalMargin >= 0 ? '흑자' : '적자',
      changeType: stats.totalMargin >= 0 ? 'up' : 'down',
      icon: TrendingUp,
      accentColor: stats.totalMargin >= 0 ? '#22c55e' : '#ef4444',
    },
    {
      title: '평균 마진율',
      value: `${stats.averageMarginRate.toFixed(1)}%`,
      change: stats.averageMarginRate >= 20 ? '양호' : '주의',
      changeType: stats.averageMarginRate >= 20 ? 'up' : 'down',
      icon: Percent,
      accentColor: '#8b5cf6',
    },
  ]
}

// ── Main Component ───────────────────────────────

export default function DashboardPage() {
  const { data: stats, isLoading: statsLoading } = useDashboardStats()
  const { data: summary, isLoading: summaryLoading } = useDashboardSummary()

  const statCards = stats ? buildStatCards(stats) : []
  const siteProfits: SiteProfitSummary[] = stats?.siteProfits ?? []

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
      {/* Header */}
      <motion.div
        initial={{ opacity: 0, y: -8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.3 }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 4 }}>
          <Activity size={18} color="var(--accent)" strokeWidth={2.5} />
          <h1 style={{ fontSize: 20, fontWeight: 700, color: 'var(--text-primary)', letterSpacing: '-0.03em' }}>
            대시보드
          </h1>
        </div>
        <p style={{ fontSize: 13, color: 'var(--text-muted)' }}>현장 현황 및 손익 요약</p>
      </motion.div>

      {/* Stat cards — 상단 3개 + 하단 2개 균등 배치 */}
      <div style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(6, 1fr)',
        gap: 12,
      }}>
        {statsLoading
          ? Array.from({ length: 5 }).map((_, i) => (
              <div key={i} style={{ gridColumn: i < 3 ? 'span 2' : 'span 3' }}>
                <StatCardSkeleton index={i} />
              </div>
            ))
          : statCards.map((stat, i) => (
              <div key={stat.title} style={{ gridColumn: i < 3 ? 'span 2' : 'span 3' }}>
                <StatCard {...stat} index={i} />
              </div>
            ))
        }
      </div>

      {/* Bottom section */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 340px', gap: 16 }}>
        {/* Site profits table */}
        <motion.div
          initial={{ opacity: 0, y: 16 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.4, delay: 0.35 }}
          style={{
            background: 'var(--bg-card)',
            border: '1px solid var(--border)',
            borderRadius: 12,
            overflow: 'hidden',
          }}
        >
          <div style={{
            padding: '16px 20px',
            borderBottom: '1px solid var(--border)',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
          }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <Layers size={15} color="var(--text-muted)" strokeWidth={2} />
              <span style={{ fontSize: 14, fontWeight: 600, color: 'var(--text-primary)' }}>현장별 손익</span>
            </div>
            <button style={{
              display: 'flex', alignItems: 'center', gap: 4,
              fontSize: 12, color: 'var(--accent)', background: 'none', border: 'none', cursor: 'pointer', fontWeight: 500,
            }}>
              전체 보기 <ArrowUpRight size={12} strokeWidth={2.5} />
            </button>
          </div>

          {statsLoading ? (
            <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 16 }}>
              {Array.from({ length: 4 }).map((_, i) => (
                <SkeletonBlock key={i} width="100%" height={20} />
              ))}
            </div>
          ) : siteProfits.length === 0 ? (
            <div style={{ padding: 40, textAlign: 'center', color: 'var(--text-muted)', fontSize: 13 }}>
              등록된 현장이 없습니다
            </div>
          ) : (
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr style={{ borderBottom: '1px solid var(--border)' }}>
                  {['현장명', '상태', '견적액', '매입액', '마진', '마진율'].map((h) => (
                    <th key={h} style={{
                      padding: '10px 16px',
                      textAlign: h === '현장명' || h === '상태' ? 'left' : 'right',
                      fontSize: 11,
                      fontWeight: 600,
                      color: 'var(--text-muted)',
                      letterSpacing: '0.04em',
                      textTransform: 'uppercase',
                    }}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {siteProfits.map((site, i) => {
                  const statusColor = STATUS_COLOR[site.status] || '#6b7280'
                  const statusLabel = STATUS_LABEL[site.status] || site.status

                  return (
                    <motion.tr
                      key={site.siteId}
                      initial={{ opacity: 0, x: -8 }}
                      animate={{ opacity: 1, x: 0 }}
                      transition={{ delay: 0.4 + i * 0.06 }}
                      style={{
                        borderBottom: i < siteProfits.length - 1 ? '1px solid var(--border)' : 'none',
                        transition: 'background 0.15s',
                      }}
                      onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.background = 'rgba(255,255,255,0.03)' }}
                      onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.background = 'transparent' }}
                    >
                      <td style={{ padding: '13px 16px', fontSize: 13, color: 'var(--text-primary)', fontWeight: 500 }}>
                        {site.siteName}
                      </td>
                      <td style={{ padding: '13px 16px' }}>
                        <span style={{
                          display: 'inline-block',
                          fontSize: 11,
                          fontWeight: 600,
                          padding: '3px 8px',
                          borderRadius: 20,
                          background: `${statusColor}18`,
                          color: statusColor,
                          border: `1px solid ${statusColor}30`,
                        }}>
                          {statusLabel}
                        </span>
                      </td>
                      <td style={{ padding: '13px 16px', fontSize: 13, color: 'var(--text-secondary)', textAlign: 'right' }}>
                        {formatAmountTable(site.estimateAmount)}
                      </td>
                      <td style={{ padding: '13px 16px', fontSize: 13, color: 'var(--text-secondary)', textAlign: 'right' }}>
                        {formatAmountTable(site.purchaseAmount)}
                      </td>
                      <td style={{
                        padding: '13px 16px',
                        fontSize: 13,
                        color: site.margin >= 0 ? 'var(--success)' : 'var(--danger)',
                        fontWeight: 600,
                        textAlign: 'right',
                      }}>
                        {formatAmountTable(site.margin)}
                      </td>
                      <td style={{
                        padding: '13px 16px',
                        fontSize: 13,
                        color: site.marginRate >= 20 ? 'var(--success)' : site.marginRate >= 0 ? 'var(--text-muted)' : 'var(--danger)',
                        fontWeight: 600,
                        textAlign: 'right',
                      }}>
                        {site.marginRate.toFixed(1)}%
                      </td>
                    </motion.tr>
                  )
                })}
              </tbody>
            </table>
          )}
        </motion.div>

        {/* AI Summary */}
        <motion.div
          initial={{ opacity: 0, y: 16 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.4, delay: 0.4 }}
          style={{
            background: 'var(--bg-card)',
            border: '1px solid var(--border)',
            borderRadius: 12,
            overflow: 'hidden',
            display: 'flex',
            flexDirection: 'column',
          }}
        >
          <div style={{
            padding: '16px 20px',
            borderBottom: '1px solid var(--border)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
          }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <Sparkles size={15} color="#8b5cf6" strokeWidth={2} />
              <span style={{ fontSize: 14, fontWeight: 600, color: 'var(--text-primary)' }}>AI 요약</span>
            </div>
            <span style={{
              fontSize: 10,
              color: 'var(--text-muted)',
              background: 'rgba(139,92,246,0.12)',
              padding: '2px 8px',
              borderRadius: 4,
              fontWeight: 500,
            }}>
              Ollama
            </span>
          </div>

          <div style={{ padding: 20, flex: 1, overflow: 'auto' }}>
            {summaryLoading ? (
              <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 12, padding: '32px 0' }}>
                <motion.div
                  animate={{ rotate: 360 }}
                  transition={{ duration: 1.2, repeat: Infinity, ease: 'linear' }}
                >
                  <Loader2 size={20} color="var(--text-muted)" strokeWidth={2} />
                </motion.div>
                <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>
                  AI 요약을 생성 중입니다...
                </span>
              </div>
            ) : summary?.summary ? (
              <div>
                <div style={{ fontSize: 13, color: 'var(--text-secondary)', lineHeight: 1.7 }}
                  className="dashboard-markdown"
                >
                  <ReactMarkdown
                    components={{
                      h2: ({ children }) => (
                        <h2 style={{ fontSize: 14, fontWeight: 700, color: 'var(--text-primary)', margin: '16px 0 6px' }}>{children}</h2>
                      ),
                      h3: ({ children }) => (
                        <h3 style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-primary)', margin: '12px 0 4px' }}>{children}</h3>
                      ),
                      p: ({ children }) => (
                        <p style={{ margin: '4px 0', lineHeight: 1.7 }}>{children}</p>
                      ),
                      ul: ({ children }) => (
                        <ul style={{ margin: '4px 0', paddingLeft: 16 }}>{children}</ul>
                      ),
                      li: ({ children }) => (
                        <li style={{ margin: '2px 0' }}>{children}</li>
                      ),
                    }}
                  >
                    {summary.summary}
                  </ReactMarkdown>
                </div>
                {summary.generatedAt && (
                  <div style={{
                    marginTop: 16,
                    paddingTop: 12,
                    borderTop: '1px solid var(--border)',
                    fontSize: 11,
                    color: 'var(--text-muted)',
                  }}>
                    {new Date(summary.generatedAt).toLocaleString('ko-KR')} 기준
                  </div>
                )}
              </div>
            ) : (
              <div style={{
                textAlign: 'center',
                padding: '32px 0',
                color: 'var(--text-muted)',
                fontSize: 13,
              }}>
                요약 데이터가 없습니다
              </div>
            )}
          </div>
        </motion.div>
      </div>
    </div>
  )
}
