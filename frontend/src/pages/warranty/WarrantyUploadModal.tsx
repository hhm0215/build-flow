import { useState } from 'react'
import { Modal, Upload, message, Button, InputNumber } from 'antd'
import type { RcFile } from 'antd/es/upload'
import { isAxiosError } from 'axios'
import { FileText, Sparkles, RotateCcw } from 'lucide-react'
import { motion, AnimatePresence } from 'motion/react'
import { useUploadWarranty } from '../../api/warranties.api'

interface WarrantyUploadModalProps {
  open: boolean
  onClose: () => void
  onUploaded?: () => void
}

const ACCEPT = '.pdf'
const MAX_BYTES = 20 * 1024 * 1024

export default function WarrantyUploadModal({ open, onClose, onUploaded }: WarrantyUploadModalProps) {
  const [siteId, setSiteId] = useState<number | null>(null)
  const [step, setStep] = useState<'input' | 'done'>('input')
  const uploadMutation = useUploadWarranty()

  const reset = () => {
    setSiteId(null)
    setStep('input')
    uploadMutation.reset()
  }

  const handleClose = () => {
    reset()
    onClose()
  }

  const handleBeforeUpload = (file: RcFile) => {
    if (siteId == null) {
      message.error('현장 ID를 먼저 입력해 주세요.')
      return Upload.LIST_IGNORE
    }
    if (!file.name.toLowerCase().endsWith('.pdf')) {
      message.error('PDF 파일만 업로드 가능합니다.')
      return Upload.LIST_IGNORE
    }
    if (file.size > MAX_BYTES) {
      message.error('파일 크기는 20MB 이하여야 합니다.')
      return Upload.LIST_IGNORE
    }

    uploadMutation.mutate(
      { file, siteId },
      {
        onSuccess: () => {
          setStep('done')
          message.success('업로드 완료 — AI가 백그라운드에서 분석 중입니다.')
          onUploaded?.()
        },
        onError: (error) => {
          const backendMsg = isAxiosError(error)
            ? error.response?.data?.error?.message
            : undefined
          message.error(backendMsg ?? '업로드에 실패했습니다. 다시 시도해 주세요.')
        },
      },
    )
    return false
  }

  return (
    <Modal
      title={
        <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <Sparkles size={16} color="#a78bfa" />
          하자보증보험 PDF 업로드 (AI OCR)
        </span>
      }
      open={open}
      onCancel={handleClose}
      footer={
        step === 'done' ? (
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
            <Button onClick={reset} icon={<RotateCcw size={14} />}>
              다른 파일 업로드
            </Button>
            <Button type="primary" onClick={handleClose}>
              완료
            </Button>
          </div>
        ) : (
          <Button onClick={handleClose}>닫기</Button>
        )
      }
      width={640}
      destroyOnClose
    >
      <AnimatePresence mode="wait">
        {step === 'input' ? (
          <motion.div
            key="input"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            style={{ padding: '16px 0' }}
          >
            <div style={{ marginBottom: 16 }}>
              <label style={{ display: 'block', fontSize: 12, color: 'var(--text-muted)', marginBottom: 6 }}>
                현장 ID
              </label>
              <InputNumber
                value={siteId}
                onChange={(v) => setSiteId(v ?? null)}
                min={1}
                placeholder="현장 ID 입력"
                style={{ width: '100%' }}
              />
            </div>

            <Upload.Dragger
              name="file"
              accept={ACCEPT}
              beforeUpload={handleBeforeUpload}
              showUploadList={false}
              disabled={uploadMutation.isPending || siteId == null}
              multiple={false}
              style={{ padding: '32px 16px' }}
            >
              <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 12 }}>
                <FileText size={40} color="#3b82f6" strokeWidth={1.5} />
                <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--text-primary)' }}>
                  {uploadMutation.isPending
                    ? '업로드 중…'
                    : siteId == null
                      ? '현장 ID 입력 후 PDF 드래그/클릭'
                      : 'PDF 파일을 드래그하거나 클릭하세요'}
                </div>
                <div style={{ fontSize: 12, color: 'var(--text-muted)', textAlign: 'center', lineHeight: 1.6 }}>
                  지원: .pdf · 최대 20MB
                  <br />
                  업로드 즉시 PENDING으로 추가되고, 백엔드가 30초~1분 안에 보험사·증권번호·기간을 자동 추출합니다.
                </div>
              </div>
            </Upload.Dragger>
          </motion.div>
        ) : (
          <motion.div
            key="done"
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0 }}
            style={{ padding: '24px 8px', textAlign: 'center' }}
          >
            <Sparkles size={40} color="#a78bfa" style={{ marginBottom: 12 }} />
            <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 }}>
              업로드 완료 — AI 분석 진행 중
            </div>
            <div style={{ fontSize: 12, color: 'var(--text-muted)', lineHeight: 1.6 }}>
              목록에서 <strong>AI 분석 중</strong> 뱃지가 표시됩니다.
              <br />
              분석 완료(30초~1분) 시 자동으로 보험사·증권번호·기간이 채워집니다.
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </Modal>
  )
}
