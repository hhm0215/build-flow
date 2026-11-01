import { useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { motion } from 'motion/react'
import { FileText, GitBranch, History, Pencil, Plus, Trash2, Sparkles } from 'lucide-react'
import { Alert, Modal, Form, Input, InputNumber, DatePicker, Button } from 'antd'
import SiteSelect from '../../components/SiteSelect'
import dayjs from 'dayjs'
import PageHeader from '../../components/PageHeader'
import ErrorState from '../../components/ErrorState'
import FilterBar from '../../components/filters/FilterBar'
import FilterSearch from '../../components/filters/FilterSearch'
import FilterSelect from '../../components/filters/FilterSelect'
import FilterDateRange from '../../components/filters/FilterDateRange'
import FilterAmountRange from '../../components/filters/FilterAmountRange'
import { FilterSchema } from '../../hooks/useFilterParams'
import { useListFilters } from '../../hooks/useListFilters'
import { useEstimates, useCreateEstimate } from '../../api/estimates.api'
import UploadParseModal from './UploadParseModal'
import EstimateConfirmModal from './EstimateConfirmModal'
import EstimateEditModal from './EstimateEditModal'
import EstimateDeleteModal from './EstimateDeleteModal'
import EstimateChainModal from './EstimateChainModal'
import type { Estimate, EstimateStatus, EstimateCreateRequest, ParsedItemResult } from '../../types'
import {
  estimateSupersededByConfirming,
  estimateTitleFromFileName,
  truncateUtf16,
  validateEstimateAmounts,
} from '../../utils/estimate'
import {
  ESTIMATE_KIND_LABEL,
  ESTIMATE_STATUS_COLOR as STATUS_COLOR,
  ESTIMATE_STATUS_LABEL as STATUS_LABEL,
} from './estimateLabels'

const STATUS_OPTIONS = [
  { value: 'DRAFT' as const, label: STATUS_LABEL.DRAFT, color: STATUS_COLOR.DRAFT },
  { value: 'CONFIRMED' as const, label: STATUS_LABEL.CONFIRMED, color: STATUS_COLOR.CONFIRMED },
  { value: 'SUPERSEDED' as const, label: STATUS_LABEL.SUPERSEDED, color: STATUS_COLOR.SUPERSEDED },
] as const

/** 확정(또는 대체된) 견적에서 만드는 수정본·추가공사 초안. 수정본은 항목을 복사하고 추가공사는 빈 항목으로 시작한다. */
interface DerivedDraft {
  kind: 'REVISION' | 'ADDITIONAL'
  source: Estimate
}

function derivedInitialValues({ kind, source }: DerivedDraft) {
  const suffix = kind === 'REVISION' ? ' (수정본)' : ' (추가공사)'
  return {
    siteId: source.siteId,
    title: truncateUtf16(source.title, 200 - suffix.length) + suffix,
    items: kind === 'REVISION'
      ? source.items.map(({ itemName, unit, quantity, unitPrice }) => ({ itemName, unit, quantity, unitPrice }))
      : [],
  }
}

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
  status: { type: 'enum', values: ['DRAFT', 'CONFIRMED', 'SUPERSEDED'] },
  startDate: { type: 'date' },
  endDate: { type: 'date' },
  minAmount: { type: 'number' },
  maxAmount: { type: 'number' },
}

function formatKRW(n: number) {
  return `₩${n.toLocaleString('ko-KR')}`
}

/** 현장 상세의 '견적서 추가'가 navigate state로 넘기는 값 */
export interface EstimateCreateLocationState {
  createForSiteId?: number
}

