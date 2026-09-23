import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import dayjs from 'dayjs'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Purchase } from '../../types'
import PurchaseEditModal, { toPurchaseUpdateRequest } from './PurchaseEditModal'

const mutateAsync = vi.hoisted(() => vi.fn())

vi.mock('../../api/purchases.api', () => ({
  useUpdatePurchase: () => ({ mutateAsync, isPending: false }),
}))

const purchase = {
  id: 9, siteId: 3, itemName: '기존 자재', quantity: 2, unitPrice: 1000,
  totalAmount: 2000, supplier: null, purchaseDate: null, memo: null,
  createdAt: '', updatedAt: '',
} satisfies Purchase

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

describe('PurchaseEditModal', () => {
  it('siteId와 totalAmount를 제외한 PUT 계약으로 변환한다', () => {
    expect(toPurchaseUpdateRequest({
      itemName: ' 수정 자재 ', quantity: 2, unitPrice: 3000,
      supplier: ' 공급사 ', purchaseDate: dayjs('2026-09-24'), memo: '  ',
    })).toEqual({
      itemName: '수정 자재', quantity: 2, unitPrice: 3000,
      supplier: '공급사', purchaseDate: '2026-09-24', memo: undefined,
    })
  })

  it('nullable 기존값을 채우고 중복 저장·진행 중 닫기를 막는다', async () => {
    let resolveMutation: ((value: Purchase) => void) | undefined
    mutateAsync.mockReturnValue(new Promise((resolve) => { resolveMutation = resolve }))
    const onClose = vi.fn()
    render(<PurchaseEditModal purchase={purchase} onClose={onClose} />)
    expect(screen.getByPlaceholderText('품목명')).toHaveValue('기존 자재')
    expect(screen.getByPlaceholderText('공급업체명')).toHaveValue('')
    fireEvent.change(screen.getByPlaceholderText('품목명'), { target: { value: '수정 자재' } })
    const save = screen.getByRole('button', { name: '저장' })
    fireEvent.click(save)
    fireEvent.click(save)
    await waitFor(() => expect(mutateAsync).toHaveBeenCalledWith({
      id: 9, itemName: '수정 자재', quantity: 2, unitPrice: 1000,
      supplier: undefined, purchaseDate: undefined, memo: undefined,
    }))
    expect(mutateAsync).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: '취소' }))
    expect(onClose).not.toHaveBeenCalled()
    await act(async () => { resolveMutation?.(purchase) })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('서버 오류 후 입력을 유지하고 재시도한다', async () => {
    mutateAsync.mockRejectedValueOnce({ response: { data: { error: '수정 실패' } } })
    mutateAsync.mockResolvedValueOnce(purchase)
    const onClose = vi.fn()
    render(<PurchaseEditModal purchase={purchase} onClose={onClose} />)
    fireEvent.change(screen.getByPlaceholderText('품목명'), { target: { value: '재시도 자재' } })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))
    expect(await screen.findByText('수정 실패')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('품목명')).toHaveValue('재시도 자재')
    fireEvent.click(screen.getByRole('button', { name: '저장' }))
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
  })

  it('DB 총액 범위를 넘는 값은 서버에 보내지 않는다', async () => {
    render(<PurchaseEditModal purchase={purchase} onClose={vi.fn()} />)
    fireEvent.change(screen.getByPlaceholderText('수량'), { target: { value: '1001' } })
    fireEvent.change(screen.getByPlaceholderText('단가'), { target: { value: '9999999999.99' } })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))
    expect(await screen.findByText('매입 총액이 저장 가능한 범위를 초과했습니다.')).toBeInTheDocument()
    expect(mutateAsync).not.toHaveBeenCalled()
  })
})
