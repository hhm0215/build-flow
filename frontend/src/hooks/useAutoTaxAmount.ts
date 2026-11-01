import { useCallback, useRef } from 'react'
import type { FormInstance } from 'antd'
import { defaultTaxAmount, shouldAutoFillTax } from '../utils/tax'

interface TaxAmountFields {
  supplyAmount?: number | null
  taxAmount?: number | null
}

/**
 * 세금계산서 폼의 Form `onValuesChange`에 연결한다.
 * 공급가액을 입력하면 세액을 10%(원 미만 절사)로 채우되, 사용자가 세액을 직접 고친 뒤에는 덮어쓰지 않는다.
 * 세액을 지우면 다시 자동 채움 대상이 된다. onValuesChange는 사용자 입력에만 호출되므로
 * 여기서 setFieldValue로 채운 값은 "직접 고침"으로 집계되지 않는다.
 */
export function useAutoTaxAmount(
  form: Pick<FormInstance, 'getFieldValue' | 'setFieldValue'>,
  initialTouched = false,
) {
  const touched = useRef(initialTouched)

  return useCallback((changed: TaxAmountFields) => {
    if ('taxAmount' in changed) {
      touched.current = changed.taxAmount != null
    }
    if ('supplyAmount' in changed) {
      const current = form.getFieldValue('taxAmount') as number | null | undefined
      if (!shouldAutoFillTax(current, touched.current)) return
      const supply = changed.supplyAmount
      const next = defaultTaxAmount(supply)
      // 범위를 벗어난 공급가액은 저장 시 검증 메시지로 안내하고, 세액은 건드리지 않는다.
      if (supply != null && next == null) return
      form.setFieldValue('taxAmount', next ?? undefined)
      touched.current = false
    }
  }, [form])
}
