import { Alert, Spin } from 'antd'
import { useWarrantyHistory } from '../../api/warranties.api'
import type { WarrantyAuditAction } from '../../types'

const ACTION_LABEL: Record<WarrantyAuditAction, string> = {
  CREATED: '직접 등록',
  UPLOADED: 'PDF 업로드',
  OCR_APPLIED: 'OCR 결과 반영',
  OCR_FAILED: 'OCR 실패',
  MANUAL_UPDATED: '수동 수정·확인',
  DELETED: '삭제',
}

const ACTOR_LABEL: Record<string, string> = {
  system: '시스템',
  unknown: '알 수 없음',
}

function formatTime(value: string) {
  return value.replace('T', ' ').slice(0, 16)
}

/** 보증 값이 언제 어떤 경로로 바뀌었는지 보여 준다. 기록자는 공유 관리자 계정 단위다. */
export default function WarrantyHistory({ warrantyId }: { warrantyId: number }) {
  const { data, isLoading, isError } = useWarrantyHistory(warrantyId)

  if (isLoading) return <Spin size="small" />
  if (isError) return <Alert type="warning" showIcon message="변경 이력을 불러오지 못했습니다." />
  if (!data || data.length === 0) {
    return <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>기록된 변경 이력이 없습니다.</div>
  }

  return (
    <ol aria-label="변경·확인 이력" style={{ margin: 0, paddingLeft: 18, fontSize: 12, lineHeight: 1.7 }}>
      {data.map((event) => (
        <li key={event.id}>
          <strong>{ACTION_LABEL[event.action]}</strong>
          {' · '}
          {formatTime(event.occurredAt)}
          {' · '}
          {ACTOR_LABEL[event.actor] ?? event.actor}
          <div style={{ color: 'var(--text-muted)' }}>
            {event.insuranceCompany ?? '보험사 미확인'}
            {' / '}
            {event.startDate ?? '?'} ~ {event.endDate ?? '?'}
            {event.policyNumber ? ` / ${event.policyNumber}` : ''}
          </div>
        </li>
      ))}
    </ol>
  )
}
