import { useMemo } from 'react'
import { Select } from 'antd'

export interface FilterOption<T extends string = string> {
  value: T
  label: string
  color?: string
}

interface FilterSelectProps<T extends string> {
  placeholder: string
  value: T | undefined
  options: ReadonlyArray<FilterOption<T>>
  onChange: (value: T | undefined) => void
  width?: number
}

export default function FilterSelect<T extends string>({
  placeholder,
  value,
  options,
  onChange,
  width = 160,
}: FilterSelectProps<T>) {
  const renderedOptions = useMemo(
    () =>
      options.map((o) => ({
        value: o.value,
        label: o.color ? (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
            <span
              style={{
                width: 8,
                height: 8,
                borderRadius: '50%',
                background: o.color,
                flexShrink: 0,
              }}
            />
            {o.label}
          </span>
        ) : (
          o.label
        ),
      })),
    [options],
  )

  return (
    <Select<T>
      placeholder={placeholder}
      value={value}
      allowClear
      onChange={(next) => onChange(next ?? undefined)}
      style={{ width }}
      size="middle"
      options={renderedOptions}
    />
  )
}
