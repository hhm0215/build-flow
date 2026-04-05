import { motion } from 'motion/react'
import {
  HardHat,
  TrendingUp,
  TrendingDown,
  DollarSign,
  FileText,
  Receipt,
  AlertCircle,
  ArrowUpRight,
  Activity,
  Layers,
} from 'lucide-react'

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

const stats: Omit<StatCardProps, 'index'>[] = [
  {
    title: '진행 중인 현장',
    value: '12',
    change: '지난달 대비 +2',
    changeType: 'up',
    icon: HardHat,
    accentColor: '#3b82f6',
  },
  {
    title: '이번달 매출',
    value: '₩84.2M',
    change: '지난달 대비 +18.4%',
    changeType: 'up',
    icon: DollarSign,
    accentColor: '#22c55e',
  },
  {
    title: '이번달 매입',
    value: '₩31.6M',
    change: '지난달 대비 +5.2%',
    changeType: 'down',
    icon: TrendingDown,
    accentColor: '#f59e0b',
  },
  {
    title: '미수금',
    value: '₩12.4M',
    change: '3건 미결제',
    changeType: 'neutral',
    icon: AlertCircle,
    accentColor: '#ef4444',
  },
  {
    title: '견적서',
    value: '28',
    change: '이번달 +6건',
    changeType: 'up',
    icon: FileText,
    accentColor: '#8b5cf6',
  },
  {
    title: '세금계산서',
    value: '19',
    change: '처리 완료 15건',
    changeType: 'up',
    icon: Receipt,
    accentColor: '#06b6d4',
  },
]

interface ActivityItem {
  id: number
  type: string
  title: string
  site: string
  time: string
  color: string
}

const recentActivity: ActivityItem[] = [
  { id: 1, type: '견적서', title: '삼성 물류창고 2차 공사 견적 확정', site: '삼성 물류센터', time: '방금 전', color: '#8b5cf6' },
  { id: 2, type: '매입', title: '철골 자재 구매 ₩4,200,000', site: '현대 오피스텔', time: '1시간 전', color: '#f59e0b' },
  { id: 3, type: '세금계산서', title: '매출 세금계산서 발행', site: 'LG 공장 배관', time: '3시간 전', color: '#22c55e' },
  { id: 4, type: '현장', title: '신규 현장 등록 — 롯데 쇼핑몰', site: '롯데 쇼핑몰', time: '어제', color: '#3b82f6' },
  { id: 5, type: '알림', title: '하자보증보험 만료 D-7', site: 'GS 아파트', time: '어제', color: '#ef4444' },
]

interface SiteRow {
  id: number
  name: string
  client: string
  progress: number
  margin: string
  status: string
  statusColor: string
}

const topSites: SiteRow[] = [
  { id: 1, name: '삼성 물류센터 용접', client: '삼성물산', progress: 75, margin: '32.4%', status: '시공 중', statusColor: '#3b82f6' },
  { id: 2, name: '현대 오피스텔 배관', client: '현대건설', progress: 45, margin: '28.1%', status: '시공 중', statusColor: '#3b82f6' },
  { id: 3, name: 'LG 공장 배관 공사', client: 'LG화학', progress: 92, margin: '41.2%', status: '마무리', statusColor: '#22c55e' },
  { id: 4, name: '롯데 쇼핑몰 철골', client: '롯데건설', progress: 10, margin: '—', status: '준비 중', statusColor: '#f59e0b' },
]

