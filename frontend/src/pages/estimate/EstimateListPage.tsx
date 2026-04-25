import { motion } from 'motion/react'
import { FileText, Plus } from 'lucide-react'
import PageHeader from '../../components/PageHeader'
import { useEstimates } from '../../api/estimates.api'
import { EstimateStatus } from '../../types'

const STATUS_LABEL: Record<EstimateStatus, string> = {
  DRAFT: '작성 중',
  CONFIRMED: '확정',
}
const STATUS_COLOR: Record<EstimateStatus, string> = {
  DRAFT: '#71717a',
  CONFIRMED: '#22c55e',
}

function formatKRW(n: number) {
  return `₩${n.toLocaleString('ko-KR')}`
}

export default function EstimateListPage() {
  const { data, isLoading } = useEstimates()
  const estimates = data ?? []

  return (
    <div>
      <PageHeader
        icon={FileText}
        title="견적서"
        description="공내역서 AI 파싱 및 견적서 관리"
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
            견적서 작성
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
                {['제목', '상태', '총액', '항목 수', '견적일'].map((h) => (
                  <th key={h} style={{
                    padding: '11px 20px', textAlign: 'left',
                    fontSize: 11, fontWeight: 600, color: 'var(--text-muted)',
                    letterSpacing: '0.05em', textTransform: 'uppercase',
                  }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {estimates.map((est, i) => (
                <motion.tr
                  key={est.id}
                  initial={{ opacity: 0, x: -8 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{ delay: i * 0.05 }}
                  style={{ borderBottom: i < estimates.length - 1 ? '1px solid var(--border)' : 'none', cursor: 'pointer' }}
                  onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.background = 'rgba(255,255,255,0.03)' }}
                  onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.background = 'transparent' }}
                >
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-primary)', fontWeight: 500 }}>
                    {est.title}
                  </td>
                  <td style={{ padding: '14px 20px' }}>
                    <span style={{
                      fontSize: 11, fontWeight: 600, padding: '3px 8px',
                      borderRadius: 20,
                      background: `${STATUS_COLOR[est.status]}18`,
                      color: STATUS_COLOR[est.status],
                      border: `1px solid ${STATUS_COLOR[est.status]}30`,
                    }}>
                      {STATUS_LABEL[est.status]}
                    </span>
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-primary)', fontWeight: 600 }}>
                    {est.totalAmount > 0 ? formatKRW(est.totalAmount) : '—'}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-secondary)' }}>
                    {est.items.length}건
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 12, color: 'var(--text-muted)' }}>
                    {est.estimateDate}
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
