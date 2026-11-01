import { useEffect, useRef, useState } from 'react'
import { Alert, Button, DatePicker, Form, Input, InputNumber, Modal, Popconfirm } from 'antd'
import dayjs from 'dayjs'
import type { Dayjs } from 'dayjs'
import { useCancelTaxPayment, useRecordTaxPayment, useTaxPayments } from '../../api/taxes.api'
import type { TaxInvoice, TaxPayment } from '../../types'
import {
  PAYMENT_STATUS_META, formatWonInput, parseWonInput, paymentStatusOf, remainingOf, validatePaymentAmount,
} from '../../utils/taxPayment'

interface FormValues {
  amount: number
  paymentDate: Dayjs
  memo?: string
}

interface Props {
  invoice: TaxInvoice
  onClose: () => void
}

const won = (value: number) => `₩${value.toLocaleString('ko-KR')}`

function responseError(cause: unknown, fallback: string): string {
  return (cause as { response?: { data?: { error?: string } } })?.response?.data?.error ?? fallback
}

/** 매출 세금계산서 입금 관리: 분할 입금 기록(기본값 = 잔액)과 오입금 취소. */
export default function TaxPaymentsModal({ invoice, onClose }: Props) {
  const [form] = Form.useForm<FormValues>()
  const payments = useTaxPayments(invoice.id)
  const recordPayment = useRecordTaxPayment()
  const cancelPayment = useCancelTaxPayment()
  const [error, setError] = useState<string | null>(null)
  const inFlight = useRef(false)
  const remaining = remainingOf(invoice)
  const status = paymentStatusOf(invoice) ?? 'UNPAID'
  const busy = recordPayment.isPending || cancelPayment.isPending

  // 기록·취소 뒤 목록이 다시 조회되면 입금액 기본값을 새 잔액으로 맞춘다.
  useEffect(() => {
    if (remaining > 0) form.setFieldsValue({ amount: remaining })
  }, [form, remaining])

  const close = () => {
    if (!inFlight.current && !busy) onClose()
  }

  const submit = async () => {
    if (inFlight.current || busy || remaining <= 0) return
    inFlight.current = true
    try {
      const values = await form.validateFields()
      const amountError = validatePaymentAmount(values.amount, remaining)
      if (amountError) {
        setError(amountError)
        return
      }
      setError(null)
      await recordPayment.mutateAsync({
        taxId: invoice.id,
        amount: values.amount,
        paymentDate: values.paymentDate.format('YYYY-MM-DD'),
        memo: values.memo?.trim() || undefined,
      })
      form.setFieldsValue({ memo: undefined })
    } catch (cause: unknown) {
      if (cause && typeof cause === 'object' && 'errorFields' in cause) return
      setError(responseError(cause, '입금을 기록하지 못했습니다. 다시 시도해 주세요.'))
    } finally {
      inFlight.current = false
    }
  }

  const cancel = async (payment: TaxPayment) => {
    if (inFlight.current || busy) return
    inFlight.current = true
    try {
      setError(null)
      await cancelPayment.mutateAsync({ taxId: invoice.id, paymentId: payment.id })
    } catch (cause: unknown) {
      setError(responseError(cause, '입금을 취소하지 못했습니다. 다시 시도해 주세요.'))
    } finally {
      inFlight.current = false
    }
  }

  return (
    <Modal
      title="입금 관리"
      open
      onCancel={close}
      footer={<Button onClick={close} disabled={busy}>닫기</Button>}
      closable={!busy}
      maskClosable={!busy}
      keyboard={!busy}
      destroyOnHidden
    >
      <p style={{ marginBottom: 12 }}>
        ‘{invoice.counterparty || '거래처 미입력'}’ 매출 세금계산서
        <span style={{ marginLeft: 8, fontWeight: 600, color: PAYMENT_STATUS_META[status].color }}>
          {PAYMENT_STATUS_META[status].label}
        </span>
      </p>
      <div className="bf-info-grid"
        style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 8, marginBottom: 16 }}
        aria-label="입금 요약"
      >
        <div><div style={{ fontSize: 11, opacity: 0.7 }}>합계</div><strong>{won(invoice.totalAmount)}</strong></div>
        <div><div style={{ fontSize: 11, opacity: 0.7 }}>입금 합계</div><strong>{won(invoice.paidAmount ?? 0)}</strong></div>
        <div>
          <div style={{ fontSize: 11, opacity: 0.7 }}>잔액</div>
          <strong data-testid="payment-remaining" style={{ color: remaining > 0 ? '#ef4444' : undefined }}>
            {won(remaining)}
          </strong>
        </div>
      </div>

      <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 6 }}>입금 내역</div>
      {payments.isError ? (
        <Alert type="error" showIcon message="입금 내역을 불러오지 못했습니다." style={{ marginBottom: 12 }} />
      ) : payments.isLoading ? (
        <div style={{ fontSize: 12, opacity: 0.7, marginBottom: 12 }}>불러오는 중…</div>
      ) : (payments.data ?? []).length === 0 ? (
        <div style={{ fontSize: 12, opacity: 0.7, marginBottom: 12 }}>기록된 입금이 없습니다.</div>
      ) : (
        <ul style={{ listStyle: 'none', padding: 0, margin: '0 0 12px' }}>
          {(payments.data ?? []).map((payment) => (
            <li
              key={payment.id}
              style={{
                display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8,
                padding: '6px 0', borderBottom: '1px solid var(--border)',
                opacity: payment.canceled ? 0.55 : 1,
              }}
            >
              <div style={{ minWidth: 0 }}>
                <div style={{ fontSize: 13, textDecoration: payment.canceled ? 'line-through' : undefined }}>
                  {payment.paymentDate} · {won(payment.amount)}
                </div>
                {(payment.memo || payment.canceled) && (
                  <div style={{ fontSize: 11, opacity: 0.75 }}>
                    {payment.canceled
                      ? `취소됨 ${payment.canceledAt?.slice(0, 16).replace('T', ' ') ?? ''}${payment.cancelReason ? ` · ${payment.cancelReason}` : ''}`
                      : payment.memo}
                  </div>
                )}
              </div>
              {!payment.canceled && (
                <Popconfirm
                  title="이 입금을 취소할까요?"
                  description="기록은 남고 입금 합계에서만 제외됩니다."
                  okText="입금 취소"
                  cancelText="닫기"
                  onConfirm={() => cancel(payment)}
                >
                  <Button size="small" danger disabled={busy} aria-label={`${payment.paymentDate} 입금 취소`}>
                    취소
                  </Button>
                </Popconfirm>
              )}
            </li>
          ))}
        </ul>
      )}

      {remaining > 0 && (
        <Form
          form={form}
          layout="vertical"
          initialValues={{ amount: remaining, paymentDate: dayjs() }}
        >
          <Form.Item name="amount" label="입금액" rules={[{ required: true, message: '입금액을 입력하세요' }]}>
            <InputNumber<number>
              style={{ width: '100%' }}
              min={0.01}
              max={remaining}
              // precision={2}는 정수 기본값을 660000.00으로 보이게 한다. 소수 자릿수는 validatePaymentAmount가 반올림 없이 검증한다.
              formatter={formatWonInput}
              parser={parseWonInput}
              placeholder="입금액"
            />
          </Form.Item>
          <Form.Item name="paymentDate" label="실제 입금일" rules={[{ required: true, message: '입금일을 선택하세요' }]}>
            <DatePicker style={{ width: '100%' }} placeholder="입금일 선택" />
          </Form.Item>
          <Form.Item name="memo" label="메모">
            <Input placeholder="예: 2차 기성금 (선택)" maxLength={500} />
          </Form.Item>
          <Button type="primary" onClick={submit} loading={recordPayment.isPending} disabled={busy}>
            입금 기록
          </Button>
        </Form>
      )}
      {error && <Alert type="error" message={error} showIcon style={{ marginTop: 12 }} />}
    </Modal>
  )
}
