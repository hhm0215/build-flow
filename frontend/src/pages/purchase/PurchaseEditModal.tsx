import { useRef, useState } from 'react'
import { Alert, DatePicker, Form, Input, InputNumber, Modal } from 'antd'
import dayjs from 'dayjs'
import type { Dayjs } from 'dayjs'
import { useUpdatePurchase } from '../../api/purchases.api'
import type { Purchase, PurchaseUpdateRequest } from '../../types'
import { validatePurchaseAmount } from '../../utils/purchase'

interface FormValues {
  itemName: string
  quantity: number
  unitPrice: number
  supplier?: string
  purchaseDate?: Dayjs | null
  memo?: string
}

export function toPurchaseUpdateRequest(values: FormValues): PurchaseUpdateRequest {
  return {
    itemName: values.itemName.trim(),
    quantity: values.quantity,
    unitPrice: values.unitPrice,
    supplier: values.supplier?.trim() || undefined,
    purchaseDate: values.purchaseDate?.format('YYYY-MM-DD') || undefined,
    memo: values.memo?.trim() || undefined,
  }
}

interface Props {
  purchase: Purchase
  onClose: () => void
}

export default function PurchaseEditModal({ purchase, onClose }: Props) {
  const [form] = Form.useForm<FormValues>()
  const updatePurchase = useUpdatePurchase()
  const [error, setError] = useState<string | null>(null)
  const inFlight = useRef(false)

  const close = () => {
    if (!inFlight.current && !updatePurchase.isPending) onClose()
  }

  const submit = async () => {
    if (inFlight.current || updatePurchase.isPending) return
    inFlight.current = true
    try {
      const values = await form.validateFields()
      const amountError = validatePurchaseAmount(values.quantity, values.unitPrice)
      if (amountError) {
        setError(amountError)
        return
      }
      setError(null)
      await updatePurchase.mutateAsync({ id: purchase.id, ...toPurchaseUpdateRequest(values) })
      onClose()
    } catch (cause: unknown) {
      if (cause && typeof cause === 'object' && 'errorFields' in cause) return
      const responseError = (cause as { response?: { data?: { error?: string } } })?.response?.data?.error
      setError(responseError ?? '매입 내역을 수정하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      inFlight.current = false
    }
  }

  return (
    <Modal
      title="매입 수정"
      open
      onOk={submit}
      onCancel={close}
      okText="저장"
      cancelText="취소"
      okButtonProps={{ disabled: updatePurchase.isPending }}
      cancelButtonProps={{ disabled: updatePurchase.isPending }}
      confirmLoading={updatePurchase.isPending}
      closable={!updatePurchase.isPending}
      maskClosable={!updatePurchase.isPending}
      keyboard={!updatePurchase.isPending}
      destroyOnHidden
    >
      <Form
        form={form}
        layout="vertical"
        style={{ marginTop: 16 }}
        initialValues={{
          itemName: purchase.itemName,
          quantity: purchase.quantity,
          unitPrice: purchase.unitPrice,
          supplier: purchase.supplier ?? '',
          purchaseDate: purchase.purchaseDate ? dayjs(purchase.purchaseDate) : null,
          memo: purchase.memo ?? '',
        }}
      >
        {error && <Alert type="error" message={error} showIcon style={{ marginBottom: 16 }} />}
        <Form.Item name="itemName" label="품목명" rules={[{ required: true, whitespace: true, message: '품목명을 입력하세요' }]}>
          <Input placeholder="품목명" maxLength={200} />
        </Form.Item>
        <Form.Item name="quantity" label="수량" rules={[{ required: true, message: '수량을 입력하세요' }, { type: 'integer', min: 1, message: '수량은 1 이상의 정수여야 합니다' }]}>
          <InputNumber<number> style={{ width: '100%' }} min={1} max={2_147_483_647} precision={0} placeholder="수량" />
        </Form.Item>
        <Form.Item name="unitPrice" label="단가" rules={[{ required: true, message: '단가를 입력하세요' }, { type: 'number', min: 0, max: 9_999_999_999.99, message: '단가 범위를 확인하세요' }]}>
          <InputNumber<number>
            style={{ width: '100%' }} min={0} max={9_999_999_999.99} precision={2} placeholder="단가"
            formatter={(value) => `${value}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}
            parser={(value) => Number(value?.replace(/,/g, '') ?? 0)}
          />
        </Form.Item>
        <Form.Item name="supplier" label="공급업체">
          <Input placeholder="공급업체명" maxLength={200} />
        </Form.Item>
        <Form.Item name="purchaseDate" label="매입일">
          <DatePicker style={{ width: '100%' }} placeholder="매입일 선택" />
        </Form.Item>
        <Form.Item name="memo" label="메모">
          <Input.TextArea rows={3} placeholder="메모" />
        </Form.Item>
      </Form>
    </Modal>
  )
}