export default function DashboardPage() {
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

      {/* Stat cards */}
      <div style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(3, 1fr)',
        gap: 12,
      }}>
        {stats.map((stat, i) => (
          <StatCard key={stat.title} {...stat} index={i} />
        ))}
      </div>

      {/* Bottom section */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 340px', gap: 16 }}>
        {/* Top sites table */}
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
              <span style={{ fontSize: 14, fontWeight: 600, color: 'var(--text-primary)' }}>진행 중인 현장</span>
            </div>
            <button style={{
              display: 'flex', alignItems: 'center', gap: 4,
              fontSize: 12, color: 'var(--accent)', background: 'none', border: 'none', cursor: 'pointer', fontWeight: 500,
            }}>
              전체 보기 <ArrowUpRight size={12} strokeWidth={2.5} />
            </button>
          </div>

          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid var(--border)' }}>
                {['현장명', '발주처', '진행률', '마진율', '상태'].map((h) => (
                  <th key={h} style={{
                    padding: '10px 20px',
                    textAlign: 'left',
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
              {topSites.map((site, i) => (
                <motion.tr
                  key={site.id}
                  initial={{ opacity: 0, x: -8 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{ delay: 0.4 + i * 0.06 }}
                  style={{
                    borderBottom: i < topSites.length - 1 ? '1px solid var(--border)' : 'none',
                    transition: 'background 0.15s',
                  }}
                  onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.background = 'rgba(255,255,255,0.03)' }}
                  onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.background = 'transparent' }}
                >
                  <td style={{ padding: '13px 20px', fontSize: 13, color: 'var(--text-primary)', fontWeight: 500 }}>
                    {site.name}
                  </td>
                  <td style={{ padding: '13px 20px', fontSize: 13, color: 'var(--text-secondary)' }}>
                    {site.client}
                  </td>
                  <td style={{ padding: '13px 20px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <div style={{ flex: 1, height: 4, background: 'rgba(255,255,255,0.06)', borderRadius: 2, overflow: 'hidden', maxWidth: 80 }}>
                        <motion.div
                          initial={{ width: 0 }}
                          animate={{ width: `${site.progress}%` }}
                          transition={{ duration: 0.8, delay: 0.5 + i * 0.1, ease: 'easeOut' }}
                          style={{
                            height: '100%',
                            background: site.progress > 80 ? 'var(--success)' : 'var(--accent-gradient)',
                            borderRadius: 2,
                          }}
                        />
                      </div>
                      <span style={{ fontSize: 12, color: 'var(--text-muted)', width: 32 }}>{site.progress}%</span>
                    </div>
                  </td>
                  <td style={{ padding: '13px 20px', fontSize: 13, color: site.margin !== '—' ? 'var(--success)' : 'var(--text-muted)', fontWeight: 600 }}>
                    {site.margin}
                  </td>
                  <td style={{ padding: '13px 20px' }}>
                    <span style={{
                      display: 'inline-block',
                      fontSize: 11,
                      fontWeight: 600,
                      padding: '3px 8px',
                      borderRadius: 20,
                      background: `${site.statusColor}18`,
                      color: site.statusColor,
                      border: `1px solid ${site.statusColor}30`,
                    }}>
                      {site.status}
                    </span>
                  </td>
                </motion.tr>
              ))}
            </tbody>
          </table>
        </motion.div>

        {/* Activity feed */}
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
            gap: 8,
          }}>
            <Activity size={15} color="var(--text-muted)" strokeWidth={2} />
            <span style={{ fontSize: 14, fontWeight: 600, color: 'var(--text-primary)' }}>최근 활동</span>
          </div>
          <div style={{ padding: '8px 0', flex: 1, overflow: 'auto' }}>
            {recentActivity.map((item, i) => (
              <motion.div
                key={item.id}
                initial={{ opacity: 0, x: 8 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ delay: 0.45 + i * 0.07 }}
                style={{
                  padding: '12px 20px',
                  display: 'flex',
                  gap: 12,
                  cursor: 'default',
                  transition: 'background 0.15s',
                }}
                onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.background = 'rgba(255,255,255,0.03)' }}
                onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.background = 'transparent' }}
              >
                <div style={{
                  width: 7,
                  height: 7,
                  borderRadius: '50%',
                  background: item.color,
                  marginTop: 5,
                  flexShrink: 0,
                  boxShadow: `0 0 6px ${item.color}80`,
                }} />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 12, color: 'var(--text-primary)', fontWeight: 500, lineHeight: 1.4, marginBottom: 3 }}>
                    {item.title}
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <span style={{
                      fontSize: 11,
                      color: item.color,
                      background: `${item.color}15`,
                      padding: '1px 6px',
                      borderRadius: 4,
                      fontWeight: 500,
                    }}>
                      {item.type}
                    </span>
                    <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>{item.time}</span>
                  </div>
                </div>
              </motion.div>
            ))}
          </div>
        </motion.div>
      </div>
    </div>
  )
}
