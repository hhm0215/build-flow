import { Loader2, CheckCircle2, AlertCircle } from 'lucide-react'
import type { OcrStatus } from '../types'

interface Props {
  status?: OcrStatus
}

const STYLES: Record<Exclude<OcrStatus, 'MANUAL'>, { bg: string; fg: string; text: string; Icon: typeof Loader2 }> = {
  PENDING: { bg: 'rgba(148,163,184,0.15)', fg: '#94a3b8', text: 'AI 분석 중', Icon: Loader2 },
  SUCCESS: { bg: 'rgba(34,197,94,0.15)', fg: '#22c55e', text: 'AI 추출됨', Icon: CheckCircle2 },
  FAILED: { bg: 'rgba(239,68,68,0.15)', fg: '#ef4444', text: 'AI 실패', Icon: AlertCircle },
}

export default function OcrStatusBadge({ status }: Props) {
  if (!status || status === 'MANUAL') return null
  const s = STYLES[status]
  const Icon = s.Icon
  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 4,
        padding: '2px 8px',
        borderRadius: 999,
        background: s.bg,
        color: s.fg,
        fontSize: 11,
        fontWeight: 600,
        whiteSpace: 'nowrap',
      }}
    >
      <Icon
        size={12}
        style={status === 'PENDING' ? { animation: 'spin 1s linear infinite' } : undefined}
      />
      {s.text}
    </span>
  )
}
