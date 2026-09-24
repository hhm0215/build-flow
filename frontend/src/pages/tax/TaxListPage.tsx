import { useMemo, useRef, useState } from 'react'
import { motion } from 'motion/react'
import { Receipt, Plus, CheckCircle, Pencil, Trash2 } from 'lucide-react'
import { Alert, Modal, Form, Input, InputNumber, Select, DatePicker } from 'antd'
import type { Dayjs } from 'dayjs'
import SiteSelect from '../../components/SiteSelect'
import PageHeader from '../../components/PageHeader'
import ErrorState from '../../components/ErrorState'
import FilterBar from '../../components/filters/FilterBar'
import FilterSearch from '../../components/filters/FilterSearch'
import FilterSelect from '../../components/filters/FilterSelect'
import FilterDateRange from '../../components/filters/FilterDateRange'
import FilterAmountRange from '../../components/filters/FilterAmountRange'
import { FilterSchema } from '../../hooks/useFilterParams'
import { useListFilters } from '../../hooks/useListFilters'
import { useTaxes, useCreateTax } from '../../api/taxes.api'
import type { TaxInvoice, TaxInvoiceCreateRequest, TaxInvoiceType } from '../../types'
import TaxPaymentConfirmModal from './TaxPaymentConfirmModal'
import TaxEditModal from './TaxEditModal'
import TaxDeleteModal from './TaxDeleteModal'
import { validateTaxAmounts } from '../../utils/tax'

type PaymentStatus = 'PAID' | 'UNPAID'

const TYPE_OPTIONS = [
  { value: 'SALES' as const, label: '매출', color: '#22c55e' },
  { value: 'PURCHASE' as const, label: '매입', color: '#ef4444' },
] as const

