import { useState } from 'react'
import { motion } from 'motion/react'
import { Receipt, Plus, CheckCircle } from 'lucide-react'
import { Modal, Form, Input, InputNumber, Select, DatePicker } from 'antd'
import PageHeader from '../../components/PageHeader'
import { useTaxes, useConfirmPayment, useCreateTax } from '../../api/taxes.api'
import type { TaxInvoiceCreateRequest } from '../../types'

export default function TaxListPage() {
  const { data, isLoading } = useTaxes()
  const { mutate: confirmPayment } = useConfirmPayment()
  const { mutate: createTax, isPending: isCreating } = useCreateTax()
  const [modalOpen, setModalOpen] = useState(false)
  const [form] = Form.useForm()
  const invoices = data ?? []

  const unpaidTotal = invoices
    .filter((t) => !t.paymentConfirmed && t.type === 'SALES')
    .reduce((sum, t) => sum + t.totalAmount, 0)

  return (
    <div>
      <PageHeader
        icon={Receipt}
        title="세금계산서"
        description="매출·매입 세금계산서 및 미수금 추적"
        action={
          <motion.button
            whileHover={{ scale: 1.02 }}
            whileTap={{ scale: 0.98 }}
            onClick={() => setModalOpen(true)}
            style={{
              display: 'flex', alignItems: 'center', gap: 6,
              padding: '8px 16px',
              background: 'var(--accent-gradient)',
              border: 'none', borderRadius: 'var(--radius-sm)',
              color: 'white', fontSize: 13, fontWeight: 600, cursor: 'pointer',
              boxShadow: '0 0 16px rgba(59,130,246,0.2)',
            }}
          >
            <Plus size={14} strokeWidth={2.5} />
            세금계산서 등록
          </motion.button>
        }
      />

      {!isLoading && unpaidTotal > 0 && (
        <motion.div
          initial={{ opacity: 0, y: -8 }}
          animate={{ opacity: 1, y: 0 }}
          style={{
            background: 'rgba(239,68,68,0.06)',
            border: '1px solid rgba(239,68,68,0.2)',
            borderRadius: 10,
            padding: '12px 20px',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            marginBottom: 12,
          }}
        >
          <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
            미수금 합계 ({invoices.filter((t) => !t.paymentConfirmed && t.type === 'SALES').length}건)
          </span>
          <span style={{ fontSize: 18, fontWeight: 700, color: '#ef4444', letterSpacing: '-0.03em' }}>
            ₩{unpaidTotal.toLocaleString('ko-KR')}
          </span>
        </motion.div>
      )}

      {isLoading ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {[1, 2, 3].map((i) => (
            <div key={i} className="shimmer" style={{ height: 64, borderRadius: 10 }} />
          ))}
        </div>
      ) : (
        <motion.div
          style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, overflow: 'hidden' }}
        >
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid var(--border)' }}>
                {['구분', '거래처', '공급가', '세액', '합계', '입금 상태', '처리'].map((h) => (
                  <th key={h} style={{
                    padding: '11px 20px', textAlign: 'left',
                    fontSize: 11, fontWeight: 600, color: 'var(--text-muted)',
                    letterSpacing: '0.05em', textTransform: 'uppercase',
                  }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {invoices.map((inv, i) => (
                <motion.tr
                  key={inv.id}
                  initial={{ opacity: 0, x: -8 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{ delay: i * 0.05 }}
                  style={{ borderBottom: i < invoices.length - 1 ? '1px solid var(--border)' : 'none' }}
                  onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.background = 'rgba(255,255,255,0.03)' }}
                  onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.background = 'transparent' }}
                >
                  <td style={{ padding: '14px 20px' }}>
                    <span style={{
                      fontSize: 11, fontWeight: 600, padding: '3px 8px',
                      borderRadius: 20,
                      background: inv.type === 'SALES' ? 'rgba(34,197,94,0.1)' : 'rgba(239,68,68,0.1)',
                      color: inv.type === 'SALES' ? '#22c55e' : '#ef4444',
                      border: `1px solid ${inv.type === 'SALES' ? 'rgba(34,197,94,0.2)' : 'rgba(239,68,68,0.2)'}`,
                    }}>
                      {inv.type === 'SALES' ? '매출' : '매입'}
                    </span>
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-primary)', fontWeight: 500 }}>
                    {inv.counterparty}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-secondary)' }}>
                    ₩{inv.supplyAmount.toLocaleString('ko-KR')}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-secondary)' }}>
                    ₩{inv.taxAmount.toLocaleString('ko-KR')}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-primary)', fontWeight: 600 }}>
                    ₩{inv.totalAmount.toLocaleString('ko-KR')}
                  </td>
                  <td style={{ padding: '14px 20px' }}>
                    <span style={{
                      fontSize: 11, fontWeight: 600, padding: '3px 8px',
                      borderRadius: 20,
                      background: inv.paymentConfirmed ? 'rgba(34,197,94,0.1)' : 'rgba(245,158,11,0.1)',
                      color: inv.paymentConfirmed ? '#22c55e' : '#f59e0b',
                      border: `1px solid ${inv.paymentConfirmed ? 'rgba(34,197,94,0.2)' : 'rgba(245,158,11,0.2)'}`,
                    }}>
                      {inv.paymentConfirmed ? '입금 완료' : '미입금'}
                    </span>
                  </td>
                  <td style={{ padding: '14px 20px' }}>
                    {!inv.paymentConfirmed && inv.type === 'SALES' && (
                      <motion.button
                        whileHover={{ scale: 1.05 }}
                        whileTap={{ scale: 0.95 }}
                        onClick={() => confirmPayment(inv.id)}
                        style={{
                          display: 'flex', alignItems: 'center', gap: 4,
                          fontSize: 11, fontWeight: 600,
                          padding: '4px 10px', borderRadius: 6,
                          background: 'rgba(34,197,94,0.1)',
                          border: '1px solid rgba(34,197,94,0.2)',
                          color: '#22c55e', cursor: 'pointer',
                        }}
                      >
                        <CheckCircle size={11} strokeWidth={2.5} />
                        입금 확인
                      </motion.button>
                    )}
                  </td>
                </motion.tr>
              ))}
            </tbody>
          </table>
        </motion.div>
      )}

      <Modal
        title="세금계산서 등록"
        open={modalOpen}
        okText="등록"
        cancelText="취소"
        confirmLoading={isCreating}
        destroyOnClose
        onCancel={() => { setModalOpen(false); form.resetFields() }}
        onOk={() => {
          form.validateFields().then((values) => {
            const body: TaxInvoiceCreateRequest = {
              ...values,
              issueDate: values.issueDate.format('YYYY-MM-DD'),
            }
            createTax(body, {
              onSuccess: () => {
                setModalOpen(false)
                form.resetFields()
              },
            })
          })
        }}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="type" label="구분" rules={[{ required: true, message: '구분을 선택해주세요' }]}>
            <Select
              placeholder="매출 / 매입 선택"
              options={[
                { label: '매출', value: 'SALES' },
                { label: '매입', value: 'PURCHASE' },
              ]}
            />
          </Form.Item>
          <Form.Item name="siteId" label="현장 ID" rules={[{ required: true, message: '현장 ID를 입력해주세요' }]}>
            <InputNumber style={{ width: '100%' }} min={1} placeholder="현장 ID" />
          </Form.Item>
          <Form.Item name="counterparty" label="거래처" rules={[{ required: true, message: '거래처를 입력해주세요' }]}>
            <Input placeholder="거래처명" />
          </Form.Item>
          <Form.Item name="supplyAmount" label="공급가액" rules={[{ required: true, message: '공급가액을 입력해주세요' }]}>
            <InputNumber
              style={{ width: '100%' }}
              min={0}
              formatter={(value) => `₩ ${value}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}
              parser={(value) => Number(value?.replace(/₩\s?|(,*)/g, '') ?? 0) as any}
              placeholder="공급가액"
            />
          </Form.Item>
          <Form.Item name="taxAmount" label="세액" rules={[{ required: true, message: '세액을 입력해주세요' }]}>
            <InputNumber
              style={{ width: '100%' }}
              min={0}
              formatter={(value) => `₩ ${value}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}
              parser={(value) => Number(value?.replace(/₩\s?|(,*)/g, '') ?? 0) as any}
              placeholder="세액"
            />
          </Form.Item>
          <Form.Item name="issueDate" label="발행일" rules={[{ required: true, message: '발행일을 선택해주세요' }]}>
            <DatePicker style={{ width: '100%' }} placeholder="발행일 선택" />
          </Form.Item>
          <Form.Item name="memo" label="메모">
            <Input.TextArea rows={3} placeholder="메모 (선택)" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
