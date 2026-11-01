import { useRef, useState } from 'react'
import { Alert, DatePicker, Form, Input, InputNumber, Modal } from 'antd'
import dayjs from 'dayjs'
import type { Dayjs } from 'dayjs'
import SiteSelect from '../../components/SiteSelect'
import { useCreatePurchase } from '../../api/purchases.api'
import type { PurchaseCreateRequest } from '../../types'
import { validatePurchaseAmount } from '../../utils/purchase'
import { todayKst } from '../../utils/date'

interface FormValues {
  siteId: number
  itemName: string
  quantity: number
  unitPrice: number
  supplier?: string
  purchaseDate?: Dayjs | null
  memo?: string
}

interface Props {
  /** 현장 상세에서 열 때 미리 선택할 현장 */
  initialSiteId?: number
  onClose: () => void
}

export function toPurchaseCreateRequest(values: FormValues): PurchaseCreateRequest {
  return {
    siteId: values.siteId,
    itemName: values.itemName.trim(),
    quantity: values.quantity,
    unitPrice: values.unitPrice,
    supplier: values.supplier?.trim() || undefined,
    memo: values.memo?.trim() || undefined,
    purchaseDate: values.purchaseDate ? values.purchaseDate.format('YYYY-MM-DD') : undefined,
  }
}

export default function PurchaseCreateModal({ initialSiteId, onClose }: Props) {
  const [form] = Form.useForm<FormValues>()
  const createMutation = useCreatePurchase()
  const [error, setError] = useState<string | null>(null)
  const inFlight = useRef(false)

  const close = () => {
    if (!inFlight.current && !createMutation.isPending) onClose()
  }

  const submit = async () => {
    if (inFlight.current || createMutation.isPending) return
    inFlight.current = true
    try {
      const values = await form.validateFields()
      const amountError = validatePurchaseAmount(values.quantity, values.unitPrice)
      if (amountError) {
        setError(amountError)
        return
      }
      setError(null)
      await createMutation.mutateAsync(toPurchaseCreateRequest(values))
      onClose()
    } catch (cause: unknown) {
      if (cause && typeof cause === 'object' && 'errorFields' in cause) return
      const responseError = (cause as { response?: { data?: { error?: string } } })?.response?.data?.error
      setError(responseError ?? '매입 내역을 등록하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      inFlight.current = false
    }
  }

  return (
    <Modal
      title="매입 등록"
      open
      onOk={submit}
      onCancel={close}
      okText="등록"
      cancelText="취소"
      okButtonProps={{ disabled: createMutation.isPending }}
      cancelButtonProps={{ disabled: createMutation.isPending }}
      confirmLoading={createMutation.isPending}
      closable={!createMutation.isPending}
      maskClosable={!createMutation.isPending}
      keyboard={!createMutation.isPending}
      destroyOnHidden
    >
      <Form
        form={form}
        layout="vertical"
        style={{ marginTop: 16 }}
        // 매입일은 기간 집계 기준이라 오늘(KST)을 기본으로 둔다. 서버는 선택값이므로 지울 수는 있다.
        initialValues={{ siteId: initialSiteId, purchaseDate: dayjs(todayKst()) }}
      >
        {error && <Alert type="error" message={error} showIcon style={{ marginBottom: 16 }} />}
        <Form.Item
          name="siteId"
          label="현장"
          rules={[{ required: true, message: '현장을 선택하세요' }]}
        >
          <SiteSelect placeholder="현장 검색 / 선택" />
        </Form.Item>
        <Form.Item
          name="itemName"
          label="품목명"
          rules={[{ required: true, whitespace: true, message: '품목명을 입력하세요' }]}
        >
          <Input placeholder="품목명" maxLength={200} />
        </Form.Item>
        <Form.Item
          name="quantity"
          label="수량"
          rules={[{ required: true, message: '수량을 입력하세요' }]}
        >
          <InputNumber<number>
            style={{ width: '100%' }} min={1} max={2_147_483_647} precision={0} placeholder="수량"
          />
        </Form.Item>
        <Form.Item
          name="unitPrice"
          label="단가"
          rules={[{ required: true, message: '단가를 입력하세요' }]}
        >
          <InputNumber<number>
            style={{ width: '100%' }}
            min={0}
            max={9_999_999_999.99}
            precision={2}
            placeholder="단가"
            formatter={(v) => `${v}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}
            parser={(v) => Number(v?.replace(/,/g, '') ?? 0)}
          />
        </Form.Item>
        <Form.Item name="supplier" label="거래처">
          <Input placeholder="거래처명" maxLength={200} />
        </Form.Item>
        <Form.Item name="purchaseDate" label="매입일">
          <DatePicker style={{ width: '100%' }} placeholder="매입일 선택" />
        </Form.Item>
        <Form.Item name="memo" label="메모">
          <Input.TextArea rows={3} placeholder="메모" maxLength={16000} />
        </Form.Item>
      </Form>
    </Modal>
  )
}
