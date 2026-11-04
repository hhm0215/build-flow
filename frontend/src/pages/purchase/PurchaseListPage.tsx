import { useMemo, useState } from 'react'
import { motion } from 'motion/react'
import { ShoppingBag, Pencil, Plus, Trash2, FileCheck } from 'lucide-react'
import { Button, Checkbox, Tooltip } from 'antd'
import SiteNameCell from '../../components/SiteNameCell'
import PageHeader from '../../components/PageHeader'
import ErrorState from '../../components/ErrorState'
import FilterBar from '../../components/filters/FilterBar'
import FilterSearch from '../../components/filters/FilterSearch'
import FilterDateRange from '../../components/filters/FilterDateRange'
import FilterAmountRange from '../../components/filters/FilterAmountRange'
import FilterSelect from '../../components/filters/FilterSelect'
import { FilterSchema } from '../../hooks/useFilterParams'
import { useListFilters } from '../../hooks/useListFilters'
import { usePurchases } from '../../api/purchases.api'
import { useSites } from '../../api/sites.api'
import PurchaseEditModal from './PurchaseEditModal'
import PurchaseDeleteModal from './PurchaseDeleteModal'
import PurchaseEvidenceModal from './PurchaseEvidenceModal'
import PurchaseCreateModal from './PurchaseCreateModal'
import type { Purchase } from '../../types'
import { buildSiteDisplayMap } from '../../utils/siteName'
import { summarizeUnassigned } from '../../utils/purchase'
import { PurchaseBulkAssignBar, PurchaseSiteAssignCell } from './PurchaseSiteAssign'

const ASSIGNMENT_VALUES = ['unassigned', 'assigned'] as const
type Assignment = typeof ASSIGNMENT_VALUES[number]
const ASSIGNMENT_OPTIONS = [
  { value: 'unassigned' as const, label: '현장 미배정', color: '#f59e0b' },
  { value: 'assigned' as const, label: '현장 배정됨' },
]

interface PurchaseFilters {
  assignment: Assignment
  q: string
  startDate: string
  endDate: string
  minAmount: number
  maxAmount: number
}

const FILTER_SCHEMA: FilterSchema = {
  assignment: { type: 'enum', values: ASSIGNMENT_VALUES },
  q: { type: 'string' },
  startDate: { type: 'date' },
  endDate: { type: 'date' },
  minAmount: { type: 'number' },
  maxAmount: { type: 'number' },
}

