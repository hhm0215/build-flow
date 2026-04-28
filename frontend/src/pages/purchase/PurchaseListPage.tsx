import { useState } from 'react'
import { motion } from 'motion/react'
import { ShoppingBag, Plus } from 'lucide-react'
import { Modal, Form, Input, InputNumber, DatePicker } from 'antd'
import dayjs from 'dayjs'
import PageHeader from '../../components/PageHeader'
import { usePurchases, useCreatePurchase } from '../../api/purchases.api'
import type { PurchaseCreateRequest } from '../../types'

export default function PurchaseListPage() {
  const { data, isLoading } = usePurchases()
  const purchases = data ?? []
  const totalAmount = purchases.reduce((sum, p) => sum + p.totalAmount, 0)

  const [open, setOpen] = useState(false)
  const [form] = Form.useForm()
  const createMutation = useCreatePurchase()

  const handleOk = () => {
    form.validateFields().then((values) => {
      const body: PurchaseCreateRequest = {
        ...values,
        purchaseDate: values.purchaseDate
          ? dayjs(values.purchaseDate).format('YYYY-MM-DD')
          : undefined,
      }
      createMutation.mutate(body, {
        onSuccess: () => {
          setOpen(false)
          form.resetFields()
        },
      })
    })
  }

  return (
    <div>
      <PageHeader
        icon={ShoppingBag}
        title="매입 관리"
        description="자재 및 서비스 매입 내역"
        action={
          <motion.button
            whileHover={{ scale: 1.02 }}
            whileTap={{ scale: 0.98 }}
            onClick={() => setOpen(true)}
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
            매입 등록
          </motion.button>
        }
      />

      <Modal
        title="매입 등록"
        open={open}
        onOk={handleOk}
        onCancel={() => { setOpen(false); form.resetFields() }}
        okText="등록"
        cancelText="취소"
        confirmLoading={createMutation.isPending}
        destroyOnClose
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item
            name="siteId"
            label="현장 ID"
            rules={[{ required: true, message: '현장 ID를 입력하세요' }]}
          >
            <InputNumber style={{ width: '100%' }} min={1} placeholder="현장 ID" />
          </Form.Item>
          <Form.Item
            name="itemName"
            label="품목명"
            rules={[{ required: true, message: '품목명을 입력하세요' }]}
          >
            <Input placeholder="품목명" />
          </Form.Item>
          <Form.Item
            name="quantity"
            label="수량"
            rules={[{ required: true, message: '수량을 입력하세요' }]}
          >
            <InputNumber style={{ width: '100%' }} min={1} placeholder="수량" />
          </Form.Item>
          <Form.Item
            name="unitPrice"
            label="단가"
            rules={[{ required: true, message: '단가를 입력하세요' }]}
          >
            <InputNumber
              style={{ width: '100%' }}
              min={0}
              placeholder="단가"
              formatter={(v) => `${v}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}
              parser={(v) => Number(v?.replace(/,/g, '') ?? 0) as 0}
            />
          </Form.Item>
          <Form.Item name="supplier" label="공급업체">
            <Input placeholder="공급업체명" />
          </Form.Item>
          <Form.Item name="purchaseDate" label="매입일">
            <DatePicker style={{ width: '100%' }} placeholder="매입일 선택" />
          </Form.Item>
          <Form.Item name="memo" label="메모">
            <Input.TextArea rows={3} placeholder="메모" />
          </Form.Item>
        </Form>
      </Modal>

      {!isLoading && (
        <motion.div
          initial={{ opacity: 0, y: -8 }}
          animate={{ opacity: 1, y: 0 }}
          style={{
            background: 'rgba(239,68,68,0.06)',
            border: '1px solid rgba(239,68,68,0.15)',
            borderRadius: 10,
            padding: '12px 20px',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            marginBottom: 12,
          }}
        >
          <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>전체 매입 합계</span>
          <span style={{ fontSize: 18, fontWeight: 700, color: '#ef4444', letterSpacing: '-0.03em' }}>
            ₩{totalAmount.toLocaleString('ko-KR')}
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
                {['품목명', '수량', '단가', '금액', '거래처', '매입일'].map((h) => (
                  <th key={h} style={{
                    padding: '11px 20px', textAlign: 'left',
                    fontSize: 11, fontWeight: 600, color: 'var(--text-muted)',
                    letterSpacing: '0.05em', textTransform: 'uppercase',
                  }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {purchases.map((p, i) => (
                <motion.tr
                  key={p.id}
                  initial={{ opacity: 0, x: -8 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{ delay: i * 0.05 }}
                  style={{ borderBottom: i < purchases.length - 1 ? '1px solid var(--border)' : 'none', cursor: 'pointer' }}
                  onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.background = 'rgba(255,255,255,0.03)' }}
                  onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.background = 'transparent' }}
                >
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-primary)', fontWeight: 500 }}>
                    {p.itemName}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-secondary)' }}>
                    {p.quantity}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-secondary)' }}>
                    ₩{p.unitPrice.toLocaleString('ko-KR')}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-primary)', fontWeight: 600 }}>
                    ₩{p.totalAmount.toLocaleString('ko-KR')}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 13, color: 'var(--text-secondary)' }}>
                    {p.supplier || '-'}
                  </td>
                  <td style={{ padding: '14px 20px', fontSize: 12, color: 'var(--text-muted)' }}>
                    {p.purchaseDate}
                  </td>
                </motion.tr>
              ))}
            </tbody>
          </table>
        </motion.div>
      )}
    </div>
  )
}