const PAYMENT_OPTIONS = [
  { value: 'PAID' as const, label: '입금 완료', color: '#22c55e' },
  { value: 'UNPAID' as const, label: '미입금', color: '#f59e0b' },
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

interface TaxCreateFormValues {
  siteId: number
  type: TaxInvoiceType
  supplyAmount: number
  taxAmount: number
  counterparty: string
  issueDate: Dayjs
  memo?: string
}

const FILTER_SCHEMA: FilterSchema = {
  q: { type: 'string' },
  type: { type: 'enum', values: ['SALES', 'PURCHASE'] },
  payment: { type: 'enum', values: ['PAID', 'UNPAID'] },
  startDate: { type: 'date' },
  endDate: { type: 'date' },
  minAmount: { type: 'number' },
  maxAmount: { type: 'number' },
}

export default function TaxListPage() {
  const { data, isLoading, isError, refetch } = useTaxes()
  const createTax = useCreateTax()
  const [modalOpen, setModalOpen] = useState(false)
  const [confirmingInvoice, setConfirmingInvoice] = useState<TaxInvoice | null>(null)
  const [editingInvoice, setEditingInvoice] = useState<TaxInvoice | null>(null)
  const [deletingInvoice, setDeletingInvoice] = useState<TaxInvoice | null>(null)
  const [createError, setCreateError] = useState<string | null>(null)
  const createInFlight = useRef(false)
  const [form] = Form.useForm<TaxCreateFormValues>()
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
        if (f.payment === 'PAID' && !inv.paymentConfirmed) return false
        if (f.payment === 'UNPAID' && inv.paymentConfirmed) return false
        const dateKey = inv.issueDate?.slice(0, 10) ?? ''
        if (f.startDate && dateKey < f.startDate) return false
        if (f.endDate && dateKey > f.endDate) return false
        if (f.minAmount != null && inv.totalAmount < f.minAmount) return false
        if (f.maxAmount != null && inv.totalAmount > f.maxAmount) return false
        return true
      },
    })

  const closeCreate = () => {
    if (createInFlight.current || createTax.isPending) return
    setModalOpen(false)
    setCreateError(null)
    form.resetFields()
  }

  const submitCreate = async () => {
    if (createInFlight.current || createTax.isPending) return
    createInFlight.current = true
    try {
      const values = await form.validateFields()
      const amountError = validateTaxAmounts(values.supplyAmount, values.taxAmount)
      if (amountError) {
        setCreateError(amountError)
        return
      }
      const body: TaxInvoiceCreateRequest = {
        siteId: values.siteId,
        type: values.type,
        supplyAmount: values.supplyAmount,
        taxAmount: values.taxAmount,
        counterparty: values.counterparty.trim(),
        issueDate: values.issueDate.format('YYYY-MM-DD'),
        memo: values.memo?.trim() || undefined,
      }
      setCreateError(null)
      await createTax.mutateAsync(body)
      setModalOpen(false)
      form.resetFields()
    } catch (cause: unknown) {
      if (cause && typeof cause === 'object' && 'errorFields' in cause) return
      const responseError = (cause as { response?: { data?: { error?: string } } })?.response?.data?.error
      setCreateError(responseError ?? '세금계산서를 등록하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      createInFlight.current = false
    }
  }

  const unpaidTotal = filtered
    .filter((t) => !t.paymentConfirmed && t.type === 'SALES')
    .reduce((sum, t) => sum + t.totalAmount, 0)
  const unpaidCount = filtered.filter((t) => !t.paymentConfirmed && t.type === 'SALES').length

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
            onClick={() => { setCreateError(null); setModalOpen(true) }}
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
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-primary)', fontWeight: 500 }}>
                    {inv.counterparty ?? '-'}
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
                    {!inv.paymentConfirmed && (
                      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                        {inv.type === 'SALES' && (
                          <motion.button
                            whileHover={{ scale: 1.05 }} whileTap={{ scale: 0.95 }}
                            onClick={() => setConfirmingInvoice(inv)}
                            style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 11, fontWeight: 600, padding: '4px 10px', borderRadius: 6, background: 'rgba(34,197,94,0.1)', border: '1px solid rgba(34,197,94,0.2)', color: '#22c55e', cursor: 'pointer' }}
                          >
                            <CheckCircle size={11} strokeWidth={2.5} /> 입금 확인
                          </motion.button>
                        )}
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
                      </div>
                    )}
                  </td>
                </motion.tr>
              ))}
            </tbody>
          </table>
        </motion.div>
      )}

      <Modal
        title="세금계산서 등록"
        open={modalOpen}
        okText="등록"
        cancelText="취소"
        confirmLoading={createTax.isPending}
        okButtonProps={{ disabled: createTax.isPending }}
        cancelButtonProps={{ disabled: createTax.isPending }}
        closable={!createTax.isPending}
        maskClosable={!createTax.isPending}
        keyboard={!createTax.isPending}
        destroyOnHidden
        onCancel={closeCreate}
        onOk={submitCreate}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          {createError && <Alert type="error" message={createError} showIcon style={{ marginBottom: 16 }} />}
          <Form.Item name="type" label="구분" rules={[{ required: true, message: '구분을 선택해주세요' }]}>
            <Select
              placeholder="매출 / 매입 선택"
              options={[
                { label: '매출', value: 'SALES' },
                { label: '매입', value: 'PURCHASE' },
              ]}
            />
          </Form.Item>
          <Form.Item name="siteId" label="현장" rules={[{ required: true, message: '현장을 선택해주세요' }]}>
            <SiteSelect placeholder="현장 검색 / 선택" />
          </Form.Item>
          <Form.Item name="counterparty" label="거래처" rules={[{ required: true, message: '거래처를 입력해주세요' }]}>
            <Input placeholder="거래처명" maxLength={200} />
          </Form.Item>
          <Form.Item name="supplyAmount" label="공급가액" rules={[{ required: true, message: '공급가액을 입력해주세요' }]}>
            <InputNumber<number>
              style={{ width: '100%' }}
              min={0}
              max={9_999_999_999_999.99}
              precision={2}
              formatter={(value) => `₩ ${value}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}
              parser={(value) => Number(value?.replace(/₩\s?|(,*)/g, '') ?? 0)}
              placeholder="공급가액"
            />
          </Form.Item>
          <Form.Item name="taxAmount" label="세액" rules={[{ required: true, message: '세액을 입력해주세요' }]}>
            <InputNumber<number>
              style={{ width: '100%' }}
              min={0}
              max={9_999_999_999_999.99}
              precision={2}
              formatter={(value) => `₩ ${value}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}
              parser={(value) => Number(value?.replace(/₩\s?|(,*)/g, '') ?? 0)}
              placeholder="세액"
            />
          </Form.Item>
          <Form.Item name="issueDate" label="발행일" rules={[{ required: true, message: '발행일을 선택해주세요' }]}>
            <DatePicker style={{ width: '100%' }} placeholder="발행일 선택" />
          </Form.Item>
          <Form.Item name="memo" label="메모">
            <Input.TextArea rows={3} placeholder="메모 (선택)" />
          </Form.Item>
        </Form>
      </Modal>
      {confirmingInvoice && (
        <TaxPaymentConfirmModal
          invoice={confirmingInvoice}
          onClose={() => setConfirmingInvoice(null)}
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
