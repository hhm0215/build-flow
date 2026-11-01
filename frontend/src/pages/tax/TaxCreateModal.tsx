import { useRef, useState } from 'react'
import { Alert, DatePicker, Form, Input, InputNumber, Modal, Select } from 'antd'
import type { Dayjs } from 'dayjs'
import SiteSelect from '../../components/SiteSelect'
import { useCreateTax } from '../../api/taxes.api'
import { useAutoTaxAmount } from '../../hooks/useAutoTaxAmount'
import type { TaxInvoiceCreateRequest, TaxInvoiceType } from '../../types'
import { TAX_AUTO_HINT, validateTaxAmounts } from '../../utils/tax'

interface FormValues {
  siteId: number
  type: TaxInvoiceType
  supplyAmount: number
  taxAmount: number
  counterparty: string
  issueDate: Dayjs
  memo?: string
}

/**
 * 표시용 '₩ 1,000' 문자열을 숫자 문자열로 되돌린다. 빈 입력은 0이 아니라 빈 값으로 남겨야
 * 세액을 지웠을 때 자동 채움이 다시 동작하고, 필수 검증도 '0원'으로 통과하지 않는다.
 */
const parseWon = (value?: string) => (value ?? '').replace(/₩\s?|,/g, '') as unknown as number

interface Props {
  /** 현장 상세에서 열 때 미리 선택할 현장 */
  initialSiteId?: number
  onClose: () => void
}

export default function TaxCreateModal({ initialSiteId, onClose }: Props) {
  const createTax = useCreateTax()
  const [error, setError] = useState<string | null>(null)
  const inFlight = useRef(false)
  const [form] = Form.useForm<FormValues>()
  const handleValuesChange = useAutoTaxAmount(form)

  const close = () => {
    if (!inFlight.current && !createTax.isPending) onClose()
  }

  const submit = async () => {
    if (inFlight.current || createTax.isPending) return
    inFlight.current = true
    try {
      const values = await form.validateFields()
      const amountError = validateTaxAmounts(values.supplyAmount, values.taxAmount)
      if (amountError) {
        setError(amountError)
        return
      }
      const body: TaxInvoiceCreateRequest = {
        siteId: values.siteId,
        type: values.type,
        supplyAmount: values.supplyAmount,
        taxAmount: values.taxAmount,
        counterparty: values.counterparty.trim(),
        issueDate: values.issueDate.format('YYYY-MM-DD'),
        memo: values.memo?.trim() || undefined,
      }
      setError(null)
      await createTax.mutateAsync(body)
      onClose()
    } catch (cause: unknown) {
      if (cause && typeof cause === 'object' && 'errorFields' in cause) return
      const responseError = (cause as { response?: { data?: { error?: string } } })?.response?.data?.error
      setError(responseError ?? '세금계산서를 등록하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      inFlight.current = false
    }
  }

  return (
    <Modal
      title="세금계산서 등록"
      open
      okText="등록"
      cancelText="취소"
      confirmLoading={createTax.isPending}
      okButtonProps={{ disabled: createTax.isPending }}
      cancelButtonProps={{ disabled: createTax.isPending }}
      closable={!createTax.isPending}
      maskClosable={!createTax.isPending}
      keyboard={!createTax.isPending}
      destroyOnHidden
      onCancel={close}
      onOk={submit}
    >
      <Form
        form={form}
        layout="vertical"
        style={{ marginTop: 16 }}
        initialValues={initialSiteId != null ? { siteId: initialSiteId } : undefined}
        onValuesChange={handleValuesChange}
      >
        {error && <Alert type="error" message={error} showIcon style={{ marginBottom: 16 }} />}
        <Form.Item name="type" label="구분" rules={[{ required: true, message: '구분을 선택해주세요' }]}>
          <Select
            placeholder="매출 / 매입 선택"
            options={[
              { label: '매출', value: 'SALES' },
              { label: '매입', value: 'PURCHASE' },
            ]}
          />
        </Form.Item>
        <Form.Item name="siteId" label="현장" rules={[{ required: true, message: '현장을 선택해주세요' }]}>
          <SiteSelect placeholder="현장 검색 / 선택" />
        </Form.Item>
        <Form.Item name="counterparty" label="거래처" rules={[{ required: true, message: '거래처를 입력해주세요' }]}>
          <Input placeholder="거래처명" maxLength={200} />
        </Form.Item>
        <Form.Item name="supplyAmount" label="공급가액" rules={[{ required: true, message: '공급가액을 입력해주세요' }]}>
          <InputNumber<number>
            style={{ width: '100%' }}
            min={0}
            max={9_999_999_999_999.99}
            precision={2}
            formatter={(value) => `₩ ${value}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}
            parser={parseWon}
            placeholder="공급가액"
          />
        </Form.Item>
        <Form.Item
          name="taxAmount"
          label="세액"
          extra={TAX_AUTO_HINT}
          rules={[{ required: true, message: '세액을 입력해주세요' }]}
        >
          <InputNumber<number>
            style={{ width: '100%' }}
            min={0}
            max={9_999_999_999_999.99}
            precision={2}
            formatter={(value) => `₩ ${value}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}
            parser={parseWon}
            placeholder="세액"
          />
        </Form.Item>
        <Form.Item name="issueDate" label="발행일" rules={[{ required: true, message: '발행일을 선택해주세요' }]}>
          <DatePicker style={{ width: '100%' }} placeholder="발행일 선택" />
        </Form.Item>
        <Form.Item name="memo" label="메모">
          <Input.TextArea rows={3} placeholder="메모 (선택)" maxLength={16000} />
        </Form.Item>
      </Form>
    </Modal>
  )
}
