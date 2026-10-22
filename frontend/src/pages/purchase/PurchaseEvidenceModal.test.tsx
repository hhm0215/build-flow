import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Purchase, TaxInvoice } from '../../types'
import PurchaseEvidenceModal from './PurchaseEvidenceModal'

const state = vi.hoisted(() => ({
  invoices: [] as unknown[],
  link: vi.fn(),
  unlink: vi.fn(),
}))

vi.mock('../../api/taxes.api', () => ({
  useTaxes: () => ({ data: state.invoices, isLoading: false, isError: false }),
}))
vi.mock('../../api/purchases.api', () => ({
  useLinkPurchaseEvidence: () => ({ mutateAsync: state.link, isPending: false }),
  useUnlinkPurchaseEvidence: () => ({ mutateAsync: state.unlink, isPending: false }),
}))

const purchase: Purchase = {
  id: 7, siteId: 3, itemName: '철근', quantity: 1, unitPrice: 1000, totalAmount: 1000,
  supplier: null, purchaseDate: null, memo: null, evidenceTaxInvoiceId: 20,
  createdAt: '2026-10-23T00:00:00', updatedAt: '2026-10-23T00:00:00',
}

function invoice(id: number, siteId: number, type: 'SALES' | 'PURCHASE'): TaxInvoice {
  return {
    id, siteId, type, supplyAmount: 1000, taxAmount: 100, totalAmount: 1100,
    counterparty: '합성 공급처', issueDate: '2026-10-01', paymentConfirmed: false, paymentDate: null,
    paidAmount: null, remainingAmount: null, paymentStatus: null, memo: null,
    createdAt: '2026-10-01T00:00:00', updatedAt: '2026-10-01T00:00:00',
  }
}

beforeEach(() => {
  state.invoices = [invoice(20, 3, 'PURCHASE')]
  state.link.mockReset()
  state.unlink.mockReset()
  vi.stubGlobal('matchMedia', (media: string) => ({
    matches: false, media, addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
  }))
})

describe('PurchaseEvidenceModal', () => {
  it('현재 연결을 선택된 상태로 보여주고 연결 해제를 보낸다', async () => {
    const onClose = vi.fn()
    state.unlink.mockResolvedValue({})
    render(<PurchaseEvidenceModal purchase={purchase} onClose={onClose} />)

    expect(screen.getByText(/#20 합성 공급처/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /연결 해제/ }))

    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(state.unlink).toHaveBeenCalledWith(7)
  })

  it('서버 거절 메시지를 그대로 보여주고 닫지 않는다', async () => {
    const onClose = vi.fn()
    state.link.mockRejectedValue({ response: { data: { error: '같은 현장의 세금계산서만 증빙으로 연결할 수 있습니다.' } } })
    render(<PurchaseEvidenceModal purchase={purchase} onClose={onClose} />)

    fireEvent.click(screen.getByRole('button', { name: /^연결$/ }))

    expect(await screen.findByText('같은 현장의 세금계산서만 증빙으로 연결할 수 있습니다.')).toBeInTheDocument()
    expect(state.link).toHaveBeenCalledWith({ id: 7, taxInvoiceId: 20 })
    expect(onClose).not.toHaveBeenCalled()
  })

  it('현장 매입 계산서 목록에 없는 기존 연결은 다시 연결하도록 경고한다', () => {
    state.invoices = [invoice(21, 3, 'PURCHASE'), invoice(22, 3, 'SALES'), invoice(23, 4, 'PURCHASE')]
    render(<PurchaseEvidenceModal purchase={purchase} onClose={vi.fn()} />)

    expect(screen.getByText(/연결된 계산서 #20를 이 현장의 매입 계산서에서 찾을 수 없습니다/)).toBeInTheDocument()
  })
})
