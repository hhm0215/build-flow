import { Alert, Button } from 'antd'
import { usePurchaseEvidenceSummary } from '../../api/purchases.api'

function krw(value: number) {
  return `₩${value.toLocaleString('ko-KR')}`
}

function signedKrw(value: number) {
  if (value === 0) return '₩0'
  return `${value > 0 ? '+' : '−'}₩${Math.abs(value).toLocaleString('ko-KR')}`
}

function Metric({ label, value, hint, tone }: {
  label: string
  value: string
  hint?: string
  tone?: 'warn' | 'ok'
}) {
  const color = tone === 'warn' ? '#f59e0b' : tone === 'ok' ? '#22c55e' : 'var(--text-primary)'
  return (
    <div>
      <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 4 }}>{label}</div>
      <div style={{ fontSize: 15, fontWeight: 700, color }}>{value}</div>
      {hint && <div style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 2 }}>{hint}</div>}
    </div>
  )
}

/**
 * 현장별 매입 항목 ↔ 매입 세금계산서 증빙 대조. 원가는 매입 항목만이며
 * 미연결 금액과 계산서−연결 매입 차이로 누락·중복을 사람이 찾게 한다.
 */
export default function PurchaseEvidenceSummaryCard({ siteId }: { siteId: number }) {
  const { data, isLoading, isError, refetch } = usePurchaseEvidenceSummary(siteId)

  if (isError) {
    return (
      <Alert
        type="error"
        showIcon
        style={{ marginBottom: 12 }}
        message="증빙 대조 요약을 불러오지 못했습니다."
        action={<Button size="small" onClick={() => refetch()}>다시 시도</Button>}
      />
    )
  }
  if (isLoading || !data) {
    return <div className="shimmer" style={{ height: 96, borderRadius: 10, marginBottom: 12 }} aria-label="증빙 대조 불러오는 중" />
  }

  return (
    <section
      aria-label="매입 증빙 대조"
      style={{
        background: 'var(--bg-card)',
        border: '1px solid var(--border)',
        borderRadius: 12,
        padding: 16,
        marginBottom: 12,
      }}
    >
      <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-primary)', marginBottom: 4 }}>
        매입 증빙 대조
      </div>
      <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 12 }}>
        원가는 매입 항목 합계입니다. 매입 세금계산서는 증빙으로만 연결하며 원가에 더하지 않습니다.
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 12 }}>
        <Metric
          label="증빙 미연결 매입"
          value={krw(data.unlinkedPurchaseAmount)}
          hint={`${data.unlinkedPurchaseCount}건 / 전체 ${data.purchaseCount}건`}
          tone={data.unlinkedPurchaseCount > 0 ? 'warn' : 'ok'}
        />
        <Metric
          label="연결된 매입 합계"
          value={krw(data.linkedPurchaseAmount)}
          hint={`${data.linkedPurchaseCount}건`}
        />
        <Metric
          label="매입 계산서 합계"
          value={krw(data.invoiceTotalAmount)}
          hint={`공급가 ${krw(data.invoiceSupplyAmount)} · ${data.invoiceCount}건`}
        />
        <Metric
          label="계산서 − 연결 매입"
          value={signedKrw(data.supplyDifference)}
          hint={`공급가 기준 · 합계 기준 ${signedKrw(data.totalDifference)}`}
          tone={data.supplyDifference !== 0 && data.totalDifference !== 0 ? 'warn' : 'ok'}
        />
      </div>
      {(data.brokenLinkCount > 0 || data.invoiceWithoutPurchaseCount > 0) && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginTop: 12 }}>
          {data.brokenLinkCount > 0 && (
            <Alert
              type="warning"
              showIcon
              message={`연결된 계산서를 찾을 수 없는 매입 ${data.brokenLinkCount}건 — 매입 화면에서 다시 연결하세요.`}
            />
          )}
          {data.invoiceWithoutPurchaseCount > 0 && (
            <Alert
              type="info"
              showIcon
              message={`연결된 매입이 없는 매입 계산서 ${data.invoiceWithoutPurchaseCount}건 — 매입 등록 누락인지 확인하세요.`}
            />
          )}
        </div>
      )}
    </section>
  )
}
