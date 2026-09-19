import { useRef, useState } from 'react'
import { Alert, Modal } from 'antd'
import { useConfirmEstimate } from '../../api/estimates.api'
import type { Estimate } from '../../types'

interface Props {
  estimate: Estimate
  onClose: () => void
}

export default function EstimateConfirmModal({ estimate, onClose }: Props) {
  const confirmEstimate = useConfirmEstimate()
  const [error, setError] = useState<string | null>(null)
  const inFlight = useRef(false)

  const close = () => {
    if (!inFlight.current && !confirmEstimate.isPending) onClose()
  }

  const submit = async () => {
    if (inFlight.current || confirmEstimate.isPending) return
    inFlight.current = true
    setError(null)
    try {
      await confirmEstimate.mutateAsync(estimate.id)
      onClose()
    } catch (cause: unknown) {
      const responseError = (cause as { response?: { data?: { error?: string } } })?.response?.data?.error
      setError(responseError ?? '견적서를 확정하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      inFlight.current = false
    }
  }

  return (
    <Modal
      title="견적서 확정"
      open
      onOk={submit}
      onCancel={close}
      okText="확정"
      cancelText="취소"
      confirmLoading={confirmEstimate.isPending}
      closable={!confirmEstimate.isPending}
      okButtonProps={{ disabled: confirmEstimate.isPending }}
      cancelButtonProps={{ disabled: confirmEstimate.isPending }}
      maskClosable={!confirmEstimate.isPending}
      keyboard={!confirmEstimate.isPending}
    >
      <p>‘{estimate.title}’ 견적서를 확정할까요? 확정 후에는 수정할 수 없습니다.</p>
      {error && <Alert type="error" message={error} showIcon />}
    </Modal>
  )
}
