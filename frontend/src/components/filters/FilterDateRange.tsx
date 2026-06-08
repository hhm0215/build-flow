import { useMemo } from 'react'
import { DatePicker } from 'antd'
import dayjs, { Dayjs } from 'dayjs'

interface FilterDateRangeProps {
  startDate: string | undefined
  endDate: string | undefined
  onChange: (range: { startDate?: string; endDate?: string }) => void
}

const ISO = 'YYYY-MM-DD'

export default function FilterDateRange({ startDate, endDate, onChange }: FilterDateRangeProps) {
  const value = useMemo<[Dayjs | null, Dayjs | null]>(
    () => [startDate ? dayjs(startDate) : null, endDate ? dayjs(endDate) : null],
    [startDate, endDate],
  )

  return (
    <DatePicker.RangePicker
      value={value}
      allowEmpty={[true, true]}
      placeholder={['시작일', '종료일']}
      onChange={(range) => {
        if (!range) {
          onChange({ startDate: undefined, endDate: undefined })
          return
        }
        const [s, e] = range
        let startStr = s?.format(ISO)
        let endStr = e?.format(ISO)
        if (startStr && endStr && startStr > endStr) {
          ;[startStr, endStr] = [endStr, startStr]
        }
        onChange({ startDate: startStr, endDate: endStr })
      }}
      style={{ width: 240 }}
    />
  )
}
