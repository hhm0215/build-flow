import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import dayjs from 'dayjs'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Estimate } from '../../types'
import EstimateEditModal, { toEstimateUpdateRequest } from './EstimateEditModal'

const mutateAsync = vi.hoisted(() => vi.fn())

vi.mock('../../api/estimates.api', () => ({
  useUpdateEstimate: () => ({ mutateAsync, isPending: false }),
}))

const estimate = {
  id: 9, siteId: 3, title: '기존 초안', status: 'DRAFT', estimateDate: '2026-09-19',
  totalAmount: 2000, memo: '기존 메모',
  items: [{ id: 1, itemName: '자재', unit: 'EA', quantity: 2, unitPrice: 1000, amount: 2000 }],
  createdAt: '', updatedAt: '',
} satisfies Estimate

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

describe('EstimateEditModal', () => {
  it('현장 ID와 계산 금액을 제외한 PUT 계약으로 변환한다', () => {
    expect(toEstimateUpdateRequest({
      title: ' 수정 초안 ', estimateDate: dayjs('2026-10-01'), memo: '  ',
      items: [{ itemName: ' 자재 ', unit: ' EA ', quantity: 2, unitPrice: 1000 }],
    })).toEqual({
      title: '수정 초안', estimateDate: '2026-10-01', memo: undefined,
      items: [{ itemName: '자재', unit: 'EA', quantity: 2, unitPrice: 1000 }],
    })
  })

  it('기존 항목을 채우고 한 번의 PUT으로 저장한다', async () => {
    let resolveMutation: ((value: Estimate) => void) | undefined
    mutateAsync.mockReturnValue(new Promise((resolve) => { resolveMutation = resolve }))
    const onClose = vi.fn()
    render(<EstimateEditModal estimate={estimate} onClose={onClose} />)
    expect(screen.getByPlaceholderText('견적 제목')).toHaveValue('기존 초안')
    expect(screen.getByPlaceholderText('품목명')).toHaveValue('자재')
    fireEvent.change(screen.getByPlaceholderText('견적 제목'), { target: { value: '수정 초안' } })
    const save = screen.getByRole('button', { name: '저장' })
    fireEvent.click(save)
    fireEvent.click(save)
    await waitFor(() => expect(mutateAsync).toHaveBeenCalledWith({
      id: 9,
      title: '수정 초안',
      estimateDate: '2026-09-19',
      memo: '기존 메모',
      items: [{ itemName: '자재', unit: 'EA', quantity: 2, unitPrice: 1000 }],
    }))
    expect(mutateAsync).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: '취소' }))
    expect(onClose).not.toHaveBeenCalled()
    await act(async () => { resolveMutation?.(estimate) })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('항목을 모두 지우면 서버에 보내지 않는다', async () => {
    render(<EstimateEditModal estimate={estimate} onClose={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '항목 삭제' }))
    fireEvent.click(screen.getByRole('button', { name: '저장' }))
    expect(await screen.findByText('견적 항목은 최소 1개 이상 필요합니다.')).toBeInTheDocument()
    expect(mutateAsync).not.toHaveBeenCalled()
  })

  it('서버 오류 후 입력을 유지하고 재시도한다', async () => {
    mutateAsync.mockRejectedValueOnce({ response: { data: { error: '수정 실패' } } })
    mutateAsync.mockResolvedValueOnce(estimate)
    const onClose = vi.fn()
    render(<EstimateEditModal estimate={estimate} onClose={onClose} />)
    fireEvent.change(screen.getByPlaceholderText('견적 제목'), { target: { value: '재시도 초안' } })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))
    expect(await screen.findByText('수정 실패')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('견적 제목')).toHaveValue('재시도 초안')
    fireEvent.click(screen.getByRole('button', { name: '저장' }))
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
  })
})
