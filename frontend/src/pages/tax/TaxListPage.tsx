import { useMemo, useState } from 'react'
import { motion } from 'motion/react'
import { Receipt, Plus, Wallet, Pencil, Trash2 } from 'lucide-react'
import SiteNameCell from '../../components/SiteNameCell'
import PageHeader from '../../components/PageHeader'
import ErrorState from '../../components/ErrorState'
import FilterBar from '../../components/filters/FilterBar'
import FilterSearch from '../../components/filters/FilterSearch'
import FilterSelect from '../../components/filters/FilterSelect'
import FilterDateRange from '../../components/filters/FilterDateRange'
import FilterAmountRange from '../../components/filters/FilterAmountRange'
import { FilterSchema } from '../../hooks/useFilterParams'
import { useListFilters } from '../../hooks/useListFilters'
import { useTaxes } from '../../api/taxes.api'
import { useSites } from '../../api/sites.api'
import type { TaxInvoice, TaxInvoiceType, TaxPaymentStatus } from '../../types'
import TaxPaymentsModal from './TaxPaymentsModal'
import TaxEditModal from './TaxEditModal'
import TaxDeleteModal from './TaxDeleteModal'
import TaxCreateModal from './TaxCreateModal'
import { buildSiteDisplayMap } from '../../utils/siteName'
import { PAYMENT_STATUS_META, hasActivePayment, paymentStatusOf, remainingOf } from '../../utils/taxPayment'

type PaymentStatus = TaxPaymentStatus

const TYPE_OPTIONS = [
  { value: 'SALES' as const, label: '매출', color: '#22c55e' },
  { value: 'PURCHASE' as const, label: '매입', color: '#ef4444' },
] as const

const PAYMENT_OPTIONS = [
  { value: 'PAID' as const, label: PAYMENT_STATUS_META.PAID.label, color: PAYMENT_STATUS_META.PAID.color },
  { value: 'PARTIAL' as const, label: PAYMENT_STATUS_META.PARTIAL.label, color: PAYMENT_STATUS_META.PARTIAL.color },
  { value: 'UNPAID' as const, label: PAYMENT_STATUS_META.UNPAID.label, color: PAYMENT_STATUS_META.UNPAID.color },
] as const

interface TaxFilters {
  q: string
  type: TaxInvoiceType
  payment: PaymentStatus
  startDate: string
  endDate: string
  minAmount: number
  maxAmount: number
}

const FILTER_SCHEMA: FilterSchema = {
  q: { type: 'string' },
  type: { type: 'enum', values: ['SALES', 'PURCHASE'] },
  payment: { type: 'enum', values: ['PAID', 'PARTIAL', 'UNPAID'] },
  startDate: { type: 'date' },
  endDate: { type: 'date' },
  minAmount: { type: 'number' },
  maxAmount: { type: 'number' },
}

