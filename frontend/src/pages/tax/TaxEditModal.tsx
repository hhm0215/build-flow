import { useRef, useState } from 'react'
import { Alert, DatePicker, Form, Input, InputNumber, Modal, Select } from 'antd'
import dayjs from 'dayjs'
import type { Dayjs } from 'dayjs'
import { useUpdateTax } from '../../api/taxes.api'
import type { TaxInvoice, TaxInvoiceType, TaxInvoiceUpdateRequest } from '../../types'
import { validateTaxAmounts } from '../../utils/tax'

interface FormValues {
  type: TaxInvoiceType
  supplyAmount: number
  taxAmount: number
  counterparty?: string
  issueDate?: Dayjs | null
  memo?: string
}

export function toTaxUpdateRequest(values: FormValues): TaxInvoiceUpdateRequest {
  return {
    type: values.type,
    supplyAmount: values.supplyAmount,
    taxAmount: values.taxAmount,
    counterparty: values.counterparty?.trim() || undefined,
    issueDate: values.issueDate?.format('YYYY-MM-DD') || undefined,
    memo: values.memo?.trim() || undefined,
  }
}

interface Props {
  invoice: TaxInvoice
  onClose: () => void
}

export default function TaxEditModal({ invoice, onClose }: Props) {
  const [form] = Form.useForm<FormValues>()
  const updateTax = useUpdateTax()
  const [error, setError] = useState<string | null>(null)
  const inFlight = useRef(false)

  const close = () => {
    if (!inFlight.current && !updateTax.isPending) onClose()
  }

  const submit = async () => {
    if (inFlight.current || updateTax.isPending) return
    inFlight.current = true
    try {
      const values = await form.validateFields()
      const amountError = validateTaxAmounts(values.supplyAmount, values.taxAmount)
      if (amountError) {
        setError(amountError)
        return
      }
      setError(null)
      await updateTax.mutateAsync({ id: invoice.id, ...toTaxUpdateRequest(values) })
      onClose()
    } catch (cause: unknown) {
      if (cause && typeof cause === 'object' && 'errorFields' in cause) return
      const responseError = (cause as { response?: { data?: { error?: string } } })?.response?.data?.error
      setError(responseError ?? '세금계산서를 수정하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      inFlight.current = false
    }
  }

  return (
    <Modal
      title="세금계산서 수정"
      open
      onOk={submit}
      onCancel={close}
      okText="저장"
      cancelText="취소"
      okButtonProps={{ disabled: updateTax.isPending }}
      cancelButtonProps={{ disabled: updateTax.isPending }}
      confirmLoading={updateTax.isPending}
      closable={!updateTax.isPending}
      maskClosable={!updateTax.isPending}
      keyboard={!updateTax.isPending}
      destroyOnHidden
    >
      <Form
        form={form}
        layout="vertical"
        style={{ marginTop: 16 }}
        initialValues={{
          type: invoice.type,
          supplyAmount: invoice.supplyAmount,
          taxAmount: invoice.taxAmount,
          counterparty: invoice.counterparty ?? '',
          issueDate: invoice.issueDate ? dayjs(invoice.issueDate) : null,
          memo: invoice.memo ?? '',
        }}
      >
        {error && <Alert type="error" message={error} showIcon style={{ marginBottom: 16 }} />}
        <Form.Item name="type" label="구분" rules={[{ required: true, message: '구분을 선택해주세요' }]}>
          <Select options={[{ label: '매출', value: 'SALES' }, { label: '매입', value: 'PURCHASE' }]} />
        </Form.Item>
        <Form.Item name="counterparty" label="거래처">
          <Input placeholder="거래처명" maxLength={200} />
        </Form.Item>
        <Form.Item name="supplyAmount" label="공급가액" rules={[{ required: true, message: '공급가액을 입력해주세요' }, { type: 'number', min: 0, max: 9_999_999_999_999.99, message: '공급가액 범위를 확인해주세요' }]}>
          <InputNumber<number> style={{ width: '100%' }} min={0} max={9_999_999_999_999.99} precision={2} placeholder="공급가액" />
        </Form.Item>
        <Form.Item name="taxAmount" label="세액" rules={[{ required: true, message: '세액을 입력해주세요' }, { type: 'number', min: 0, max: 9_999_999_999_999.99, message: '세액 범위를 확인해주세요' }]}>
          <InputNumber<number> style={{ width: '100%' }} min={0} max={9_999_999_999_999.99} precision={2} placeholder="세액" />
        </Form.Item>
        <Form.Item name="issueDate" label="발행일">
          <DatePicker style={{ width: '100%' }} placeholder="발행일 선택" />
        </Form.Item>
        <Form.Item name="memo" label="메모">
          <Input.TextArea rows={3} placeholder="메모" />
        </Form.Item>
      </Form>
    </Modal>
  )
}
