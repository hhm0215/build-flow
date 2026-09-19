import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import dayjs from 'dayjs'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Site } from '../../types'
import SiteEditModal, { toSiteUpdateRequest } from './SiteEditModal'

const mutateAsync = vi.hoisted(() => vi.fn())

vi.mock('../../api/clients.api', () => ({
  useClients: () => ({ data: [], isError: false, isLoading: false }),
}))
vi.mock('../../api/sites.api', () => ({
  useUpdateSite: () => ({ mutateAsync, isPending: false }),
}))

const site = {
  id: 17,
  siteName: '기존 현장',
  client: null,
  address: '서울',
  status: 'IN_PROGRESS',
  startDate: '2026-09-01',
  endDate: '2026-09-30',
  memo: '기존 메모',
  createdAt: '2026-09-01T00:00:00',
  updatedAt: '2026-09-01T00:00:00',
} satisfies Site

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

describe('SiteEditModal', () => {
  it('비운 선택 필드를 전체 교체 요청의 null로 변환한다', () => {
    expect(toSiteUpdateRequest({
      siteName: ' 수정된 현장 ',
      address: '   ',
      startDate: dayjs('2026-10-01'),
    })).toEqual({
      siteName: '수정된 현장',
      clientId: null,
      address: null,
      startDate: '2026-10-01',
      endDate: null,
      memo: null,
    })
  })

  it('기존 값을 채우고 전체 필드를 PUT 요청으로 보낸다', async () => {
    mutateAsync.mockResolvedValue({ ...site, siteName: '수정된 현장' })
    const onClose = vi.fn()
    render(<SiteEditModal site={site} onClose={onClose} />)
    expect(screen.getByPlaceholderText('현장명')).toHaveValue('기존 현장')
    expect(screen.getByPlaceholderText('주소 (선택)')).toHaveValue('서울')
    fireEvent.change(screen.getByPlaceholderText('현장명'), { target: { value: '수정된 현장' } })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => expect(mutateAsync).toHaveBeenCalledWith({
      id: 17,
      siteName: '수정된 현장',
      clientId: null,
      address: '서울',
      startDate: '2026-09-01',
      endDate: '2026-09-30',
      memo: '기존 메모',
    }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('서버 실패 시 입력을 유지하고 재시도한다', async () => {
    mutateAsync.mockRejectedValueOnce({ response: { data: { error: '수정할 수 없습니다.' } } })
    mutateAsync.mockResolvedValueOnce({ ...site, siteName: '다시 수정' })
    const onClose = vi.fn()
    render(<SiteEditModal site={site} onClose={onClose} />)
    fireEvent.change(screen.getByPlaceholderText('현장명'), { target: { value: '다시 수정' } })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))
    expect(await screen.findByText('수정할 수 없습니다.')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('현장명')).toHaveValue('다시 수정')
    expect(onClose).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '저장' }))
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
  })

  it('빠른 중복 저장을 한 번의 PUT으로 제한한다', async () => {
    let resolveMutation: ((value: Site) => void) | undefined
    mutateAsync.mockReturnValue(new Promise((resolve) => { resolveMutation = resolve }))
    const onClose = vi.fn()
    render(<SiteEditModal site={site} onClose={onClose} />)
    const save = screen.getByRole('button', { name: '저장' })
    fireEvent.click(save)
    fireEvent.click(save)
    await waitFor(() => expect(mutateAsync).toHaveBeenCalledTimes(1))
    fireEvent.click(screen.getByRole('button', { name: '취소' }))
    expect(onClose).not.toHaveBeenCalled()
    await act(async () => { resolveMutation?.(site) })
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
