import type { EstimateKind, EstimateStatus } from '../../types'

export const ESTIMATE_STATUS_LABEL: Record<EstimateStatus, string> = {
  DRAFT: '작성 중',
  CONFIRMED: '확정',
  SUPERSEDED: '대체됨',
}

export const ESTIMATE_STATUS_COLOR: Record<EstimateStatus, string> = {
  DRAFT: '#71717a',
  CONFIRMED: '#22c55e',
  SUPERSEDED: '#a1a1aa',
}

export const ESTIMATE_KIND_LABEL: Record<EstimateKind, string> = {
  ORIGINAL: '원본',
  REVISION: '수정본',
  ADDITIONAL: '추가공사',
}
