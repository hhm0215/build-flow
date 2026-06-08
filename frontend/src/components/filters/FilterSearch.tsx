import { Input } from 'antd'
import { Search } from 'lucide-react'

interface FilterSearchProps {
  value: string
  onChange: (value: string) => void
  placeholder?: string
  width?: number
}

export default function FilterSearch({
  value,
  onChange,
  placeholder = '검색...',
  width = 220,
}: FilterSearchProps) {
  return (
    <Input
      value={value}
      onChange={(e) => onChange(e.target.value)}
      placeholder={placeholder}
      prefix={<Search size={14} strokeWidth={2} style={{ color: 'var(--text-muted)' }} />}
      allowClear
      style={{ width }}
    />
  )
}