export default function PurchaseListPage() {
  const { data, isLoading, isError, refetch } = usePurchases()
  const { data: sites } = useSites()
  const siteDisplay = useMemo(() => buildSiteDisplayMap(sites), [sites])
  const purchases = useMemo(() => data ?? [], [data])

  const { filters, setFilters, filtered, activeCount, resetFilters } =
    useListFilters({
      schema: FILTER_SCHEMA,
      items: purchases,
      groups: [
        ['startDate', 'endDate'],
        ['minAmount', 'maxAmount'],
      ],
      filterFn: (p, f: Partial<PurchaseFilters>, rawQ) => {
        if (f.assignment === 'unassigned' && p.siteId != null) return false
        if (f.assignment === 'assigned' && p.siteId == null) return false
        const q = rawQ.trim().toLowerCase()
        if (q && !`${p.itemName} ${p.supplier ?? ''}`.toLowerCase().includes(q)) return false
        const dateKey = p.purchaseDate?.slice(0, 10) ?? ''
        if (f.startDate && dateKey < f.startDate) return false
        if (f.endDate && dateKey > f.endDate) return false
        if (f.minAmount != null && p.totalAmount < f.minAmount) return false
        if (f.maxAmount != null && p.totalAmount > f.maxAmount) return false
        return true
      },
    })

  const totalAmount = filtered.reduce((sum, p) => sum + p.totalAmount, 0)
  const unassignedSummary = useMemo(() => summarizeUnassigned(purchases), [purchases])
  const visibleUnassignedIds = useMemo(
    () => filtered.filter((p) => p.siteId == null).map((p) => p.id), [filtered])
  const [selected, setSelected] = useState<number[]>([])
  // 배정 완료·필터 변경으로 화면에서 사라진 행은 선택에서 뺀다
  const selectedIds = selected.filter((id) => visibleUnassignedIds.includes(id))
  const allVisibleSelected = visibleUnassignedIds.length > 0
    && visibleUnassignedIds.every((id) => selectedIds.includes(id))
  const toggleSelected = (id: number, checked: boolean) =>
    setSelected((prev) => checked ? [...prev.filter((x) => x !== id), id] : prev.filter((x) => x !== id))

  const [open, setOpen] = useState(false)
  const [editTarget, setEditTarget] = useState<Purchase | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<Purchase | null>(null)
  const [evidenceTarget, setEvidenceTarget] = useState<Purchase | null>(null)

  return (
    <div>
      <PageHeader
        icon={ShoppingBag}
        title="매입 관리"
        description="자재 및 서비스 매입 내역"
        action={
          <motion.button
            whileHover={{ scale: 1.02 }}
            whileTap={{ scale: 0.98 }}
            onClick={() => setOpen(true)}
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
            매입 등록
          </motion.button>
        }
      />

      {editTarget && <PurchaseEditModal purchase={editTarget} onClose={() => setEditTarget(null)} />}
      {deleteTarget && <PurchaseDeleteModal purchase={deleteTarget} onClose={() => setDeleteTarget(null)} />}
      {evidenceTarget && <PurchaseEvidenceModal purchase={evidenceTarget} onClose={() => setEvidenceTarget(null)} />}

      {open && <PurchaseCreateModal onClose={() => setOpen(false)} />}

      <FilterBar activeCount={activeCount} onReset={resetFilters}>
        <FilterSelect<Assignment>
          placeholder="현장 배정"
          value={filters.assignment as Assignment | undefined}
          options={ASSIGNMENT_OPTIONS}
          onChange={(v) => setFilters({ assignment: v })}
        />
        <FilterSearch
          value={filters.q ?? ''}
          onChange={(v) => setFilters({ q: v })}
          placeholder="품목명, 거래처 검색"
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

      {!isLoading && unassignedSummary.count > 0 && (
        <div
          role="status"
          style={{
            background: 'rgba(245,158,11,0.08)',
            border: '1px solid rgba(245,158,11,0.25)',
            borderRadius: 10,
            padding: '10px 20px',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            gap: 12,
            flexWrap: 'wrap',
            marginBottom: 12,
          }}
        >
          <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
            현장 미배정 매입 {unassignedSummary.count}건 · ₩{unassignedSummary.totalAmount.toLocaleString('ko-KR')}
            {' '}— 현장을 배정해야 손익에 반영됩니다.
          </span>
          {visibleUnassignedIds.length > 0 && (
            <PurchaseBulkAssignBar
              selectedIds={selectedIds}
              onDone={(failedIds) => setSelected(failedIds)}
            />
          )}
        </div>
      )}

      {!isLoading && (
        <motion.div
          initial={{ opacity: 0, y: -8 }}
          animate={{ opacity: 1, y: 0 }}
          style={{
            background: 'rgba(239,68,68,0.06)',
            border: '1px solid rgba(239,68,68,0.15)',
            borderRadius: 10,
            padding: '12px 20px',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            marginBottom: 12,
          }}
        >
          <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
            {activeCount > 0 ? `필터 결과 매입 합계 (${filtered.length}건)` : '전체 매입 합계'}
          </span>
          <span style={{ fontSize: 18, fontWeight: 700, color: '#ef4444', letterSpacing: '-0.03em' }}>
            ₩{totalAmount.toLocaleString('ko-KR')}
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
          {activeCount > 0 ? '필터 조건에 맞는 매입이 없습니다.' : '등록된 매입이 없습니다.'}
        </motion.div>
      ) : (
        <motion.div
          className="bf-table-wrap"
          style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, overflowX: 'auto', overflowY: 'hidden' }}
        >
          <table className="bf-list-table bf-list-table--sticky" style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid var(--border)' }}>
                <th style={{ padding: '11px 0 11px 16px', width: 32 }}>
                  {visibleUnassignedIds.length > 0 && (
                    <Checkbox
                      aria-label="미배정 매입 전체 선택"
                      checked={allVisibleSelected}
                      indeterminate={selectedIds.length > 0 && !allVisibleSelected}
                      onChange={(e) => setSelected(e.target.checked ? visibleUnassignedIds : [])}
                    />
                  )}
                </th>
                {['현장', '품목명', '수량', '단가', '금액', '거래처', '매입일', '증빙', '처리'].map((h) => (
                  <th key={h} style={{
                    padding: '11px 20px', textAlign: 'left',
                    fontSize: 11, fontWeight: 600, color: 'var(--text-muted)',
                    letterSpacing: '0.05em', textTransform: 'uppercase',
                  }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {filtered.map((p, i) => (
                <motion.tr
                  key={p.id}
                  initial={{ opacity: 0, x: -8 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{ delay: Math.min(i, 9) * 0.03 }}
                  style={{ borderBottom: i < filtered.length - 1 ? '1px solid var(--border)' : 'none', cursor: 'pointer' }}
                  onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.background = 'rgba(255,255,255,0.03)' }}
                  onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.background = 'transparent' }}
                >
                  <td style={{ padding: '14px 0 14px 16px' }}>
                    {p.siteId == null && (
                      <Checkbox
                        aria-label={`${p.itemName} 선택`}
                        checked={selectedIds.includes(p.id)}
                        onChange={(e) => toggleSelected(p.id, e.target.checked)}
                      />
                    )}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13 }}>
                    {p.siteId == null
                      ? <PurchaseSiteAssignCell purchaseId={p.id} />
                      : <SiteNameCell site={siteDisplay(p.siteId)} />}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-primary)', fontWeight: 500 }}>
                    {p.itemName}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-secondary)' }}>
                    {p.quantity}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-secondary)' }}>
                    ₩{p.unitPrice.toLocaleString('ko-KR')}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-primary)', fontWeight: 600 }}>
                    ₩{p.totalAmount.toLocaleString('ko-KR')}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-secondary)' }}>
                    {p.supplier || '-'}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 12, color: 'var(--text-muted)' }}>
                    {p.purchaseDate || '-'}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 12 }}>
                    {p.evidenceTaxInvoiceId != null ? (
                      <span style={{ color: '#22c55e', fontWeight: 600 }}>계산서 #{p.evidenceTaxInvoiceId}</span>
                    ) : (
                      <span style={{ color: '#f59e0b' }}>미연결</span>
                    )}
                  </td>
                  <td style={{ padding: '14px 20px' }}>
                    <div style={{ display: 'flex', gap: 6 }}>
                      {p.siteId == null ? (
                        <Tooltip title="현장을 먼저 배정해야 증빙을 연결할 수 있습니다">
                          <Button size="small" icon={<FileCheck size={12} />} disabled>증빙</Button>
                        </Tooltip>
                      ) : (
                        <Button size="small" icon={<FileCheck size={12} />} onClick={() => setEvidenceTarget(p)}>증빙</Button>
                      )}
                      <Button size="small" icon={<Pencil size={12} />} onClick={() => setEditTarget(p)}>수정</Button>
                      <Button size="small" danger icon={<Trash2 size={12} />} onClick={() => setDeleteTarget(p)}>삭제</Button>
                    </div>
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
