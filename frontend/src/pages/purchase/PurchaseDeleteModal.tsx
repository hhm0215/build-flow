import { useRef, useState } from 'react'
import { Alert, Modal } from 'antd'
import { useDeletePurchase } from '../../api/purchases.api'
import type { Purchase } from '../../types'

interface Props {
  purchase: Purchase
  onClose: () => void
}

export default function PurchaseDeleteModal({ purchase, onClose }: Props) {
  const deletePurchase = useDeletePurchase()
  const [error, setError] = useState<string | null>(null)
  const inFlight = useRef(false)

  const close = () => {
    if (!inFlight.current && !deletePurchase.isPending) onClose()
  }

  const submit = async () => {
    if (inFlight.current || deletePurchase.isPending) return
    inFlight.current = true
    setError(null)
    try {
      await deletePurchase.mutateAsync({ id: purchase.id, siteId: purchase.siteId })
      onClose()
    } catch (cause: unknown) {
      const responseError = (cause as { response?: { data?: { error?: string } } })?.response?.data?.error
      setError(responseError ?? '매입 내역을 삭제하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      inFlight.current = false
    }
  }

  return (
    <Modal
      title="매입 삭제"
      open
      onOk={submit}
      onCancel={close}
      okText="삭제"
      okButtonProps={{ danger: true, disabled: deletePurchase.isPending }}
      cancelButtonProps={{ disabled: deletePurchase.isPending }}
      cancelText="취소"
      confirmLoading={deletePurchase.isPending}
      closable={!deletePurchase.isPending}
      maskClosable={!deletePurchase.isPending}
      keyboard={!deletePurchase.isPending}
    >
      <p>‘{purchase.itemName}’ 매입 내역을 삭제할까요? 이 작업은 되돌릴 수 없습니다.</p>
      {error && <Alert type="error" message={error} showIcon />}
    </Modal>
  )
}
