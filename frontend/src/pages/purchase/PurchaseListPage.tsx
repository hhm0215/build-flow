import { useMemo, useState } from 'react'
import { motion } from 'motion/react'
import { ShoppingBag, Plus } from 'lucide-react'
import { Modal, Form, Input, InputNumber, DatePicker } from 'antd'
import dayjs from 'dayjs'
import PageHeader from '../../components/PageHeader'
import ErrorState from '../../components/ErrorState'
import FilterBar from '../../components/filters/FilterBar'
import FilterSearch from '../../components/filters/FilterSearch'
import FilterDateRange from '../../components/filters/FilterDateRange'
import FilterAmountRange from '../../components/filters/FilterAmountRange'
import { useFilterParams, FilterSchema } from '../../hooks/useFilterParams'
import { useDebouncedValue } from '../../hooks/useDebouncedValue'
import { usePurchases, useCreatePurchase } from '../../api/purchases.api'
import type { PurchaseCreateRequest } from '../../types'

interface PurchaseFilters {
  q: string
  startDate: string
  endDate: string
  minAmount: number
  maxAmount: number
}

const FILTER_SCHEMA: FilterSchema = {
  q: { type: 'string' },
  startDate: { type: 'date' },
  endDate: { type: 'date' },
  minAmount: { type: 'number' },
  maxAmount: { type: 'number' },
}

export default function PurchaseListPage() {
  const { data, isLoading, isError, refetch } = usePurchases()
  const purchases = useMemo(() => data ?? [], [data])

  const [filters, setFilters] = useFilterParams<PurchaseFilters>(FILTER_SCHEMA)
  const debouncedQ = useDebouncedValue(filters.q ?? '', 250)

  const filtered = useMemo(() => {
    const q = debouncedQ.trim().toLowerCase()
    return purchases.filter((p) => {
      if (q && !(`${p.itemName} ${p.supplier ?? ''}`.toLowerCase().includes(q))) return false
      const dateKey = p.purchaseDate?.slice(0, 10) ?? ''
      if (filters.startDate && dateKey < filters.startDate) return false
      if (filters.endDate && dateKey > filters.endDate) return false
      if (filters.minAmount != null && p.totalAmount < filters.minAmount) return false
      if (filters.maxAmount != null && p.totalAmount > filters.maxAmount) return false
      return true
    })
  }, [purchases, debouncedQ, filters.startDate, filters.endDate, filters.minAmount, filters.maxAmount])

  const totalAmount = filtered.reduce((sum, p) => sum + p.totalAmount, 0)

  const activeCount =
    (filters.q ? 1 : 0) +
    (filters.startDate || filters.endDate ? 1 : 0) +
    (filters.minAmount != null || filters.maxAmount != null ? 1 : 0)

  const resetFilters = () =>
    setFilters({ q: '', startDate: '', endDate: '', minAmount: undefined, maxAmount: undefined })

  const [open, setOpen] = useState(false)
  const [form] = Form.useForm()
  const createMutation = useCreatePurchase()

  const handleOk = () => {
    form.validateFields().then((values) => {
      const body: PurchaseCreateRequest = {
        ...values,
        purchaseDate: values.purchaseDate
          ? dayjs(values.purchaseDate).format('YYYY-MM-DD')
          : undefined,
      }
      createMutation.mutate(body, {
        onSuccess: () => {
          setOpen(false)
          form.resetFields()
        },
      })
    })
  }

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

      <Modal
        title="매입 등록"
        open={open}
        onOk={handleOk}
        onCancel={() => { setOpen(false); form.resetFields() }}
        okText="등록"
        cancelText="취소"
        confirmLoading={createMutation.isPending}
        destroyOnClose
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item
            name="siteId"
            label="현장 ID"
            rules={[{ required: true, message: '현장 ID를 입력하세요' }]}
          >
            <InputNumber style={{ width: '100%' }} min={1} placeholder="현장 ID" />
          </Form.Item>
          <Form.Item
            name="itemName"
            label="품목명"
            rules={[{ required: true, message: '품목명을 입력하세요' }]}
          >
            <Input placeholder="품목명" />
          </Form.Item>
          <Form.Item
            name="quantity"
            label="수량"
            rules={[{ required: true, message: '수량을 입력하세요' }]}
          >
            <InputNumber style={{ width: '100%' }} min={1} placeholder="수량" />
          </Form.Item>
          <Form.Item
            name="unitPrice"
            label="단가"
            rules={[{ required: true, message: '단가를 입력하세요' }]}
          >
            <InputNumber
              style={{ width: '100%' }}
              min={0}
              placeholder="단가"
              formatter={(v) => `${v}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}
              parser={(v) => Number(v?.replace(/,/g, '') ?? 0) as 0}
            />
          </Form.Item>
          <Form.Item name="supplier" label="공급업체">
            <Input placeholder="공급업체명" />
          </Form.Item>
          <Form.Item name="purchaseDate" label="매입일">
            <DatePicker style={{ width: '100%' }} placeholder="매입일 선택" />
          </Form.Item>
          <Form.Item name="memo" label="메모">
            <Input.TextArea rows={3} placeholder="메모" />
          </Form.Item>
        </Form>
      </Modal>

      <FilterBar activeCount={activeCount} onReset={resetFilters}>
        <FilterSearch
          value={filters.q ?? ''}
          onChange={(v) => setFilters({ q: v })}
          placeholder="품목명, 공급업체 검색"
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
          style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, overflow: 'hidden' }}
        >
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid var(--border)' }}>
                {['품목명', '수량', '단가', '금액', '거래처', '매입일'].map((h) => (
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
                    {p.purchaseDate}
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
