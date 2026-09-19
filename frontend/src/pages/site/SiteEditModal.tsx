import { useRef, useState } from 'react'
import { Alert, DatePicker, Form, Input, Modal, Select } from 'antd'
import dayjs from 'dayjs'
import type { Dayjs } from 'dayjs'
import { useClients } from '../../api/clients.api'
import { useUpdateSite } from '../../api/sites.api'
import type { Site, SiteUpdateRequest } from '../../types'

interface SiteEditValues {
  siteName: string
  clientId?: number
  address?: string
  startDate?: Dayjs
  endDate?: Dayjs
  memo?: string
}

export function toSiteUpdateRequest(values: SiteEditValues): SiteUpdateRequest {
  const optional = (value?: string) => value?.trim() || null
  return {
    siteName: values.siteName.trim(),
    clientId: values.clientId ?? null,
    address: optional(values.address),
    startDate: values.startDate?.format('YYYY-MM-DD') ?? null,
    endDate: values.endDate?.format('YYYY-MM-DD') ?? null,
    memo: optional(values.memo),
  }
}

interface Props {
  site: Site
  onClose: () => void
}

export default function SiteEditModal({ site, onClose }: Props) {
  const [form] = Form.useForm<SiteEditValues>()
  const { data: clients = [], isLoading: clientsLoading, isError: clientsError } = useClients()
  const updateSite = useUpdateSite()
  const [error, setError] = useState<string | null>(null)
  const inFlight = useRef(false)
  const clientOptions = site.client && !clients.some((client) => client.id === site.client?.id)
    ? [...clients, site.client]
    : clients

  const close = () => {
    if (inFlight.current || updateSite.isPending) return
    onClose()
  }

  const submit = async () => {
    if (inFlight.current || updateSite.isPending) return
    inFlight.current = true
    try {
      const values = await form.validateFields()
      if (values.startDate && values.endDate && values.startDate.isAfter(values.endDate, 'day')) {
        setError('종료일은 시작일보다 빠를 수 없습니다.')
        return
      }
      setError(null)
      await updateSite.mutateAsync({ id: site.id, ...toSiteUpdateRequest(values) })
      onClose()
    } catch (cause: unknown) {
      if (cause && typeof cause === 'object' && 'errorFields' in cause) return
      const responseError = (cause as { response?: { data?: { error?: string } } })?.response?.data?.error
      setError(responseError ?? '현장을 수정하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      inFlight.current = false
    }
  }

  return (
    <Modal
      title="현장 수정"
      open
      onOk={submit}
      onCancel={close}
      okText="저장"
      cancelText="취소"
      confirmLoading={updateSite.isPending}
      closable={!updateSite.isPending}
      maskClosable={!updateSite.isPending}
      keyboard={!updateSite.isPending}
      destroyOnHidden
    >
      <Form
        form={form}
        layout="vertical"
        style={{ marginTop: 20 }}
        initialValues={{
          siteName: site.siteName,
          clientId: site.client?.id,
          address: site.address ?? undefined,
          startDate: site.startDate ? dayjs(site.startDate) : undefined,
          endDate: site.endDate ? dayjs(site.endDate) : undefined,
          memo: site.memo ?? undefined,
        }}
      >
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
            options={clientOptions.map((client) => ({ value: client.id, label: client.companyName }))}
          />
        </Form.Item>
        <Form.Item name="address" label="주소"><Input placeholder="주소 (선택)" maxLength={500} /></Form.Item>
        <Form.Item name="startDate" label="시작일"><DatePicker style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="endDate" label="종료일"><DatePicker style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="memo" label="메모"><Input.TextArea rows={3} placeholder="메모 (선택)" /></Form.Item>
      </Form>
    </Modal>
  )
}
