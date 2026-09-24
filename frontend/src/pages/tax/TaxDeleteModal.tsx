import { useRef, useState } from 'react'
import { Alert, Modal } from 'antd'
import { useDeleteTax } from '../../api/taxes.api'
import type { TaxInvoice } from '../../types'

interface Props {
  invoice: TaxInvoice
  onClose: () => void
}

export default function TaxDeleteModal({ invoice, onClose }: Props) {
  const deleteTax = useDeleteTax()
  const [error, setError] = useState<string | null>(null)
  const inFlight = useRef(false)

  const close = () => {
    if (!inFlight.current && !deleteTax.isPending) onClose()
  }

  const submit = async () => {
    if (inFlight.current || deleteTax.isPending) return
    inFlight.current = true
    setError(null)
    try {
      await deleteTax.mutateAsync({ id: invoice.id, siteId: invoice.siteId })
      onClose()
    } catch (cause: unknown) {
      const responseError = (cause as { response?: { data?: { error?: string } } })?.response?.data?.error
      setError(responseError ?? '세금계산서를 삭제하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      inFlight.current = false
    }
  }

  return (
    <Modal
      title="세금계산서 삭제"
      open
      onOk={submit}
      onCancel={close}
      okText="삭제"
      okButtonProps={{ danger: true, disabled: deleteTax.isPending }}
      cancelButtonProps={{ disabled: deleteTax.isPending }}
      cancelText="취소"
      confirmLoading={deleteTax.isPending}
      closable={!deleteTax.isPending}
      maskClosable={!deleteTax.isPending}
      keyboard={!deleteTax.isPending}
    >
      <p>‘{invoice.counterparty ?? '거래처 미지정'}’ 세금계산서를 삭제할까요? 이 작업은 되돌릴 수 없습니다.</p>
      {error && <Alert type="error" message={error} showIcon />}
    </Modal>
  )
}
