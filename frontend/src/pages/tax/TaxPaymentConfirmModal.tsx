import { useRef, useState } from 'react'
import { Alert, DatePicker, Form, Modal } from 'antd'
import dayjs from 'dayjs'
import type { Dayjs } from 'dayjs'
import { useConfirmPayment } from '../../api/taxes.api'
import type { TaxInvoice } from '../../types'

interface FormValues {
  paymentDate: Dayjs
}

interface Props {
  invoice: TaxInvoice
  onClose: () => void
}

export default function TaxPaymentConfirmModal({ invoice, onClose }: Props) {
  const [form] = Form.useForm<FormValues>()
  const confirmPayment = useConfirmPayment()
  const [error, setError] = useState<string | null>(null)
  const inFlight = useRef(false)

  const close = () => {
    if (!inFlight.current && !confirmPayment.isPending) onClose()
  }

  const submit = async () => {
    if (invoice.type !== 'SALES' || invoice.paymentConfirmed || inFlight.current || confirmPayment.isPending) return
    inFlight.current = true
    try {
      const { paymentDate } = await form.validateFields()
      setError(null)
      await confirmPayment.mutateAsync({ id: invoice.id, paymentDate: paymentDate.format('YYYY-MM-DD') })
      onClose()
    } catch (cause: unknown) {
      if (cause && typeof cause === 'object' && 'errorFields' in cause) return
      const responseError = (cause as { response?: { data?: { error?: string } } })?.response?.data?.error
      setError(responseError ?? '입금 확인에 실패했습니다. 다시 시도해 주세요.')
    } finally {
      inFlight.current = false
    }
  }

  return (
    <Modal
      title="입금 확인"
      open
      onOk={submit}
      onCancel={close}
      okText="입금 확인"
      cancelText="취소"
      confirmLoading={confirmPayment.isPending}
      okButtonProps={{ disabled: invoice.type !== 'SALES' || invoice.paymentConfirmed || confirmPayment.isPending }}
      cancelButtonProps={{ disabled: confirmPayment.isPending }}
      closable={!confirmPayment.isPending}
      maskClosable={!confirmPayment.isPending}
      keyboard={!confirmPayment.isPending}
      destroyOnHidden
    >
      <p>‘{invoice.counterparty || '거래처 미입력'}’ 매출 세금계산서 ₩{invoice.totalAmount.toLocaleString('ko-KR')}의 입금을 확인할까요?</p>
      <Form form={form} layout="vertical" initialValues={{ paymentDate: dayjs() }}>
        <Form.Item name="paymentDate" label="실제 입금일" rules={[{ required: true, message: '입금일을 선택하세요' }]}>
          <DatePicker style={{ width: '100%' }} placeholder="입금일 선택" />
        </Form.Item>
      </Form>
      {error && <Alert type="error" message={error} showIcon />}
    </Modal>
  )
}
