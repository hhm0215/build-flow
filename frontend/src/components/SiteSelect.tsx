import { Select, type SelectProps } from 'antd'
import { useSites } from '../api/sites.api'

/**
 * AntD Select<number>의 props를 그대로 받고 (Form.Item 주입 id/onBlur/aria-* 포함),
 * options/showSearch/optionFilterProp/filterOption은 본 컴포넌트가 관리.
 *
 * Form.Item과 함께 쓸 때 라벨 클릭 포커스/검증 status/접근성 props가 전부 forward됨.
 */
type SiteSelectProps = Omit<
  SelectProps<number>,
  'options' | 'showSearch' | 'optionFilterProp' | 'filterOption' | 'loading' | 'mode'
>

export default function SiteSelect({
  placeholder = '현장 선택',
  allowClear = true,
  style,
  ...rest
}: SiteSelectProps) {
  const { data, isLoading } = useSites()
  const options = (data ?? []).map((s) => ({
    value: s.id,
    label: `${s.siteName} (#${s.id})`,
    searchKey: `${s.siteName} ${s.id} ${s.client?.companyName ?? ''}`.toLowerCase(),
  }))

  return (
    <Select<number>
      {...rest}
      placeholder={placeholder}
      allowClear={allowClear}
      showSearch
      loading={isLoading}
      optionFilterProp="label"
      filterOption={(input, option) =>
        (option?.searchKey ?? '').includes(input.toLowerCase())
      }
      options={options}
      style={{ width: '100%', ...style }}
    />
  )
}
