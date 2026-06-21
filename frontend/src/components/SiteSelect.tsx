import { Select } from 'antd'
import { useSites } from '../api/sites.api'

interface Props {
  value?: number
  onChange?: (value: number | undefined) => void
  placeholder?: string
  disabled?: boolean
  allowClear?: boolean
}

/**
 * 현장 선택 드롭다운 — 이름 검색 + ID 표시.
 * useSites로 전체 현장 목록을 받아 Select option으로 렌더.
 * Ant Design Form.Item이 value/onChange를 자동 주입하므로 별도 옵션 불필요.
 */
export default function SiteSelect({
  value,
  onChange,
  placeholder = '현장 선택',
  disabled,
  allowClear = true,
}: Props) {
  const { data, isLoading } = useSites()
  const options = (data ?? []).map((s) => ({
    value: s.id,
    label: `${s.siteName} (#${s.id})`,
    searchText: `${s.siteName} ${s.id} ${s.client?.companyName ?? ''}`.toLowerCase(),
  }))

  return (
    <Select<number>
      value={value}
      onChange={onChange}
      placeholder={placeholder}
      disabled={disabled}
      allowClear={allowClear}
      showSearch
      loading={isLoading}
      optionFilterProp="searchText"
      filterOption={(input, option) =>
        (option?.searchText ?? '').includes(input.toLowerCase())
      }
      options={options}
      style={{ width: '100%' }}
    />
  )
}
