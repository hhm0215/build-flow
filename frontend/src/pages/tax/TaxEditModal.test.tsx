import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import dayjs from 'dayjs'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { TaxInvoice } from '../../types'
import TaxEditModal, { toTaxUpdateRequest } from './TaxEditModal'

const mutateAsync = vi.hoisted(() => vi.fn())

vi.mock('../../api/taxes.api', () => ({
  useUpdateTax: () => ({ mutateAsync, isPending: false }),
}))

const invoice = {
  id: 9, siteId: 3, type: 'SALES', supplyAmount: 1000, taxAmount: 100,
  totalAmount: 1100, counterparty: null, issueDate: null,
  paymentConfirmed: false, paymentDate: null, memo: null,
  createdAt: '', updatedAt: '',
} satisfies TaxInvoice

beforeEach(() => {
  mutateAsync.mockReset()
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false, media: query, addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(),
  }))
  const getComputedStyle = window.getComputedStyle.bind(window)
  vi.spyOn(window, 'getComputedStyle').mockImplementation((element) => getComputedStyle(element))
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('TaxEditModal', () => {
  it('siteId와 서버 필드를 제외한 PUT 계약으로 변환한다', () => {
    expect(toTaxUpdateRequest({
      type: 'PURCHASE', supplyAmount: 200, taxAmount: 20,
      counterparty: ' 거래처 ', issueDate: dayjs('2026-09-24'), memo: '  ',
    })).toEqual({
      type: 'PURCHASE', supplyAmount: 200, taxAmount: 20,
      counterparty: '거래처', issueDate: '2026-09-24', memo: undefined,
    })
  })

  it('nullable 기존값을 채우고 중복 저장·진행 중 닫기를 막는다', async () => {
    let resolveMutation: ((value: TaxInvoice) => void) | undefined
    mutateAsync.mockReturnValue(new Promise((resolve) => { resolveMutation = resolve }))
    const onClose = vi.fn()
    render(<TaxEditModal invoice={invoice} onClose={onClose} />)
    expect(screen.getByPlaceholderText('거래처명')).toHaveValue('')
    fireEvent.change(screen.getByPlaceholderText('거래처명'), { target: { value: '수정 거래처' } })
    const save = screen.getByRole('button', { name: '저장' })
    fireEvent.click(save)
    fireEvent.click(save)
    await waitFor(() => expect(mutateAsync).toHaveBeenCalledWith({
      id: 9, type: 'SALES', supplyAmount: 1000, taxAmount: 100,
      counterparty: '수정 거래처', issueDate: undefined, memo: undefined,
    }))
    expect(mutateAsync).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: '취소' }))
    expect(onClose).not.toHaveBeenCalled()
    await act(async () => { resolveMutation?.(invoice) })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('서버 오류 후 입력을 유지하고 재시도한다', async () => {
    mutateAsync.mockRejectedValueOnce({ response: { data: { error: '수정 실패' } } })
    mutateAsync.mockResolvedValueOnce(invoice)
    const onClose = vi.fn()
    render(<TaxEditModal invoice={invoice} onClose={onClose} />)
    fireEvent.change(screen.getByPlaceholderText('거래처명'), { target: { value: '재시도 거래처' } })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))
    expect(await screen.findByText('수정 실패')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('거래처명')).toHaveValue('재시도 거래처')
    fireEvent.click(screen.getByRole('button', { name: '저장' }))
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
  })

  it('DB 합계 범위를 넘는 값은 서버에 보내지 않는다', async () => {
    render(<TaxEditModal invoice={invoice} onClose={vi.fn()} />)
    fireEvent.change(screen.getByPlaceholderText('공급가액'), { target: { value: '9999999999999.99' } })
    fireEvent.change(screen.getByPlaceholderText('세액'), { target: { value: '0.01' } })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))
    expect(await screen.findByText('세금계산서 합계가 저장 가능한 범위를 초과했습니다.')).toBeInTheDocument()
    expect(mutateAsync).not.toHaveBeenCalled()
  })
})
