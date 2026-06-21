import { useMemo, useState } from 'react'
import { motion } from 'motion/react'
import { ShieldCheck, Plus, Trash2, AlertTriangle, UploadCloud } from 'lucide-react'
import { Modal, Form, Input, InputNumber, DatePicker } from 'antd'
import SiteSelect from '../../components/SiteSelect'
import dayjs from 'dayjs'
import PageHeader from '../../components/PageHeader'
import ErrorState from '../../components/ErrorState'
import OcrStatusBadge from '../../components/OcrStatusBadge'
import FilterBar from '../../components/filters/FilterBar'
import FilterSearch from '../../components/filters/FilterSearch'
import FilterSelect from '../../components/filters/FilterSelect'
import FilterDateRange from '../../components/filters/FilterDateRange'
import { useFilterParams, FilterSchema } from '../../hooks/useFilterParams'
import { useDebouncedValue } from '../../hooks/useDebouncedValue'
import { useWarranties, useExpiringWarranties, useDeleteWarranty, useCreateWarranty } from '../../api/warranties.api'
import type { WarrantyCreateRequest } from '../../types/domain.types'
import WarrantyUploadModal from './WarrantyUploadModal'

type WarrantyStatus = 'VALID' | 'EXPIRED'

const STATUS_OPTIONS = [
  { value: 'VALID' as const, label: '유효', color: '#22c55e' },
  { value: 'EXPIRED' as const, label: '만료', color: '#ef4444' },
] as const

interface WarrantyFilters {
  q: string
  status: WarrantyStatus
  endStart: string
  endEnd: string
}

const FILTER_SCHEMA: FilterSchema = {
  q: { type: 'string' },
  status: { type: 'enum', values: ['VALID', 'EXPIRED'] },
  endStart: { type: 'date' },
  endEnd: { type: 'date' },
}

