import { motion } from 'motion/react'
import { HardHat, Plus, TrendingUp } from 'lucide-react'
import PageHeader from '../../components/PageHeader'
import { useSites } from '../../api/sites.api'
import { Site } from '../../types'

const STATUS_LABEL: Record<Site['status'], string> = {
  PREPARATION: '준비 중',
  IN_PROGRESS: '시공 중',
  FINISHING: '마무리',
  COMPLETED: '완료',
}
const STATUS_COLOR: Record<Site['status'], string> = {
  PREPARATION: '#f59e0b',
  IN_PROGRESS: '#3b82f6',
  FINISHING: '#22c55e',
  COMPLETED: '#52525b',
}

function formatKRW(n: number) {
  if (n === 0) return '—'
  return `₩${(n / 1_000_000).toFixed(1)}M`
}

export default function SiteListPage() {
  // ─────────────────────────────────────────────
  // useSites() 호출 한 번으로:
  //   - 컴포넌트 마운트 시 자동 fetch
  //   - 로딩/에러/데이터 상태 관리
  //   - 5분 staleTime 캐싱 (동일 queryKey 재요청 안 함)
  // ─────────────────────────────────────────────
  const { data, isLoading } = useSites()
  const sites = data?.content ?? []

  return (
    <div>
      <PageHeader
        icon={HardHat}
        title="현장 관리"
        description="등록된 현장 목록 및 손익 현황"
        action={
          <motion.button
            whileHover={{ scale: 1.02 }}
            whileTap={{ scale: 0.98 }}
            style={{
              display: 'flex', alignItems: 'center', gap: 6,
              padding: '8px 16px',
              background: 'var(--accent-gradient)',
              border: 'none', borderRadius: 'var(--radius-sm)',
              color: 'white', fontSize: 13, fontWeight: 600, cursor: 'pointer',
              boxShadow: '0 0 16px rgba(59,130,246,0.2)',
            }}
          >
            <Plus size={14} strokeWidth={2.5} />
            현장 추가
          </motion.button>
        }
      />

      {isLoading ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {[1, 2, 3].map((i) => (
            <div key={i} className="shimmer" style={{ height: 64, borderRadius: 10 }} />
          ))}
        </div>
      ) : (
        <motion.div
          style={{
            background: 'var(--bg-card)',
            border: '1px solid var(--border)',
            borderRadius: 12,
            overflow: 'hidden',
          }}
        >
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid var(--border)' }}>
                {['현장명', '발주처', '상태', '매출', '매입', '마진율'].map((h) => (
                  <th key={h} style={{
                    padding: '11px 20px', textAlign: 'left',
                    fontSize: 11, fontWeight: 600, color: 'var(--text-muted)',
                    letterSpacing: '0.05em', textTransform: 'uppercase',
                  }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {sites.map((site, i) => (
                <motion.tr
                  key={site.id}
                  initial={{ opacity: 0, x: -8 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{ delay: i * 0.05 }}
                  style={{ borderBottom: i < sites.length - 1 ? '1px solid var(--border)' : 'none', cursor: 'pointer' }}
                  onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.background = 'rgba(255,255,255,0.03)' }}
                  onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.background = 'transparent' }}
                >
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-primary)', fontWeight: 500 }}>
                    {site.name}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-secondary)' }}>
                    {site.client}
                  </td>
                  <td style={{ padding: '14px 20px' }}>
                    <span style={{
                      fontSize: 11, fontWeight: 600, padding: '3px 8px',
                      borderRadius: 20,
                      background: `${STATUS_COLOR[site.status]}18`,
                      color: STATUS_COLOR[site.status],
                      border: `1px solid ${STATUS_COLOR[site.status]}30`,
                    }}>
                      {STATUS_LABEL[site.status]}
                    </span>
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-secondary)' }}>
                    {formatKRW(site.totalRevenue)}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-secondary)' }}>
                    {formatKRW(site.totalCost)}
                  </td>
                  <td style={{ padding: '14px 20px' }}>
                    {site.marginRate > 0 ? (
                      <div style={{ display: 'flex', alignItems: 'center', gap: 4, color: 'var(--success)', fontSize: 13, fontWeight: 600 }}>
                        <TrendingUp size={12} strokeWidth={2.5} />
                        {site.marginRate.toFixed(1)}%
                      </div>
                    ) : (
                      <span style={{ color: 'var(--text-muted)', fontSize: 13 }}>—</span>
                    )}
                  </td>
                </motion.tr>
              ))}
            </tbody>
          </table>
        </motion.div>
      )}
    </div>
  )
}
