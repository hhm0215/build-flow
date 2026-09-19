import { useRef, useState } from 'react'
import { Alert, Modal } from 'antd'
import { useDeleteEstimate } from '../../api/estimates.api'
import type { Estimate } from '../../types'

interface Props {
  estimate: Estimate
  onClose: () => void
}

export default function EstimateDeleteModal({ estimate, onClose }: Props) {
  const deleteEstimate = useDeleteEstimate()
  const [error, setError] = useState<string | null>(null)
  const inFlight = useRef(false)

  const close = () => {
    if (!inFlight.current && !deleteEstimate.isPending) onClose()
  }

  const submit = async () => {
    if (estimate.status !== 'DRAFT' || inFlight.current || deleteEstimate.isPending) return
    inFlight.current = true
    setError(null)
    try {
      await deleteEstimate.mutateAsync(estimate.id)
      onClose()
    } catch (cause: unknown) {
      const responseError = (cause as { response?: { data?: { error?: string } } })?.response?.data?.error
      setError(responseError ?? '견적서를 삭제하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      inFlight.current = false
    }
  }

  return (
    <Modal
      title="견적서 삭제"
      open
      onOk={submit}
      onCancel={close}
      okText="삭제"
      okButtonProps={{ danger: true, disabled: estimate.status !== 'DRAFT' || deleteEstimate.isPending }}
      cancelText="취소"
      confirmLoading={deleteEstimate.isPending}
      closable={!deleteEstimate.isPending}
      maskClosable={!deleteEstimate.isPending}
      keyboard={!deleteEstimate.isPending}
    >
      <p>‘{estimate.title}’ 작성 중 견적서를 삭제할까요? 이 작업은 되돌릴 수 없습니다.</p>
      {error && <Alert type="error" message={error} showIcon />}
    </Modal>
  )
}
