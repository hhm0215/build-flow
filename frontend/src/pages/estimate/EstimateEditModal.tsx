import { useRef, useState } from 'react'
import { Alert, Button, DatePicker, Form, Input, InputNumber, Modal } from 'antd'
import dayjs from 'dayjs'
import type { Dayjs } from 'dayjs'
import { Plus, Trash2 } from 'lucide-react'
import { useUpdateEstimate } from '../../api/estimates.api'
import type { Estimate, EstimateUpdateRequest } from '../../types'
import { validateEstimateAmounts } from '../../utils/estimate'

interface ItemValues {
  itemName: string
  unit: string
  quantity: number
  unitPrice: number
}

interface FormValues {
  title: string
  estimateDate: Dayjs
  memo?: string
  items: ItemValues[]
}

export function toEstimateUpdateRequest(values: FormValues): EstimateUpdateRequest {
  return {
    title: values.title.trim(),
    estimateDate: values.estimateDate.format('YYYY-MM-DD'),
    memo: values.memo?.trim() || undefined,
    items: values.items.map((item) => ({
      itemName: item.itemName.trim(),
      unit: item.unit.trim(),
      quantity: item.quantity,
      unitPrice: item.unitPrice,
    })),
  }
}

interface Props {
  estimate: Estimate
  onClose: () => void
}

export default function EstimateEditModal({ estimate, onClose }: Props) {
  const [form] = Form.useForm<FormValues>()
  const updateEstimate = useUpdateEstimate()
  const [error, setError] = useState<string | null>(null)
  const inFlight = useRef(false)

  const close = () => {
    if (!inFlight.current && !updateEstimate.isPending) onClose()
  }

  const submit = async () => {
    if (estimate.status !== 'DRAFT' || inFlight.current || updateEstimate.isPending) return
    inFlight.current = true
    try {
      const values = await form.validateFields()
      const amountError = validateEstimateAmounts(values.items ?? [])
      if (amountError) {
        setError(amountError)
        return
      }
      setError(null)
      await updateEstimate.mutateAsync({ id: estimate.id, ...toEstimateUpdateRequest(values) })
      onClose()
    } catch (cause: unknown) {
      if (cause && typeof cause === 'object' && 'errorFields' in cause) return
      const responseError = (cause as { response?: { data?: { error?: string } } })?.response?.data?.error
      setError(responseError ?? '견적서를 수정하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      inFlight.current = false
    }
  }

  return (
    <Modal
      title="견적서 수정"
      open
      onOk={submit}
      onCancel={close}
      okText="저장"
      cancelText="취소"
      okButtonProps={{ disabled: estimate.status !== 'DRAFT' || updateEstimate.isPending }}
      confirmLoading={updateEstimate.isPending}
      closable={!updateEstimate.isPending}
      maskClosable={!updateEstimate.isPending}
      keyboard={!updateEstimate.isPending}
      destroyOnHidden
      width={720}
    >
      <Form
        form={form}
        layout="vertical"
        style={{ marginTop: 16 }}
        initialValues={{
          title: estimate.title,
          estimateDate: dayjs(estimate.estimateDate),
          memo: estimate.memo,
          items: estimate.items.map((item) => ({
            itemName: item.itemName,
            unit: item.unit,
            quantity: item.quantity,
            unitPrice: item.unitPrice,
          })),
        }}
      >
        {error && <Alert type="error" message={error} showIcon style={{ marginBottom: 16 }} />}
        <Form.Item name="title" label="견적 제목" rules={[{ required: true, whitespace: true, message: '견적 제목을 입력하세요' }]}>
          <Input placeholder="견적 제목" maxLength={200} />
        </Form.Item>
        <Form.Item name="estimateDate" label="견적일" rules={[{ required: true, message: '견적일을 선택하세요' }]}>
          <DatePicker style={{ width: '100%' }} placeholder="견적일 선택" />
        </Form.Item>
        <Form.Item name="memo" label="메모"><Input.TextArea rows={2} placeholder="메모 (선택)" maxLength={1000} /></Form.Item>
        <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 12 }}>
          견적 항목
        </div>
        <Form.List name="items">
          {(fields, { add, remove }) => (
            <>
              {fields.map(({ key, name, ...restField }) => (
                <div key={key} style={{ display: 'grid', gridTemplateColumns: '1fr 70px 80px 120px auto', gap: 8, alignItems: 'start' }}>
                  <Form.Item {...restField} name={[name, 'itemName']} rules={[{ required: true, whitespace: true, message: '품목명' }]}>
                    <Input placeholder="품목명" maxLength={200} />
                  </Form.Item>
                  <Form.Item {...restField} name={[name, 'unit']} rules={[{ required: true, whitespace: true, message: '단위' }]}>
                    <Input placeholder="단위" maxLength={20} />
                  </Form.Item>
                  <Form.Item {...restField} name={[name, 'quantity']} rules={[{ required: true, message: '수량' }, { type: 'number', min: 0.01, message: '수량은 0보다 커야 합니다' }]}>
                    <InputNumber style={{ width: '100%' }} min={0.01} precision={2} placeholder="수량" />
                  </Form.Item>
                  <Form.Item {...restField} name={[name, 'unitPrice']} rules={[{ required: true, message: '단가' }, { type: 'number', min: 0, message: '단가는 0 이상이어야 합니다' }]}>
                    <InputNumber style={{ width: '100%' }} min={0} precision={2} placeholder="단가" />
                  </Form.Item>
                  <Button type="text" danger aria-label="항목 삭제" icon={<Trash2 size={14} />} onClick={() => remove(name)} />
                </div>
              ))}
              <Button type="dashed" block icon={<Plus size={14} />} onClick={() => add({ unit: 'EA' })}>항목 추가</Button>
            </>
          )}
        </Form.List>
      </Form>
    </Modal>
  )
}
