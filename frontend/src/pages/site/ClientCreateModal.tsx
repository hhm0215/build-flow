import { useRef, useState } from 'react'
import { Alert, Form, Input, Modal } from 'antd'
import { useCreateClient } from '../../api/clients.api'
import type { Client, ClientCreateRequest } from '../../types'

interface ClientFormValues {
  companyName: string
  representative?: string
  businessNo?: string
  phone?: string
  email?: string
  address?: string
  memo?: string
}

export function toClientCreateRequest(values: ClientFormValues): ClientCreateRequest {
  const optional = (value?: string) => value?.trim() || undefined
  return {
    companyName: values.companyName.trim(),
    representative: optional(values.representative),
    businessNo: optional(values.businessNo),
    phone: optional(values.phone),
    email: optional(values.email),
    address: optional(values.address),
    memo: optional(values.memo),
  }
}

interface Props {
  open: boolean
  onClose: () => void
  onCreated: (client: Client) => void
}

export default function ClientCreateModal({ open, onClose, onCreated }: Props) {
  const [form] = Form.useForm<ClientFormValues>()
  const createClient = useCreateClient()
  const [error, setError] = useState<string | null>(null)
  const inFlight = useRef(false)

  const close = () => {
    if (inFlight.current || createClient.isPending) return
    setError(null)
    form.resetFields()
    onClose()
  }

  const submit = async () => {
    if (inFlight.current || createClient.isPending) return
    inFlight.current = true
    try {
      const values = await form.validateFields()
      setError(null)
      const client = await createClient.mutateAsync(toClientCreateRequest(values))
      form.resetFields()
      onCreated(client)
    } catch (cause: unknown) {
      if (cause && typeof cause === 'object' && 'errorFields' in cause) return
      const responseError = (cause as { response?: { data?: { error?: string } } })?.response?.data?.error
      setError(responseError ?? '거래처를 등록하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      inFlight.current = false
    }
  }

  return (
    <Modal
      title="거래처 등록"
      open={open}
      onOk={submit}
      onCancel={close}
      okText="등록"
      cancelText="취소"
      confirmLoading={createClient.isPending}
      closable={!createClient.isPending}
      maskClosable={!createClient.isPending}
      keyboard={!createClient.isPending}
      destroyOnHidden
      width={600}
    >
      <Form form={form} layout="vertical" style={{ marginTop: 20 }}>
        {error && <Alert type="error" message={error} showIcon style={{ marginBottom: 16 }} />}
        <Form.Item name="companyName" label="업체명" rules={[{ required: true, whitespace: true, message: '업체명을 입력하세요' }]}>
          <Input placeholder="업체명" maxLength={200} />
        </Form.Item>
        <Form.Item name="representative" label="대표자"><Input placeholder="대표자 (선택)" maxLength={50} /></Form.Item>
        <Form.Item name="businessNo" label="사업자등록번호"><Input placeholder="사업자등록번호 (선택)" maxLength={20} /></Form.Item>
        <Form.Item name="phone" label="전화번호"><Input placeholder="전화번호 (선택)" maxLength={20} /></Form.Item>
        <Form.Item name="email" label="이메일" rules={[{ type: 'email', message: '올바른 이메일 주소를 입력하세요' }]}>
          <Input placeholder="이메일 (선택)" maxLength={100} />
        </Form.Item>
        <Form.Item name="address" label="주소"><Input placeholder="주소 (선택)" maxLength={500} /></Form.Item>
        <Form.Item name="memo" label="메모"><Input.TextArea placeholder="메모 (선택)" rows={3} /></Form.Item>
      </Form>
    </Modal>
  )
}