export default function TaxListPage() {
  const { data, isLoading, isError, refetch } = useTaxes()
  const { data: sites } = useSites()
  const siteDisplay = useMemo(() => buildSiteDisplayMap(sites), [sites])
  const [modalOpen, setModalOpen] = useState(false)
  // 입금 기록·취소 뒤 목록이 다시 조회되면 모달도 최신 잔액을 보도록 ID로 보관한다.
  const [paymentInvoiceId, setPaymentInvoiceId] = useState<number | null>(null)
  const [editingInvoice, setEditingInvoice] = useState<TaxInvoice | null>(null)
  const [deletingInvoice, setDeletingInvoice] = useState<TaxInvoice | null>(null)
  const invoices = useMemo(() => data ?? [], [data])

  const { filters, setFilters, filtered, activeCount, resetFilters } =
    useListFilters({
      schema: FILTER_SCHEMA,
      items: invoices,
      groups: [
        ['startDate', 'endDate'],
        ['minAmount', 'maxAmount'],
      ],
      filterFn: (inv, f: Partial<TaxFilters>, rawQ) => {
        const q = rawQ.trim().toLowerCase()
        if (q && !(inv.counterparty ?? '').toLowerCase().includes(q)) return false
        if (f.type && inv.type !== f.type) return false
        // 입금 상태는 매출만 해당한다(매입은 수금 대상 아님).
        if (f.payment && paymentStatusOf(inv) !== f.payment) return false
        const dateKey = inv.issueDate?.slice(0, 10) ?? ''
        if (f.startDate && dateKey < f.startDate) return false
        if (f.endDate && dateKey > f.endDate) return false
        if (f.minAmount != null && inv.totalAmount < f.minAmount) return false
        if (f.maxAmount != null && inv.totalAmount > f.maxAmount) return false
        return true
      },
    })

  const unpaidInvoices = filtered.filter((t) => remainingOf(t) > 0)
  const unpaidTotal = unpaidInvoices.reduce((sum, t) => sum + remainingOf(t), 0)
  const unpaidCount = unpaidInvoices.length
  const paymentInvoice = paymentInvoiceId == null ? null : invoices.find((t) => t.id === paymentInvoiceId) ?? null

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
            onClick={() => setModalOpen(true)}
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

      <FilterBar activeCount={activeCount} onReset={resetFilters}>
        <FilterSearch
          value={filters.q ?? ''}
          onChange={(v) => setFilters({ q: v })}
          placeholder="거래처 검색"
        />
        <FilterSelect<TaxInvoiceType>
          placeholder="구분"
          value={filters.type}
          options={TYPE_OPTIONS}
          onChange={(v) => setFilters({ type: v })}
          width={130}
        />
        <FilterSelect<PaymentStatus>
          placeholder="입금 상태"
          value={filters.payment}
          options={PAYMENT_OPTIONS}
          onChange={(v) => setFilters({ payment: v })}
        />
        <FilterDateRange
          startDate={filters.startDate}
          endDate={filters.endDate}
          onChange={(range) =>
            setFilters({ startDate: range.startDate ?? '', endDate: range.endDate ?? '' })
          }
        />
        <FilterAmountRange
          minAmount={filters.minAmount}
          maxAmount={filters.maxAmount}
          onChange={(range) =>
            setFilters({ minAmount: range.minAmount, maxAmount: range.maxAmount })
          }
        />
      </FilterBar>

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
            {activeCount > 0 ? `필터 결과 미수금 (${unpaidCount}건)` : `미수금 합계 (${unpaidCount}건)`}
          </span>
          <span style={{ fontSize: 18, fontWeight: 700, color: '#ef4444', letterSpacing: '-0.03em' }}>
            ₩{unpaidTotal.toLocaleString('ko-KR')}
          </span>
        </motion.div>
      )}

      {isError ? (
        <ErrorState onRetry={refetch} />
      ) : isLoading ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {[1, 2, 3].map((i) => (
            <div key={i} className="shimmer" style={{ height: 64, borderRadius: 10 }} />
          ))}
        </div>
      ) : filtered.length === 0 ? (
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
          {activeCount > 0 ? '필터 조건에 맞는 세금계산서가 없습니다.' : '등록된 세금계산서가 없습니다.'}
        </motion.div>
      ) : (
        <motion.div
          style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, overflow: 'hidden' }}
        >
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid var(--border)' }}>
                {['구분', '현장', '거래처', '발행일', '공급가', '세액', '합계', '입금 상태', '처리'].map((h) => (
                  <th key={h} style={{
                    padding: '11px 20px', textAlign: 'left',
                    fontSize: 11, fontWeight: 600, color: 'var(--text-muted)',
                    letterSpacing: '0.05em', textTransform: 'uppercase',
                  }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {filtered.map((inv, i) => (
                <motion.tr
                  key={inv.id}
                  initial={{ opacity: 0, x: -8 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{ delay: Math.min(i, 9) * 0.03 }}
                  style={{ borderBottom: i < filtered.length - 1 ? '1px solid var(--border)' : 'none' }}
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
                  <td style={{ padding: '14px 20px', fontSize: 13 }}>
                    <SiteNameCell site={siteDisplay(inv.siteId)} />
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-primary)', fontWeight: 500 }}>
                    {inv.counterparty ?? '-'}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 12, color: 'var(--text-muted)', whiteSpace: 'nowrap' }}>
                    {inv.issueDate ?? '-'}
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
                    <PaymentBadge invoice={inv} />
                  </td>
                  <td style={{ padding: '14px 20px' }}>
                    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                      {inv.type === 'SALES' && (
                        <motion.button
                          whileHover={{ scale: 1.05 }} whileTap={{ scale: 0.95 }}
                          onClick={() => setPaymentInvoiceId(inv.id)} aria-label={`${inv.id}번 세금계산서 입금 관리`}
                          style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 11, fontWeight: 600, padding: '4px 10px', borderRadius: 6, background: 'rgba(34,197,94,0.1)', border: '1px solid rgba(34,197,94,0.2)', color: '#22c55e', cursor: 'pointer' }}
                        >
                          <Wallet size={11} strokeWidth={2.5} /> 입금
                        </motion.button>
                      )}
                      {!hasActivePayment(inv) && (
                        <>
                          <motion.button
                            whileHover={{ scale: 1.05 }} whileTap={{ scale: 0.95 }}
                            onClick={() => setEditingInvoice(inv)} aria-label={`${inv.id}번 세금계산서 수정`}
                            style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 11, padding: '4px 8px', borderRadius: 6, background: 'rgba(59,130,246,0.1)', border: '1px solid rgba(59,130,246,0.2)', color: '#60a5fa', cursor: 'pointer' }}
                          >
                            <Pencil size={11} /> 수정
                          </motion.button>
                          <motion.button
                            whileHover={{ scale: 1.05 }} whileTap={{ scale: 0.95 }}
                            onClick={() => setDeletingInvoice(inv)} aria-label={`${inv.id}번 세금계산서 삭제`}
                            style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 11, padding: '4px 8px', borderRadius: 6, background: 'rgba(239,68,68,0.1)', border: '1px solid rgba(239,68,68,0.2)', color: '#f87171', cursor: 'pointer' }}
                          >
                            <Trash2 size={11} /> 삭제
                          </motion.button>
                        </>
                      )}
                    </div>
                  </td>
                </motion.tr>
              ))}
            </tbody>
          </table>
        </motion.div>
      )}

      {modalOpen && <TaxCreateModal onClose={() => setModalOpen(false)} />}
      {paymentInvoice && (
        <TaxPaymentsModal
          invoice={paymentInvoice}
          onClose={() => setPaymentInvoiceId(null)}
        />
      )}
      {editingInvoice && (
        <TaxEditModal invoice={editingInvoice} onClose={() => setEditingInvoice(null)} />
      )}
      {deletingInvoice && (
        <TaxDeleteModal invoice={deletingInvoice} onClose={() => setDeletingInvoice(null)} />
      )}
    </div>
  )
}

function PaymentBadge({ invoice }: { invoice: TaxInvoice }) {
  const status = paymentStatusOf(invoice)
  if (status == null) {
    return <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>-</span>
  }
  const meta = PAYMENT_STATUS_META[status]
  return (
    <div>
      <span style={{
        fontSize: 11, fontWeight: 600, padding: '3px 8px',
        borderRadius: 20,
        background: `${meta.color}1a`,
        color: meta.color,
        border: `1px solid ${meta.color}33`,
      }}>
        {meta.label}
      </span>
      {status === 'PARTIAL' && (
        <div style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 4 }}>
          잔액 ₩{remainingOf(invoice).toLocaleString('ko-KR')}
        </div>
      )}
    </div>
  )
}
