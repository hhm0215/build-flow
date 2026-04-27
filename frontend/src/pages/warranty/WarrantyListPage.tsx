import { motion } from 'motion/react'
import { ShieldCheck, Plus, Trash2, AlertTriangle } from 'lucide-react'
import PageHeader from '../../components/PageHeader'
import { useWarranties, useExpiringWarranties, useDeleteWarranty } from '../../api/warranties.api'

export default function WarrantyListPage() {
  const { data, isLoading } = useWarranties()
  const { data: expiringData } = useExpiringWarranties(30)
  const { mutate: deleteWarranty } = useDeleteWarranty()
  const warranties = data ?? []
  const expiringCount = expiringData?.length ?? 0

  const getExpiryColor = (days: number, expired: boolean) => {
    if (expired) return '#ef4444'
    if (days <= 7) return '#ef4444'
    if (days <= 30) return '#f59e0b'
    return '#22c55e'
  }

  const getExpiryLabel = (days: number, expired: boolean) => {
    if (expired) return `D+${Math.abs(days)}`
    return `D-${days}`
  }

  return (
    <div>
      <PageHeader
        icon={ShieldCheck}
        title="하자보증보험"
        description="보증보험 관리 및 만료 추적"
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
            보험 등록
          </motion.button>
        }
      />

      {!isLoading && expiringCount > 0 && (
        <motion.div
          initial={{ opacity: 0, y: -8 }}
          animate={{ opacity: 1, y: 0 }}
          style={{
            background: 'rgba(245,158,11,0.06)',
            border: '1px solid rgba(245,158,11,0.2)',
            borderRadius: 10,
            padding: '12px 20px',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            marginBottom: 12,
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <AlertTriangle size={15} color="#f59e0b" strokeWidth={2.5} />
            <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
              30일 이내 만료 예정 보험
            </span>
          </div>
          <span style={{ fontSize: 18, fontWeight: 700, color: '#f59e0b', letterSpacing: '-0.03em' }}>
            {expiringCount}건
          </span>
        </motion.div>
      )}

      {isLoading ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {[1, 2, 3].map((i) => (
            <div key={i} className="shimmer" style={{ height: 64, borderRadius: 10 }} />
          ))}
        </div>
      ) : warranties.length === 0 ? (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          style={{
            textAlign: 'center',
            padding: '48px 0',
            color: 'var(--text-muted)',
            fontSize: 14,
          }}
        >
          등록된 보증보험이 없습니다.
        </motion.div>
      ) : (
        <motion.div
          style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, overflow: 'hidden' }}
        >
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid var(--border)' }}>
                {['보험사', '증권번호', '보증금액', '보증기간', '만료까지', '상태', '처리'].map((h) => (
                  <th key={h} style={{
                    padding: '11px 20px', textAlign: 'left',
                    fontSize: 11, fontWeight: 600, color: 'var(--text-muted)',
                    letterSpacing: '0.05em', textTransform: 'uppercase',
                  }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {warranties.map((w, i) => (
                <motion.tr
                  key={w.id}
                  initial={{ opacity: 0, x: -8 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{ delay: i * 0.05 }}
                  style={{ borderBottom: i < warranties.length - 1 ? '1px solid var(--border)' : 'none' }}
                  onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.background = 'rgba(255,255,255,0.03)' }}
                  onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.background = 'transparent' }}
                >
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-primary)', fontWeight: 500 }}>
                    {w.insuranceCompany}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-secondary)', fontFamily: 'monospace' }}>
                    {w.policyNumber}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-primary)', fontWeight: 600 }}>
                    ₩{w.coverageAmount.toLocaleString('ko-KR')}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 12, color: 'var(--text-secondary)' }}>
                    {w.startDate} ~ {w.endDate}
                  </td>
                  <td style={{ padding: '14px 20px' }}>
                    <span style={{
                      fontSize: 12, fontWeight: 700,
                      color: getExpiryColor(w.daysUntilExpiry, w.expired),
                    }}>
                      {getExpiryLabel(w.daysUntilExpiry, w.expired)}
                    </span>
                  </td>
                  <td style={{ padding: '14px 20px' }}>
                    <span style={{
                      fontSize: 11, fontWeight: 600, padding: '3px 8px',
                      borderRadius: 20,
                      background: w.expired ? 'rgba(239,68,68,0.1)' : 'rgba(34,197,94,0.1)',
                      color: w.expired ? '#ef4444' : '#22c55e',
                      border: `1px solid ${w.expired ? 'rgba(239,68,68,0.2)' : 'rgba(34,197,94,0.2)'}`,
                    }}>
                      {w.expired ? '만료' : '유효'}
                    </span>
                  </td>
                  <td style={{ padding: '14px 20px' }}>
                    <motion.button
                      whileHover={{ scale: 1.05 }}
                      whileTap={{ scale: 0.95 }}
                      onClick={() => { if (window.confirm('정말 삭제하시겠습니까?')) deleteWarranty(w.id) }}
                      style={{
                        display: 'flex', alignItems: 'center', gap: 4,
                        fontSize: 11, fontWeight: 600,
                        padding: '4px 10px', borderRadius: 6,
                        background: 'rgba(239,68,68,0.1)',
                        border: '1px solid rgba(239,68,68,0.2)',
                        color: '#ef4444', cursor: 'pointer',
                      }}
                    >
                      <Trash2 size={11} strokeWidth={2.5} />
                      삭제
                    </motion.button>
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
