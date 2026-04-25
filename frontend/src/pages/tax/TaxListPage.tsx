import { motion } from 'motion/react'
import { Receipt, Plus, CheckCircle } from 'lucide-react'
import PageHeader from '../../components/PageHeader'
import { useTaxes, useConfirmPayment } from '../../api/taxes.api'

export default function TaxListPage() {
  const { data, isLoading } = useTaxes()
  const { mutate: confirmPayment } = useConfirmPayment()
  const invoices = data ?? []

  const unpaidTotal = invoices
    .filter((t) => !t.paymentConfirmed && t.type === 'SALES')
    .reduce((sum, t) => sum + t.totalAmount, 0)

  return (
    <div>
      <PageHeader
        icon={Receipt}
        title="세금계산서"
        description="매출·매입 세금계산서 및 미수금 추적"
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
            세금계산서 등록
          </motion.button>
        }
      />

      {!isLoading && unpaidTotal > 0 && (
        <motion.div
          initial={{ opacity: 0, y: -8 }}
          animate={{ opacity: 1, y: 0 }}
          style={{
            background: 'rgba(239,68,68,0.06)',
            border: '1px solid rgba(239,68,68,0.2)',
            borderRadius: 10,
            padding: '12px 20px',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            marginBottom: 12,
          }}
        >
          <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
            미수금 합계 ({invoices.filter((t) => !t.paymentConfirmed && t.type === 'SALES').length}건)
          </span>
          <span style={{ fontSize: 18, fontWeight: 700, color: '#ef4444', letterSpacing: '-0.03em' }}>
            ₩{unpaidTotal.toLocaleString('ko-KR')}
          </span>
        </motion.div>
      )}

      {isLoading ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {[1, 2, 3].map((i) => (
            <div key={i} className="shimmer" style={{ height: 64, borderRadius: 10 }} />
          ))}
        </div>
      ) : (
        <motion.div
          style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, overflow: 'hidden' }}
        >
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid var(--border)' }}>
                {['구분', '거래처', '공급가', '세액', '합계', '입금 상태', '처리'].map((h) => (
                  <th key={h} style={{
                    padding: '11px 20px', textAlign: 'left',
                    fontSize: 11, fontWeight: 600, color: 'var(--text-muted)',
                    letterSpacing: '0.05em', textTransform: 'uppercase',
                  }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {invoices.map((inv, i) => (
                <motion.tr
                  key={inv.id}
                  initial={{ opacity: 0, x: -8 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{ delay: i * 0.05 }}
                  style={{ borderBottom: i < invoices.length - 1 ? '1px solid var(--border)' : 'none' }}
                  onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.background = 'rgba(255,255,255,0.03)' }}
                  onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.background = 'transparent' }}
                >
                  <td style={{ padding: '14px 20px' }}>
                    <span style={{
                      fontSize: 11, fontWeight: 600, padding: '3px 8px',
                      borderRadius: 20,
                      background: inv.type === 'SALES' ? 'rgba(34,197,94,0.1)' : 'rgba(239,68,68,0.1)',
                      color: inv.type === 'SALES' ? '#22c55e' : '#ef4444',
                      border: `1px solid ${inv.type === 'SALES' ? 'rgba(34,197,94,0.2)' : 'rgba(239,68,68,0.2)'}`,
                    }}>
                      {inv.type === 'SALES' ? '매출' : '매입'}
                    </span>
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-primary)', fontWeight: 500 }}>
                    {inv.counterparty}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-secondary)' }}>
                    ₩{inv.supplyAmount.toLocaleString('ko-KR')}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-secondary)' }}>
                    ₩{inv.taxAmount.toLocaleString('ko-KR')}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-primary)', fontWeight: 600 }}>
                    ₩{inv.totalAmount.toLocaleString('ko-KR')}
                  </td>
                  <td style={{ padding: '14px 20px' }}>
                    <span style={{
                      fontSize: 11, fontWeight: 600, padding: '3px 8px',
                      borderRadius: 20,
                      background: inv.paymentConfirmed ? 'rgba(34,197,94,0.1)' : 'rgba(245,158,11,0.1)',
                      color: inv.paymentConfirmed ? '#22c55e' : '#f59e0b',
                      border: `1px solid ${inv.paymentConfirmed ? 'rgba(34,197,94,0.2)' : 'rgba(245,158,11,0.2)'}`,
                    }}>
                      {inv.paymentConfirmed ? '입금 완료' : '미입금'}
                    </span>
                  </td>
                  <td style={{ padding: '14px 20px' }}>
                    {!inv.paymentConfirmed && inv.type === 'SALES' && (
                      <motion.button
                        whileHover={{ scale: 1.05 }}
                        whileTap={{ scale: 0.95 }}
                        onClick={() => confirmPayment(inv.id)}
                        style={{
                          display: 'flex', alignItems: 'center', gap: 4,
                          fontSize: 11, fontWeight: 600,
                          padding: '4px 10px', borderRadius: 6,
                          background: 'rgba(34,197,94,0.1)',
                          border: '1px solid rgba(34,197,94,0.2)',
                          color: '#22c55e', cursor: 'pointer',
                        }}
                      >
                        <CheckCircle size={11} strokeWidth={2.5} />
                        입금 확인
                      </motion.button>
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
