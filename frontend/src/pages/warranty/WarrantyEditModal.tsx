import { useRef, useState } from 'react'
import { Alert, DatePicker, Form, Input, InputNumber, Modal } from 'antd'
import dayjs from 'dayjs'
import type { Dayjs } from 'dayjs'
import { useUpdateWarranty } from '../../api/warranties.api'
import type { Warranty, WarrantyUpdateRequest } from '../../types'

interface FormValues {
  insuranceCompany: string
  policyNumber?: string
  coverageAmount?: number | null
  startDate: Dayjs
  endDate: Dayjs
  memo?: string
}

export function toWarrantyUpdateRequest(values: FormValues): WarrantyUpdateRequest {
  return {
    insuranceCompany: values.insuranceCompany.trim(),
    policyNumber: values.policyNumber?.trim() || null,
    coverageAmount: values.coverageAmount ?? null,
    startDate: values.startDate.format('YYYY-MM-DD'),
    endDate: values.endDate.format('YYYY-MM-DD'),
    memo: values.memo?.trim() || null,
  }
}

interface Props {
  warranty: Warranty
  onClose: () => void
}

export default function WarrantyEditModal({ warranty, onClose }: Props) {
  const [form] = Form.useForm<FormValues>()
  const updateWarranty = useUpdateWarranty()
  const [error, setError] = useState<string | null>(null)
  const inFlight = useRef(false)

  const close = () => {
    if (!inFlight.current && !updateWarranty.isPending) onClose()
  }

  const submit = async () => {
    if (warranty.ocrStatus === 'PENDING' || inFlight.current || updateWarranty.isPending) return
    inFlight.current = true
    try {
      const values = await form.validateFields()
      if (values.startDate.isAfter(values.endDate, 'day')) {
        setError('종료일은 시작일보다 빠를 수 없습니다.')
        return
      }
      setError(null)
      await updateWarranty.mutateAsync({ id: warranty.id, ...toWarrantyUpdateRequest(values) })
      onClose()
    } catch (cause: unknown) {
      if (cause && typeof cause === 'object' && 'errorFields' in cause) return
      const responseError = (cause as { response?: { data?: { error?: string } } })?.response?.data?.error
      setError(responseError ?? '하자보증보험을 수정하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      inFlight.current = false
    }
  }

  return (
    <Modal
      title={warranty.ocrStatus === 'FAILED' ? 'AI 실패 정보 수동 보정' : '하자보증보험 수정'}
      open
      onOk={submit}
      onCancel={close}
      okText="저장"
      cancelText="취소"
      okButtonProps={{ disabled: warranty.ocrStatus === 'PENDING' || updateWarranty.isPending }}
      confirmLoading={updateWarranty.isPending}
      closable={!updateWarranty.isPending}
      maskClosable={!updateWarranty.isPending}
      keyboard={!updateWarranty.isPending}
      destroyOnHidden
    >
      <Form
        form={form}
        layout="vertical"
        style={{ marginTop: 16 }}
        initialValues={{
          insuranceCompany: warranty.insuranceCompany ?? undefined,
          policyNumber: warranty.policyNumber ?? undefined,
          coverageAmount: warranty.coverageAmount ?? undefined,
          startDate: warranty.startDate ? dayjs(warranty.startDate) : undefined,
          endDate: warranty.endDate ? dayjs(warranty.endDate) : undefined,
          memo: warranty.memo ?? undefined,
        }}
      >
        {error && <Alert type="error" message={error} showIcon style={{ marginBottom: 16 }} />}
        {warranty.ocrStatus === 'FAILED' && <Alert type="info" message="AI가 읽지 못한 필수 정보를 입력하면 수기 보정으로 저장됩니다." showIcon style={{ marginBottom: 16 }} />}
        <Form.Item name="insuranceCompany" label="보험사" rules={[{ required: true, whitespace: true, message: '보험사를 입력하세요' }]}>
          <Input placeholder="보험사명" maxLength={200} />
        </Form.Item>
        <Form.Item name="policyNumber" label="증권번호"><Input placeholder="증권번호 (선택)" maxLength={100} /></Form.Item>
        <Form.Item name="coverageAmount" label="보증금액">
          <InputNumber<number> style={{ width: '100%' }} placeholder="보증금액 (선택)" min={0} max={Number.MAX_SAFE_INTEGER} precision={0} />
        </Form.Item>
        <Form.Item name="startDate" label="보증 시작일" rules={[{ required: true, message: '시작일을 선택하세요' }]}>
          <DatePicker style={{ width: '100%' }} placeholder="시작일 선택" />
        </Form.Item>
        <Form.Item name="endDate" label="보증 종료일" rules={[{ required: true, message: '종료일을 선택하세요' }]}>
          <DatePicker style={{ width: '100%' }} placeholder="종료일 선택" />
        </Form.Item>
        <Form.Item name="memo" label="메모"><Input.TextArea rows={3} placeholder="메모 (선택)" /></Form.Item>
      </Form>
    </Modal>
  )
}
