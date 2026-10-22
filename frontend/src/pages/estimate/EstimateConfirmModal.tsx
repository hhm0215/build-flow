import { useRef, useState } from 'react'
import { Alert, Modal } from 'antd'
import { useConfirmEstimate } from '../../api/estimates.api'
import type { Estimate } from '../../types'

interface Props {
  estimate: Estimate
  /** 확정하면 대체될 같은 수정 체인의 현재 확정본 */
  supersedes?: Estimate | null
  onClose: () => void
}

export default function EstimateConfirmModal({ estimate, supersedes, onClose }: Props) {
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
      {supersedes && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message={`기존 확정본 ‘${supersedes.title}’(₩${supersedes.totalAmount.toLocaleString('ko-KR')})은 대체됨으로 바뀌고 손익 합계에서 빠집니다. 기존 견적과 이력은 그대로 남습니다.`}
        />
      )}
      {error && <Alert type="error" message={error} showIcon />}
    </Modal>
  )
}