export default function EstimateListPage() {
  const { data, isLoading, isError, refetch } = useEstimates()
  const location = useLocation()
  const navigate = useNavigate()
  // 현장 상세에서 넘어오면 그 현장이 선택된 작성 모달을 바로 연다.
  const [presetSiteId, setPresetSiteId] = useState<number | undefined>(() => {
    const sid = (location.state as EstimateCreateLocationState | null)?.createForSiteId
    return typeof sid === 'number' ? sid : undefined
  })
  const estimates = useMemo(() => data ?? [], [data])

  const { filters, setFilters, filtered, activeCount, resetFilters } =
    useListFilters({
      schema: FILTER_SCHEMA,
      items: estimates,
      groups: [
        ['startDate', 'endDate'],
        ['minAmount', 'maxAmount'],
      ],
      filterFn: (est, f: Partial<EstimateFilters>, rawQ) => {
        const q = rawQ.trim().toLowerCase()
        if (q && !est.title.toLowerCase().includes(q)) return false
        if (f.status && est.status !== f.status) return false
        const dateKey = est.estimateDate?.slice(0, 10) ?? ''
        if (f.startDate && dateKey < f.startDate) return false
        if (f.endDate && dateKey > f.endDate) return false
        if (f.minAmount != null && est.totalAmount < f.minAmount) return false
        if (f.maxAmount != null && est.totalAmount > f.maxAmount) return false
        return true
      },
    })

  const [open, setOpen] = useState(presetSiteId != null)
  const [uploadOpen, setUploadOpen] = useState(false)
  const [confirmTarget, setConfirmTarget] = useState<Estimate | null>(null)
  const [editTarget, setEditTarget] = useState<Estimate | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<Estimate | null>(null)
  const [historyTarget, setHistoryTarget] = useState<Estimate | null>(null)
  const [derived, setDerived] = useState<DerivedDraft | null>(null)
  const relatedIds = useMemo(() => {
    const ids = new Set<number>()
    estimates.forEach((est) => {
      if (est.revisionOfId != null) {
        ids.add(est.id)
        ids.add(est.revisionOfId)
      }
    })
    return ids
  }, [estimates])

  const openDerived = (source: Estimate, kind: DerivedDraft['kind']) => {
    setDerived({ source, kind })
    setCreateError(null)
    setOpen(true)
  }
  const [pendingItems, setPendingItems] = useState<ParsedItemResult[] | null>(null)
  const [createError, setCreateError] = useState<string | null>(null)
  const createInFlight = useRef(false)
  const [form] = Form.useForm()
  const createMutation = useCreateEstimate()

  useEffect(() => {
    // 새로고침·뒤로가기로 작성 모달이 다시 열리지 않도록 일회성 state를 지운다.
    if ((location.state as EstimateCreateLocationState | null)?.createForSiteId != null) {
      navigate(`${location.pathname}${location.search}`, { replace: true, state: null })
    }
  }, [location, navigate])

  const handleParsedConfirm = (items: ParsedItemResult[], fileName: string) => {
    setPendingItems(items)
    setUploadOpen(false)
    setOpen(true)
    form.setFieldsValue({ title: estimateTitleFromFileName(fileName) })
  }

  const handleCreateModalAfterOpen = (visible: boolean) => {
    if (visible && pendingItems) {
      form.setFieldsValue({ items: pendingItems })
      setPendingItems(null)
    }
  }

  const closeCreate = () => {
    if (createInFlight.current || createMutation.isPending) return
    setOpen(false)
    setCreateError(null)
    form.resetFields()
    setPendingItems(null)
    setDerived(null)
    setPresetSiteId(undefined)
  }

  const handleOk = async () => {
    if (createInFlight.current || createMutation.isPending) return
    createInFlight.current = true
    setCreateError(null)
    try {
      const values = await form.validateFields()
      const amountError = validateEstimateAmounts(values.items ?? [])
      if (amountError) {
        setCreateError(amountError)
        return
      }
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
        ...(derived ? { kind: derived.kind, revisionOfId: derived.source.id } : {}),
      }

      setCreateError(null)
      await createMutation.mutateAsync(body)
      setOpen(false)
      setDerived(null)
      setPresetSiteId(undefined)
      form.resetFields()
    } catch (cause: unknown) {
      if (cause && typeof cause === 'object' && 'errorFields' in cause) return
      const responseError = (cause as { response?: { data?: { error?: string } } })?.response?.data?.error
      setCreateError(responseError ?? '견적서를 작성하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      createInFlight.current = false
    }
  }

  return (
    <div>
      <PageHeader
        icon={FileText}
        title="견적서"
        description="공내역서 AI 파싱 및 견적서 관리"
        action={
          <div style={{ display: 'flex', gap: 8 }}>
            <motion.button
              whileHover={{ scale: 1.02 }}
              whileTap={{ scale: 0.98 }}
              onClick={() => setUploadOpen(true)}
              style={{
                display: 'flex', alignItems: 'center', gap: 6,
                padding: '8px 14px',
                background: 'rgba(167,139,250,0.1)',
                border: '1px solid rgba(167,139,250,0.35)',
                borderRadius: 'var(--radius-sm)',
                color: '#a78bfa', fontSize: 13, fontWeight: 600, cursor: 'pointer',
              }}
            >
              <Sparkles size={14} strokeWidth={2.5} />
              공내역서 AI 파싱
            </motion.button>
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
          </div>
        }
      />

      <UploadParseModal
        open={uploadOpen}
        onClose={() => setUploadOpen(false)}
        onConfirm={handleParsedConfirm}
      />

      {confirmTarget && (
        <EstimateConfirmModal
          estimate={confirmTarget}
          supersedes={estimateSupersededByConfirming(estimates, confirmTarget)}
          onClose={() => setConfirmTarget(null)}
        />
      )}
      {editTarget && <EstimateEditModal estimate={editTarget} onClose={() => setEditTarget(null)} />}
      {deleteTarget && <EstimateDeleteModal estimate={deleteTarget} onClose={() => setDeleteTarget(null)} />}
      {historyTarget && (
        <EstimateChainModal estimate={historyTarget} estimates={estimates} onClose={() => setHistoryTarget(null)} />
      )}

      <Modal
        title={derived ? `${ESTIMATE_KIND_LABEL[derived.kind]} 작성` : '견적서 작성'}
        open={open}
        onOk={handleOk}
        onCancel={closeCreate}
        okText="작성"
        cancelText="취소"
        confirmLoading={createMutation.isPending}
        destroyOnHidden
        afterOpenChange={handleCreateModalAfterOpen}
        width={720}
      >
        {createError && <Alert type="error" message={createError} showIcon style={{ marginBottom: 16 }} />}
        {derived && (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message={derived.kind === 'REVISION'
              ? `원 견적 #${derived.source.id} ‘${derived.source.title}’의 항목을 복사했습니다. 이 수정본을 확정하면 기존 확정본은 대체됨으로 남고 손익에서 빠집니다.`
              : `원 견적 #${derived.source.id} ‘${derived.source.title}’에 대한 추가공사입니다. 확정하면 원 견적과 별도로 합산됩니다.`}
          />
        )}
        <Form
          form={form}
          layout="vertical"
          style={{ marginTop: 16 }}
          initialValues={derived
            ? derivedInitialValues(derived)
            : presetSiteId != null ? { siteId: presetSiteId } : undefined}
        >
          {derived ? (
            <Form.Item name="siteId" hidden><Input /></Form.Item>
          ) : (
            <Form.Item
              name="siteId"
              label="현장"
              rules={[{ required: true, message: '현장을 선택하세요' }]}
            >
              <SiteSelect placeholder="현장 검색 / 선택" />
            </Form.Item>
          )}
          <Form.Item
            name="title"
            label="견적 제목"
            rules={[{ required: true, message: '견적 제목을 입력하세요' }, { max: 200, message: '견적 제목은 200자 이하여야 합니다' }]}
          >
            <Input placeholder="견적 제목" maxLength={200} />
          </Form.Item>
          <Form.Item
            name="estimateDate"
            label="견적일"
            rules={[{ required: true, message: '견적일을 선택하세요' }]}
          >
            <DatePicker style={{ width: '100%' }} placeholder="견적일 선택" />
          </Form.Item>
          <Form.Item name="memo" label="메모">
            <Input.TextArea rows={2} placeholder="메모 (선택)" maxLength={1000} />
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
                      rules={[{ required: true, message: '품목명' }, { max: 200, message: '품목명은 200자 이하여야 합니다' }]}
                      style={{ marginBottom: 8 }}
                    >
                      <Input placeholder="품목명" maxLength={200} />
                    </Form.Item>
                    <Form.Item
                      {...restField}
                      name={[name, 'unit']}
                      rules={[{ required: true, whitespace: true, message: '단위' }, { max: 20, message: '단위는 20자 이하여야 합니다' }]}
                      style={{ marginBottom: 8 }}
                    >
                      <Input placeholder="EA" maxLength={20} />
                    </Form.Item>
                    <Form.Item
                      {...restField}
                      name={[name, 'quantity']}
                      rules={[{ required: true, message: '수량' }, { type: 'number', min: 0.01, message: '수량은 0보다 커야 합니다' }]}
                      style={{ marginBottom: 8 }}
                    >
                      <InputNumber
                        style={{ width: '100%' }}
                        min={0.01}
                        precision={2}
                        placeholder="수량"
                      />
                    </Form.Item>
                    <Form.Item
                      {...restField}
                      name={[name, 'unitPrice']}
                      rules={[{ required: true, message: '단가' }, { type: 'number', min: 0, message: '단가는 0 이상이어야 합니다' }]}
                      style={{ marginBottom: 8 }}
                    >
                      <InputNumber<number>
                        style={{ width: '100%' }}
                        min={0}
                        precision={2}
                        placeholder="단가"
                        formatter={(v) => `${v}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}
                        parser={(v) => Number(v?.replace(/,/g, '') ?? 0)}
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
                {['제목', '관계', '상태', '총액', '항목 수', '견적일', '처리'].map((h) => (
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
                  <td style={{ padding: '14px 20px', fontSize: 12, color: 'var(--text-secondary)' }}>
                    {ESTIMATE_KIND_LABEL[est.kind]}
                    {est.revisionOfId != null && ` ← #${est.revisionOfId}`}
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
                  <td style={{
                    padding: '14px 20px', fontSize: 13, color: 'var(--text-primary)', fontWeight: 600,
                    textDecoration: est.status === 'SUPERSEDED' ? 'line-through' : 'none',
                  }}>
                    {est.totalAmount > 0 ? formatKRW(est.totalAmount) : '—'}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-secondary)' }}>
                    {est.items.length}건
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 12, color: 'var(--text-muted)' }}>
                    {est.estimateDate}
                  </td>
                  <td style={{ padding: '14px 20px' }}>
                    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                      {est.status === 'DRAFT' ? (
                        <>
                          <Button size="small" onClick={() => setConfirmTarget(est)}>확정</Button>
                          <Button size="small" icon={<Pencil size={12} />} onClick={() => setEditTarget(est)}>수정</Button>
                          <Button size="small" danger icon={<Trash2 size={12} />} onClick={() => setDeleteTarget(est)}>삭제</Button>
                        </>
                      ) : (
                        <>
                          <Button size="small" icon={<GitBranch size={12} />} onClick={() => openDerived(est, 'REVISION')}>수정본</Button>
                          <Button size="small" icon={<Plus size={12} />} onClick={() => openDerived(est, 'ADDITIONAL')}>추가공사</Button>
                        </>
                      )}
                      {relatedIds.has(est.id) && (
                        <Button size="small" icon={<History size={12} />} onClick={() => setHistoryTarget(est)}>이력</Button>
                      )}
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
