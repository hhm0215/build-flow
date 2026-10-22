import { useMemo, useRef, useState } from 'react'
import { Alert, Button, Modal, Select } from 'antd'
import { useLinkPurchaseEvidence, useUnlinkPurchaseEvidence } from '../../api/purchases.api'
import { useTaxes } from '../../api/taxes.api'
import type { Purchase } from '../../types'

interface Props {
  purchase: Purchase
  onClose: () => void
}

function errorMessage(cause: unknown, fallback: string) {
  return (cause as { response?: { data?: { error?: string } } })?.response?.data?.error ?? fallback
}

/**
 * 매입 1건을 같은 현장의 매입 세금계산서 1건에 증빙으로 연결한다.
 * 원가는 매입 항목만 쓰므로 연결해도 손익은 바뀌지 않는다(서버가 유형·현장을 다시 검증).
 */
export default function PurchaseEvidenceModal({ purchase, onClose }: Props) {
  const params = useMemo(() => ({ siteId: String(purchase.siteId), type: 'PURCHASE' }), [purchase.siteId])
  const { data, isLoading, isError } = useTaxes(params)
  const link = useLinkPurchaseEvidence()
  const unlink = useUnlinkPurchaseEvidence()
  const [selected, setSelected] = useState<number | undefined>(purchase.evidenceTaxInvoiceId ?? undefined)
  const [error, setError] = useState<string | null>(null)
  const inFlight = useRef(false)
  const pending = link.isPending || unlink.isPending

  const invoices = (data ?? []).filter((invoice) =>
    invoice.type === 'PURCHASE' && invoice.siteId === purchase.siteId)
  const currentMissing = purchase.evidenceTaxInvoiceId != null && !isLoading && !isError &&
    !invoices.some((invoice) => invoice.id === purchase.evidenceTaxInvoiceId)

  const close = () => {
    if (!inFlight.current && !pending) onClose()
  }

  const run = async (action: () => Promise<unknown>, fallback: string) => {
    if (inFlight.current || pending) return
    inFlight.current = true
    setError(null)
    try {
      await action()
      onClose()
    } catch (cause: unknown) {
      setError(errorMessage(cause, fallback))
    } finally {
      inFlight.current = false
    }
  }

  const submit = () => {
    if (selected == null) {
      setError('연결할 매입 세금계산서를 선택하세요.')
      return
    }
    void run(() => link.mutateAsync({ id: purchase.id, taxInvoiceId: selected }),
      '증빙을 연결하지 못했습니다. 다시 시도해 주세요.')
  }

  const remove = () => {
    void run(() => unlink.mutateAsync(purchase.id), '증빙 연결을 해제하지 못했습니다. 다시 시도해 주세요.')
  }

  return (
    <Modal
      title="증빙 세금계산서 연결"
      open
      onOk={submit}
      onCancel={close}
      okText="연결"
      cancelText="취소"
      okButtonProps={{ disabled: pending || isLoading || isError }}
      cancelButtonProps={{ disabled: pending }}
      confirmLoading={link.isPending}
      closable={!pending}
      maskClosable={!pending}
      keyboard={!pending}
      footer={(origin) => (
        <>
          {purchase.evidenceTaxInvoiceId != null && (
            <Button danger disabled={pending} loading={unlink.isPending} onClick={remove}>
              연결 해제
            </Button>
          )}
          {origin}
        </>
      )}
    >
      <p style={{ marginBottom: 12 }}>
        ‘{purchase.itemName}’(₩{purchase.totalAmount.toLocaleString('ko-KR')})의 증빙 매입 세금계산서를 고르세요.
        원가(손익)는 매입 항목 기준이라 연결해도 바뀌지 않습니다.
      </p>
      {isError ? (
        <Alert type="error" showIcon message="매입 세금계산서를 불러오지 못했습니다." />
      ) : (
        <Select<number>
          aria-label="증빙 매입 세금계산서"
          style={{ width: '100%' }}
          loading={isLoading}
          value={selected}
          onChange={(value) => setSelected(value)}
          placeholder={invoices.length === 0 && !isLoading ? '이 현장의 매입 세금계산서가 없습니다' : '매입 세금계산서 선택'}
          options={invoices.map((invoice) => ({
            value: invoice.id,
            label: `#${invoice.id} ${invoice.counterparty || '거래처 미입력'} · ${invoice.issueDate || '발행일 미입력'} · `
              + `공급가 ₩${invoice.supplyAmount.toLocaleString('ko-KR')} / 합계 ₩${invoice.totalAmount.toLocaleString('ko-KR')}`,
          }))}
        />
      )}
      {currentMissing && (
        <Alert
          type="warning"
          showIcon
          style={{ marginTop: 12 }}
          message={`연결된 계산서 #${purchase.evidenceTaxInvoiceId}를 이 현장의 매입 계산서에서 찾을 수 없습니다. 다시 연결하거나 해제하세요.`}
        />
      )}
      {error && <Alert type="error" message={error} showIcon style={{ marginTop: 12 }} />}
    </Modal>
  )
}
