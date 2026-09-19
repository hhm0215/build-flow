import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import dayjs from 'dayjs'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Warranty } from '../../types'
import WarrantyEditModal, { toWarrantyUpdateRequest } from './WarrantyEditModal'

const mutateAsync = vi.hoisted(() => vi.fn())

vi.mock('../../api/warranties.api', () => ({
  useUpdateWarranty: () => ({ mutateAsync, isPending: false }),
}))

const warranty = {
  id: 7,
  siteId: 2,
  insuranceCompany: '기존 보험사',
  policyNumber: 'POL-1',
  coverageAmount: 123000,
  startDate: '2026-09-01',
  endDate: '2027-09-01',
  memo: '기존 메모',
  daysUntilExpiry: 300,
  expired: false,
  filePath: '/uploads/7.pdf',
  ocrStatus: 'FAILED',
  createdAt: '2026-09-01T00:00:00',
  updatedAt: '2026-09-01T00:00:00',
} satisfies Warranty

beforeEach(() => {
  mutateAsync.mockReset()
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false,
    media: query,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }))
  const getComputedStyle = window.getComputedStyle.bind(window)
  vi.spyOn(window, 'getComputedStyle').mockImplementation((element) => getComputedStyle(element))
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('WarrantyEditModal', () => {
  it('선택 필드를 비우면 서버 3-state 계약의 null로 보낸다', () => {
    expect(toWarrantyUpdateRequest({
      insuranceCompany: ' 현대해상 ',
      policyNumber: ' ',
      coverageAmount: null,
      startDate: dayjs('2026-09-01'),
      endDate: dayjs('2027-09-01'),
      memo: ' ',
    })).toEqual({
      insuranceCompany: '현대해상',
      policyNumber: null,
      coverageAmount: null,
      startDate: '2026-09-01',
      endDate: '2027-09-01',
      memo: null,
    })
  })

  it('OCR 실패 항목의 기존 값을 채우고 보정 저장한다', async () => {
    mutateAsync.mockResolvedValue({ ...warranty, ocrStatus: 'MANUAL' })
    const onClose = vi.fn()
    render(<WarrantyEditModal warranty={warranty} onClose={onClose} />)
    expect(screen.getByText('AI 실패 정보 수동 보정')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('보험사명')).toHaveValue('기존 보험사')
    fireEvent.change(screen.getByPlaceholderText('보험사명'), { target: { value: '현대해상' } })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))
    await waitFor(() => expect(mutateAsync).toHaveBeenCalledWith({
      id: 7,
      insuranceCompany: '현대해상',
      policyNumber: 'POL-1',
      coverageAmount: 123000,
      startDate: '2026-09-01',
      endDate: '2027-09-01',
      memo: '기존 메모',
    }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('OCR 실패로 필수 정보가 비어 있으면 완성 전 저장하지 않고, 입력 후 보정한다', async () => {
    const incomplete = { ...warranty, insuranceCompany: null, startDate: null, endDate: null }
    mutateAsync.mockResolvedValue({ ...incomplete, ocrStatus: 'MANUAL' })
    render(<WarrantyEditModal warranty={incomplete} onClose={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '저장' }))
    expect(await screen.findByText('보험사를 입력하세요')).toBeInTheDocument()
    expect(mutateAsync).not.toHaveBeenCalled()

    fireEvent.change(screen.getByPlaceholderText('보험사명'), { target: { value: '서울보증보험' } })
    const startDate = screen.getByPlaceholderText('시작일 선택')
    const endDate = screen.getByPlaceholderText('종료일 선택')
    fireEvent.change(startDate, { target: { value: '2026-09-01' } })
    fireEvent.keyDown(startDate, { key: 'Enter' })
    fireEvent.change(endDate, { target: { value: '2027-09-01' } })
    fireEvent.keyDown(endDate, { key: 'Enter' })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))
    await waitFor(() => expect(mutateAsync).toHaveBeenCalledWith(expect.objectContaining({
      id: 7, insuranceCompany: '서울보증보험', startDate: '2026-09-01', endDate: '2027-09-01',
    })))
  })

  it('서버 실패 시 입력을 유지하고 재시도한다', async () => {
    mutateAsync.mockRejectedValueOnce({ response: { data: { error: '수정할 수 없습니다.' } } })
    mutateAsync.mockResolvedValueOnce({ ...warranty, ocrStatus: 'MANUAL' })
    const onClose = vi.fn()
    render(<WarrantyEditModal warranty={warranty} onClose={onClose} />)
    fireEvent.change(screen.getByPlaceholderText('보험사명'), { target: { value: '다시 수정' } })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))
    expect(await screen.findByText('수정할 수 없습니다.')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('보험사명')).toHaveValue('다시 수정')
    fireEvent.click(screen.getByRole('button', { name: '저장' }))
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
  })

  it('빠른 중복 저장을 한 번으로 제한하고 OCR 처리 중은 저장하지 않는다', async () => {
    let resolveMutation: ((value: Warranty) => void) | undefined
    mutateAsync.mockReturnValue(new Promise((resolve) => { resolveMutation = resolve }))
    const onClose = vi.fn()
    const { rerender } = render(<WarrantyEditModal warranty={warranty} onClose={onClose} />)
    const save = screen.getByRole('button', { name: '저장' })
    fireEvent.click(save)
    fireEvent.click(save)
    await waitFor(() => expect(mutateAsync).toHaveBeenCalledTimes(1))
    await act(async () => { resolveMutation?.(warranty) })
    rerender(<WarrantyEditModal warranty={{ ...warranty, ocrStatus: 'PENDING' }} onClose={onClose} />)
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled()
  })
})