export default function WarrantyListPage() {
  // PENDING 상태가 하나라도 있으면 5초 폴링 — OCR 비동기 처리 완료 자동 반영
  const [hasPendingHint, setHasPendingHint] = useState(false)
  const refetchInterval = hasPendingHint ? 5000 : undefined
  const { data, isLoading, isError, refetch } = useWarranties(undefined, refetchInterval)
  const { data: expiringData } = useExpiringWarranties(30)
  const { mutate: deleteWarranty } = useDeleteWarranty()
  const { mutate: createWarranty, isPending: isCreating } = useCreateWarranty()
  const warranties = useMemo(() => data ?? [], [data])

  // 데이터 갱신 시점에 PENDING 존재 여부 재계산
  useMemo(() => {
    const hasPending = warranties.some((w) => w.ocrStatus === 'PENDING')
    setHasPendingHint(hasPending)
  }, [warranties])
  const expiringCount = expiringData?.length ?? 0

  const [filters, setFilters] = useFilterParams<WarrantyFilters>(FILTER_SCHEMA)
  const debouncedQ = useDebouncedValue(filters.q ?? '', 250)

  const filtered = useMemo(() => {
    const q = debouncedQ.trim().toLowerCase()
    return warranties.filter((w) => {
      if (q && !(`${w.insuranceCompany} ${w.policyNumber}`.toLowerCase().includes(q))) return false
      if (filters.status === 'VALID' && w.expired) return false
      if (filters.status === 'EXPIRED' && !w.expired) return false
      const dateKey = w.endDate?.slice(0, 10) ?? ''
      if (filters.endStart && dateKey < filters.endStart) return false
      if (filters.endEnd && dateKey > filters.endEnd) return false
      return true
    })
  }, [warranties, debouncedQ, filters.status, filters.endStart, filters.endEnd])

  const activeCount =
    (filters.q ? 1 : 0) +
    (filters.status ? 1 : 0) +
    (filters.endStart || filters.endEnd ? 1 : 0)

  const resetFilters = () =>
    setFilters({ q: '', status: undefined, endStart: '', endEnd: '' })

  const [isModalOpen, setIsModalOpen] = useState(false)
  const [isUploadModalOpen, setIsUploadModalOpen] = useState(false)
  const [form] = Form.useForm()

  const handleCreateSubmit = () => {
    form.validateFields().then((values) => {
      const request: WarrantyCreateRequest = {
        siteId: values.siteId,
        insuranceCompany: values.insuranceCompany,
        policyNumber: values.policyNumber,
        coverageAmount: values.coverageAmount,
        startDate: dayjs(values.startDate).format('YYYY-MM-DD'),
        endDate: dayjs(values.endDate).format('YYYY-MM-DD'),
        memo: values.memo || '',
      }
      createWarranty(request, {
        onSuccess: () => {
          setIsModalOpen(false)
          form.resetFields()
        },
      })
    })
  }

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
          <div style={{ display: 'flex', gap: 8 }}>
            <motion.button
              whileHover={{ scale: 1.02 }}
              whileTap={{ scale: 0.98 }}
              onClick={() => setIsUploadModalOpen(true)}
              style={{
                display: 'flex', alignItems: 'center', gap: 6,
                padding: '8px 16px',
                background: 'rgba(167,139,250,0.12)',
                border: '1px solid rgba(167,139,250,0.3)',
                borderRadius: 'var(--radius-sm)',
                color: '#a78bfa', fontSize: 13, fontWeight: 600, cursor: 'pointer',
              }}
            >
              <UploadCloud size={14} strokeWidth={2.5} />
              PDF 업로드
            </motion.button>
            <motion.button
              whileHover={{ scale: 1.02 }}
              whileTap={{ scale: 0.98 }}
              onClick={() => setIsModalOpen(true)}
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
          </div>
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

      <FilterBar activeCount={activeCount} onReset={resetFilters}>
        <FilterSearch
          value={filters.q ?? ''}
          onChange={(v) => setFilters({ q: v })}
          placeholder="보험사, 증권번호 검색"
        />
        <FilterSelect<WarrantyStatus>
          placeholder="상태"
          value={filters.status}
          options={STATUS_OPTIONS}
          onChange={(v) => setFilters({ status: v })}
        />
        <FilterDateRange
          startDate={filters.endStart}
          endDate={filters.endEnd}
          onChange={(range) =>
            setFilters({ endStart: range.startDate ?? '', endEnd: range.endDate ?? '' })
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
          {activeCount > 0 ? '필터 조건에 맞는 보증보험이 없습니다.' : '등록된 보증보험이 없습니다.'}
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
              {filtered.map((w, i) => (
                <motion.tr
                  key={w.id}
                  initial={{ opacity: 0, x: -8 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{ delay: Math.min(i, 9) * 0.03 }}
                  style={{ borderBottom: i < filtered.length - 1 ? '1px solid var(--border)' : 'none' }}
                  onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.background = 'rgba(255,255,255,0.03)' }}
                  onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.background = 'transparent' }}
                >
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-primary)', fontWeight: 500 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <span>{w.insuranceCompany || <span style={{ color: 'var(--text-muted)', fontStyle: 'italic' }}>분석 대기</span>}</span>
                      <OcrStatusBadge status={w.ocrStatus} />
                    </div>
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-secondary)', fontFamily: 'monospace' }}>
                    {w.policyNumber}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-primary)', fontWeight: 600 }}>
                    {w.coverageAmount != null ? `₩${w.coverageAmount.toLocaleString('ko-KR')}` : '—'}
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

      <Modal
        title="보험 등록"
        open={isModalOpen}
        onOk={handleCreateSubmit}
        onCancel={() => { setIsModalOpen(false); form.resetFields() }}
        okText="등록"
        cancelText="취소"
        confirmLoading={isCreating}
        destroyOnClose
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="siteId" label="현장" rules={[{ required: true, message: '현장을 선택하세요' }]}>
            <SiteSelect placeholder="현장 검색 / 선택" />
          </Form.Item>
          <Form.Item name="insuranceCompany" label="보험사" rules={[{ required: true, message: '보험사를 입력하세요' }]}>
            <Input placeholder="보험사명" />
          </Form.Item>
          <Form.Item name="policyNumber" label="증권번호" rules={[{ required: true, message: '증권번호를 입력하세요' }]}>
            <Input placeholder="증권번호" />
          </Form.Item>
          <Form.Item name="coverageAmount" label="보증금액" rules={[{ required: true, message: '보증금액을 입력하세요' }]}>
            <InputNumber<number>
              style={{ width: '100%' }}
              placeholder="보증금액"
              min={0}
              formatter={(value) => `₩ ${value}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}
              parser={(value) => Number(value?.replace(/₩\s?|(,*)/g, ''))}
            />
          </Form.Item>
          <Form.Item name="startDate" label="보증 시작일" rules={[{ required: true, message: '시작일을 선택하세요' }]}>
            <DatePicker style={{ width: '100%' }} placeholder="시작일 선택" />
          </Form.Item>
          <Form.Item name="endDate" label="보증 종료일" rules={[{ required: true, message: '종료일을 선택하세요' }]}>
            <DatePicker style={{ width: '100%' }} placeholder="종료일 선택" />
          </Form.Item>
          <Form.Item name="memo" label="메모">
            <Input.TextArea rows={3} placeholder="메모 (선택)" />
          </Form.Item>
        </Form>
      </Modal>

      <WarrantyUploadModal
        open={isUploadModalOpen}
        onClose={() => setIsUploadModalOpen(false)}
        onUploaded={() => refetch()}
      />
    </div>
  )
}
