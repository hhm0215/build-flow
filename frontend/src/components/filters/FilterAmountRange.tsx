import { InputNumber } from 'antd'

interface FilterAmountRangeProps {
  minAmount: number | undefined
  maxAmount: number | undefined
  onChange: (range: { minAmount?: number; maxAmount?: number }) => void
}

export default function FilterAmountRange({
  minAmount,
  maxAmount,
  onChange,
}: FilterAmountRangeProps) {
  const commit = (nextMin: number | undefined, nextMax: number | undefined) => {
    if (nextMin != null && nextMax != null && nextMin > nextMax) {
      ;[nextMin, nextMax] = [nextMax, nextMin]
    }
    onChange({ minAmount: nextMin, maxAmount: nextMax })
  }

  return (
    <div style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
      <InputNumber
        placeholder="최소 금액"
        value={minAmount ?? null}
        onChange={(v) => commit(v ?? undefined, maxAmount)}
        formatter={(v) => (v != null && `${v}` !== '' ? Number(v).toLocaleString('ko-KR') : '')}
        parser={((v: string | undefined) => {
          if (!v) return undefined
          const digits = v.replace(/[^0-9]/g, '')
          return digits === '' ? undefined : Number(digits)
        }) as never}
        style={{ width: 130 }}
        min={0}
        controls={false}
      />
      <span style={{ color: 'var(--text-muted)', fontSize: 13 }}>~</span>
      <InputNumber
        placeholder="최대 금액"
        value={maxAmount ?? null}
        onChange={(v) => commit(minAmount, v ?? undefined)}
        formatter={(v) => (v != null && `${v}` !== '' ? Number(v).toLocaleString('ko-KR') : '')}
        parser={((v: string | undefined) => {
          if (!v) return undefined
          const digits = v.replace(/[^0-9]/g, '')
          return digits === '' ? undefined : Number(digits)
        }) as never}
        style={{ width: 130 }}
        min={0}
        controls={false}
      />
    </div>
  )
}
