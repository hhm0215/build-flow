import { useState } from 'react'
import { Button, Tag, message } from 'antd'
import SiteSelect from '../../components/SiteSelect'
import { useAssignPurchaseSite } from '../../api/purchases.api'
import { assignPurchasesSequentially, purchaseErrorMessage } from '../../utils/purchase'

export function UnassignedTag() {
  return <Tag color="orange" style={{ marginInlineEnd: 0 }}>미배정</Tag>
}

/** 미배정 행의 현장 셀: 검색 가능한 현장 선택 후 '배정'. 보관 현장은 선택지에서 빠진다(서버도 거부). */
export function PurchaseSiteAssignCell({ purchaseId }: { purchaseId: number }) {
  const assign = useAssignPurchaseSite()
  const [siteId, setSiteId] = useState<number | undefined>()

  const submit = async () => {
    if (siteId == null || assign.isPending) return
    try {
      await assign.mutateAsync({ id: purchaseId, siteId })
      message.success('현장을 배정했습니다. 해당 현장 손익에 반영됩니다.')
    } catch (cause) {
      message.error(purchaseErrorMessage(cause, '현장을 배정하지 못했습니다.'))
    }
  }

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 260 }}>
      <UnassignedTag />
      <SiteSelect
        size="small"
        aria-label="배정할 현장"
        placeholder="현장 검색"
        value={siteId}
        onChange={(value) => setSiteId(value ?? undefined)}
        style={{ minWidth: 150 }}
      />
      <Button size="small" type="primary" disabled={siteId == null} loading={assign.isPending} onClick={submit}>
        배정
      </Button>
    </div>
  )
}

interface BulkProps {
  selectedIds: number[]
  onDone: (failedIds: number[]) => void
}

/** 선택한 미배정 매입 여러 건을 한 현장에 일괄 배정. 건별로 순차 요청하고 실패 건은 선택 상태로 남긴다. */
export function PurchaseBulkAssignBar({ selectedIds, onDone }: BulkProps) {
  const assign = useAssignPurchaseSite()
  const [siteId, setSiteId] = useState<number | undefined>()
  const [running, setRunning] = useState(false)

  const submit = async () => {
    if (siteId == null || selectedIds.length === 0 || running) return
    setRunning(true)
    try {
      const result = await assignPurchasesSequentially(
        selectedIds,
        (id) => assign.mutateAsync({ id, siteId }),
        (cause) => purchaseErrorMessage(cause, '배정 실패'),
      )
      if (result.failed.length === 0) {
        message.success(`${result.succeeded.length}건을 현장에 배정했습니다.`)
      } else {
        message.warning(
          `${result.succeeded.length}건 배정, ${result.failed.length}건 실패: ${result.failed[0].message}`)
      }
      onDone(result.failed.map((failure) => failure.id))
    } finally {
      setRunning(false)
    }
  }

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
      <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>선택 {selectedIds.length}건</span>
      <SiteSelect
        aria-label="일괄 배정할 현장"
        placeholder="배정할 현장 검색"
        value={siteId}
        onChange={(value) => setSiteId(value ?? undefined)}
        style={{ width: 220 }}
      />
      <Button type="primary" disabled={siteId == null || selectedIds.length === 0} loading={running} onClick={submit}>
        선택 {selectedIds.length}건 배정
      </Button>
    </div>
  )
}
