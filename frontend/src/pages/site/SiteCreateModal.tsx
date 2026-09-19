import { useState } from 'react'
import { Alert, DatePicker, Form, Input, Modal, Select } from 'antd'
import type { Dayjs } from 'dayjs'
import { useClients } from '../../api/clients.api'
import { useCreateSite } from '../../api/sites.api'
import type { SiteCreateRequest } from '../../types'

interface SiteFormValues {
  siteName: string
  clientId?: number
  address?: string
  startDate?: Dayjs
  endDate?: Dayjs
  memo?: string
}

export function toSiteCreateRequest(values: SiteFormValues): SiteCreateRequest {
  return {
    siteName: values.siteName.trim(),
    clientId: values.clientId,
    address: values.address?.trim() || undefined,
    startDate: values.startDate?.format('YYYY-MM-DD'),
    endDate: values.endDate?.format('YYYY-MM-DD'),
    memo: values.memo?.trim() || undefined,
  }
}

interface Props {
  open: boolean
  onClose: () => void
  onCreated: (siteId: number) => void
}

export default function SiteCreateModal({ open, onClose, onCreated }: Props) {
  const [form] = Form.useForm<SiteFormValues>()
  const { data: clients = [], isError: clientsError, isLoading: clientsLoading } = useClients(open)
  const createSite = useCreateSite()
  const [error, setError] = useState<string | null>(null)

  const close = () => {
    if (createSite.isPending) return
    setError(null)
    form.resetFields()
    onClose()
  }

  const submit = async () => {
    try {
      const values = await form.validateFields()
      if (values.startDate && values.endDate && values.startDate.isAfter(values.endDate, 'day')) {
        setError('종료일은 시작일보다 빠를 수 없습니다.')
        return
      }
      setError(null)
      const site = await createSite.mutateAsync(toSiteCreateRequest(values))
      form.resetFields()
      onClose()
      onCreated(site.id)
    } catch (cause: unknown) {
      if (cause && typeof cause === 'object' && 'errorFields' in cause) return
      const responseError = (cause as { response?: { data?: { error?: string } } })?.response?.data?.error
      setError(responseError ?? '현장을 생성하지 못했습니다. 다시 시도해 주세요.')
    }
  }

  return (
    <Modal
      title="현장 추가"
      open={open}
      onOk={submit}
      onCancel={close}
      okText="추가"
      cancelText="취소"
      confirmLoading={createSite.isPending}
      closable={!createSite.isPending}
      destroyOnHidden
    >
      <Form form={form} layout="vertical" style={{ marginTop: 20 }}>
        {error && <Alert type="error" message={error} showIcon style={{ marginBottom: 16 }} />}
        <Form.Item name="siteName" label="현장명" rules={[{ required: true, whitespace: true, message: '현장명을 입력하세요' }]}>
          <Input placeholder="현장명" maxLength={200} />
        </Form.Item>
        <Form.Item name="clientId" label="거래처">
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder={clientsError ? '거래처 목록을 불러오지 못했습니다' : '거래처 선택 (선택)'}
            disabled={clientsError}
            loading={clientsLoading}
            options={clients.map((client) => ({ value: client.id, label: client.companyName }))}
          />
        </Form.Item>
        <Form.Item name="address" label="주소"><Input placeholder="주소 (선택)" /></Form.Item>
        <Form.Item name="startDate" label="시작일"><DatePicker style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="endDate" label="종료일"><DatePicker style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="memo" label="메모"><Input.TextArea rows={3} placeholder="메모 (선택)" /></Form.Item>
      </Form>
    </Modal>
  )
}
