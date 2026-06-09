import { useMemo, useState } from 'react'
import { motion } from 'motion/react'
import { FileText, Plus, Trash2 } from 'lucide-react'
import { Modal, Form, Input, InputNumber, DatePicker, Button } from 'antd'
import dayjs from 'dayjs'
import PageHeader from '../../components/PageHeader'
import ErrorState from '../../components/ErrorState'
import FilterBar from '../../components/filters/FilterBar'
import FilterSearch from '../../components/filters/FilterSearch'
import FilterSelect from '../../components/filters/FilterSelect'
import FilterDateRange from '../../components/filters/FilterDateRange'
import FilterAmountRange from '../../components/filters/FilterAmountRange'
import { useFilterParams, FilterSchema } from '../../hooks/useFilterParams'
import { useDebouncedValue } from '../../hooks/useDebouncedValue'
import { useEstimates, useCreateEstimate } from '../../api/estimates.api'
import type { EstimateStatus, EstimateCreateRequest } from '../../types'

const STATUS_LABEL: Record<EstimateStatus, string> = {
  DRAFT: '작성 중',
  CONFIRMED: '확정',
}
const STATUS_COLOR: Record<EstimateStatus, string> = {
  DRAFT: '#71717a',
  CONFIRMED: '#22c55e',
}

const STATUS_OPTIONS = [
  { value: 'DRAFT' as const, label: STATUS_LABEL.DRAFT, color: STATUS_COLOR.DRAFT },
  { value: 'CONFIRMED' as const, label: STATUS_LABEL.CONFIRMED, color: STATUS_COLOR.CONFIRMED },
] as const

interface EstimateFilters {
  q: string
  status: EstimateStatus
  startDate: string
  endDate: string
  minAmount: number
  maxAmount: number
}

const FILTER_SCHEMA: FilterSchema = {
  q: { type: 'string' },
  status: { type: 'enum', values: ['DRAFT', 'CONFIRMED'] },
  startDate: { type: 'date' },
  endDate: { type: 'date' },
  minAmount: { type: 'number' },
  maxAmount: { type: 'number' },
}

function formatKRW(n: number) {
  return `₩${n.toLocaleString('ko-KR')}`
}

