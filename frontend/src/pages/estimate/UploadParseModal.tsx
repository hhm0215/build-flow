import { useState } from 'react'
import { Modal, Upload, message, Button } from 'antd'
import type { RcFile } from 'antd/es/upload'
import { isAxiosError } from 'axios'
import { FileSpreadsheet, Sparkles, RotateCcw } from 'lucide-react'
import { motion, AnimatePresence } from 'motion/react'
import { useParseEstimateFile } from '../../api/estimates.api'
import type { ParseResult, ParsedItemResult } from '../../types'

interface UploadParseModalProps {
  open: boolean
  onClose: () => void
  onConfirm: (items: ParsedItemResult[], fileName: string) => void
}

const ACCEPT = '.xlsx,.xls'
const MAX_BYTES = 10 * 1024 * 1024

function formatKRW(n: number) {
  return `₩${n.toLocaleString('ko-KR')}`
}

export default function UploadParseModal({ open, onClose, onConfirm }: UploadParseModalProps) {
  const [result, setResult] = useState<ParseResult | null>(null)
  const parseMutation = useParseEstimateFile()

  const handleBeforeUpload = (file: RcFile) => {
    const lower = file.name.toLowerCase()
    if (!lower.endsWith('.xlsx') && !lower.endsWith('.xls')) {
      message.error('엑셀 파일(.xlsx, .xls)만 업로드 가능합니다.')
      return Upload.LIST_IGNORE
    }
    if (file.size > MAX_BYTES) {
      message.error('파일 크기는 10MB 이하여야 합니다.')
      return Upload.LIST_IGNORE
    }

    parseMutation.mutate(file, {
      onSuccess: (data) => {
        setResult(data)
        message.success(`${data.itemCount}개 항목을 추출했습니다.`)
      },
      onError: (error) => {
        const backendMsg = isAxiosError(error)
          ? error.response?.data?.error?.message
          : undefined
        message.error(backendMsg ?? '공내역서 파싱에 실패했습니다. 다시 시도해 주세요.')
      },
    })
    return false
  }

  const handleClose = () => {
    setResult(null)
    parseMutation.reset()
    onClose()
  }

  const handleConfirm = () => {
    if (!result) return
    onConfirm(result.items, result.fileName)
    setResult(null)
    parseMutation.reset()
  }

  const handleRetry = () => {
    setResult(null)
    parseMutation.reset()
  }

  const totalAmount = result?.items.reduce((sum, it) => sum + it.amount, 0) ?? 0

  return (
    <Modal
      title={
        <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <Sparkles size={16} color="#a78bfa" />
          공내역서 업로드 (AI 파싱)
        </span>
      }
      open={open}
      onCancel={handleClose}
      footer={
        result ? (
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
            <Button onClick={handleRetry} icon={<RotateCcw size={14} />}>
              다시 업로드
            </Button>
            <div style={{ display: 'flex', gap: 8 }}>
              <Button onClick={handleClose}>취소</Button>
              <Button type="primary" onClick={handleConfirm}>
                견적서로 만들기
              </Button>
            </div>
          </div>
        ) : (
          <Button onClick={handleClose}>닫기</Button>
        )
      }
      width={760}
      destroyOnClose
    >
      <AnimatePresence mode="wait">
        {!result ? (
          <motion.div
            key="dropzone"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            style={{ padding: '16px 0' }}
          >
            <Upload.Dragger
              name="file"
              accept={ACCEPT}
              beforeUpload={handleBeforeUpload}
              showUploadList={false}
              disabled={parseMutation.isPending}
              multiple={false}
              style={{ padding: '32px 16px' }}
            >
              <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 12 }}>
                <FileSpreadsheet size={40} color="#3b82f6" strokeWidth={1.5} />
                <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--text-primary)' }}>
                  {parseMutation.isPending ? 'AI가 공내역서를 분석 중입니다…' : '공내역서 엑셀 파일을 드래그하거나 클릭하세요'}
                </div>
                <div style={{ fontSize: 12, color: 'var(--text-muted)', textAlign: 'center', lineHeight: 1.6 }}>
                  지원 형식: .xlsx, .xls · 최대 10MB
                  <br />
                  품목·수량·단가를 자동으로 추출합니다 (Ollama qwen2.5).
                </div>
              </div>
            </Upload.Dragger>

            {parseMutation.isPending && (
              <div
                style={{
                  marginTop: 16,
                  textAlign: 'center',
                  fontSize: 12,
                  color: 'var(--text-muted)',
                }}
              >
                LLM 처리에 30초~1분 정도 걸릴 수 있습니다.
              </div>
            )}
          </motion.div>
        ) : (
          <motion.div
            key="result"
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0 }}
            style={{ padding: '8px 0' }}
          >
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                padding: '10px 14px',
                marginBottom: 12,
                background: 'rgba(34,197,94,0.08)',
                border: '1px solid rgba(34,197,94,0.25)',
                borderRadius: 8,
              }}
            >
              <div style={{ fontSize: 13, color: 'var(--text-primary)' }}>
                <span style={{ color: '#22c55e', fontWeight: 600 }}>✓ {result.itemCount}개 항목</span>
                <span style={{ color: 'var(--text-muted)', marginLeft: 8 }}>{result.fileName}</span>
              </div>
              <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-primary)' }}>
                합계 {formatKRW(totalAmount)}
              </div>
            </div>

            <div
              style={{
                maxHeight: 360,
                overflowY: 'auto',
                background: 'var(--bg-card)',
                border: '1px solid var(--border)',
                borderRadius: 8,
              }}
            >
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
                <thead>
                  <tr style={{ borderBottom: '1px solid var(--border)', position: 'sticky', top: 0, background: 'var(--bg-card)' }}>
                    {['품목', '단위', '수량', '단가', '금액'].map((h) => (
                      <th
                        key={h}
                        style={{
                          padding: '10px 14px',
                          textAlign: h === '품목' || h === '단위' ? 'left' : 'right',
                          fontSize: 11,
                          fontWeight: 600,
                          color: 'var(--text-muted)',
                          letterSpacing: '0.05em',
                          textTransform: 'uppercase',
                        }}
                      >
                        {h}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {result.items.map((it, i) => (
                    <tr
                      key={i}
                      style={{
                        borderBottom: i < result.items.length - 1 ? '1px solid var(--border)' : 'none',
                      }}
                    >
                      <td style={{ padding: '10px 14px', color: 'var(--text-primary)' }}>{it.itemName}</td>
                      <td style={{ padding: '10px 14px', color: 'var(--text-secondary)' }}>{it.unit}</td>
                      <td style={{ padding: '10px 14px', textAlign: 'right', color: 'var(--text-secondary)' }}>
                        {it.quantity.toLocaleString('ko-KR')}
                      </td>
                      <td style={{ padding: '10px 14px', textAlign: 'right', color: 'var(--text-secondary)' }}>
                        {formatKRW(it.unitPrice)}
                      </td>
                      <td style={{ padding: '10px 14px', textAlign: 'right', color: 'var(--text-primary)', fontWeight: 600 }}>
                        {formatKRW(it.amount)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div style={{ marginTop: 12, fontSize: 11, color: 'var(--text-muted)' }}>
              ※ AI 추출 결과는 부정확할 수 있습니다. 다음 단계에서 값을 확인·수정하세요.
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </Modal>
  )
}