export default function EstimateListPage() {
  const { data, isLoading, isError, refetch } = useEstimates()
  const estimates = useMemo(() => data ?? [], [data])

  const [filters, setFilters] = useFilterParams<EstimateFilters>(FILTER_SCHEMA)
  const debouncedQ = useDebouncedValue(filters.q ?? '', 250)

  const filtered = useMemo(() => {
    const q = debouncedQ.trim().toLowerCase()
    return estimates.filter((est) => {
      if (q && !est.title.toLowerCase().includes(q)) return false
      if (filters.status && est.status !== filters.status) return false
      const dateKey = est.estimateDate?.slice(0, 10) ?? ''
      if (filters.startDate && dateKey < filters.startDate) return false
      if (filters.endDate && dateKey > filters.endDate) return false
      if (filters.minAmount != null && est.totalAmount < filters.minAmount) return false
      if (filters.maxAmount != null && est.totalAmount > filters.maxAmount) return false
      return true
    })
  }, [estimates, debouncedQ, filters.status, filters.startDate, filters.endDate, filters.minAmount, filters.maxAmount])

  const activeCount =
    (filters.q ? 1 : 0) +
    (filters.status ? 1 : 0) +
    (filters.startDate || filters.endDate ? 1 : 0) +
    (filters.minAmount != null || filters.maxAmount != null ? 1 : 0)

  const resetFilters = () =>
    setFilters({
      q: '',
      status: undefined,
      startDate: '',
      endDate: '',
      minAmount: undefined,
      maxAmount: undefined,
    })

  const [open, setOpen] = useState(false)
  const [form] = Form.useForm()
  const createMutation = useCreateEstimate()

  const handleOk = () => {
    form.validateFields().then((values) => {
      const items = (values.items ?? []).map(
        (item: { itemName: string; unit: string; quantity: number; unitPrice: number }) => ({
          itemName: item.itemName,
          unit: item.unit || 'EA',
          quantity: item.quantity,
          unitPrice: item.unitPrice,
          amount: (item.quantity ?? 0) * (item.unitPrice ?? 0),
        }),
      )

      const body: EstimateCreateRequest = {
        siteId: values.siteId,
        title: values.title,
        estimateDate: dayjs(values.estimateDate).format('YYYY-MM-DD'),
        items,
        memo: values.memo || undefined,
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
        icon={FileText}
        title="견적서"
        description="공내역서 AI 파싱 및 견적서 관리"
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
            견적서 작성
          </motion.button>
        }
      />

      <Modal
        title="견적서 작성"
        open={open}
        onOk={handleOk}
        onCancel={() => { setOpen(false); form.resetFields() }}
        okText="작성"
        cancelText="취소"
        confirmLoading={createMutation.isPending}
        destroyOnClose
        width={720}
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
            name="title"
            label="견적 제목"
            rules={[{ required: true, message: '견적 제목을 입력하세요' }]}
          >
            <Input placeholder="견적 제목" />
          </Form.Item>
          <Form.Item
            name="estimateDate"
            label="견적일"
            rules={[{ required: true, message: '견적일을 선택하세요' }]}
          >
            <DatePicker style={{ width: '100%' }} placeholder="견적일 선택" />
          </Form.Item>
          <Form.Item name="memo" label="메모">
            <Input.TextArea rows={2} placeholder="메모 (선택)" />
          </Form.Item>

          <div style={{
            fontSize: 14, fontWeight: 600, color: 'var(--text-primary)',
            marginBottom: 12,
            borderTop: '1px solid var(--border)',
            paddingTop: 16,
          }}>
            견적 항목
          </div>

          <Form.List name="items">
            {(fields, { add, remove }) => (
              <>
                {fields.map(({ key, name, ...restField }) => (
                  <div
                    key={key}
                    style={{
                      display: 'grid',
                      gridTemplateColumns: '1fr 70px 80px 120px auto',
                      gap: 8,
                      alignItems: 'start',
                      marginBottom: 4,
                    }}
                  >
                    <Form.Item
                      {...restField}
                      name={[name, 'itemName']}
                      rules={[{ required: true, message: '품목명' }]}
                      style={{ marginBottom: 8 }}
                    >
                      <Input placeholder="품목명" />
                    </Form.Item>
                    <Form.Item
                      {...restField}
                      name={[name, 'unit']}
                      style={{ marginBottom: 8 }}
                    >
                      <Input placeholder="EA" />
                    </Form.Item>
                    <Form.Item
                      {...restField}
                      name={[name, 'quantity']}
                      rules={[{ required: true, message: '수량' }]}
                      style={{ marginBottom: 8 }}
                    >
                      <InputNumber
                        style={{ width: '100%' }}
                        min={1}
                        placeholder="수량"
                      />
                    </Form.Item>
                    <Form.Item
                      {...restField}
                      name={[name, 'unitPrice']}
                      rules={[{ required: true, message: '단가' }]}
                      style={{ marginBottom: 8 }}
                    >
                      <InputNumber
                        style={{ width: '100%' }}
                        min={0}
                        placeholder="단가"
                        formatter={(v) => `${v}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}
                        parser={(v) => Number(v?.replace(/,/g, '') ?? 0) as 0}
                      />
                    </Form.Item>
                    <Button
                      type="text"
                      danger
                      onClick={() => remove(name)}
                      style={{ marginTop: 4 }}
                      icon={<Trash2 size={14} />}
                    />
                  </div>
                ))}

                <Form.Item
                  shouldUpdate={(prev, cur) => prev.items !== cur.items}
                  style={{ marginBottom: 12 }}
                >
                  {() => {
                    const items = form.getFieldValue('items') ?? []
                    const total = items.reduce(
                      (sum: number, item: { quantity?: number; unitPrice?: number } | undefined) =>
                        sum + ((item?.quantity ?? 0) * (item?.unitPrice ?? 0)),
                      0,
                    )
                    return total > 0 ? (
                      <div style={{
                        display: 'flex', justifyContent: 'flex-end',
                        fontSize: 14, fontWeight: 700, color: 'var(--text-primary)',
                        padding: '8px 0',
                      }}>
                        합계: {formatKRW(total)}
                      </div>
                    ) : null
                  }}
                </Form.Item>

                <Button
                  type="dashed"
                  onClick={() => add()}
                  block
                  icon={<Plus size={14} />}
                  style={{ marginBottom: 8 }}
                >
                  항목 추가
                </Button>
              </>
            )}
          </Form.List>
        </Form>
      </Modal>

      <FilterBar activeCount={activeCount} onReset={resetFilters}>
        <FilterSearch
          value={filters.q ?? ''}
          onChange={(v) => setFilters({ q: v })}
          placeholder="견적 제목 검색"
        />
        <FilterSelect<EstimateStatus>
          placeholder="상태"
          value={filters.status}
          options={STATUS_OPTIONS}
          onChange={(v) => setFilters({ status: v })}
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
          {activeCount > 0 ? '필터 조건에 맞는 견적서가 없습니다.' : '등록된 견적서가 없습니다.'}
        </motion.div>
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
              {filtered.map((est, i) => (
                <motion.tr
                  key={est.id}
                  initial={{ opacity: 0, x: -8 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{ delay: Math.min(i, 9) * 0.03 }}
                  style={{ borderBottom: i < filtered.length - 1 ? '1px solid var(--border)' : 'none', cursor: 'pointer' }}
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
