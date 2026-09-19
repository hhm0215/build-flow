import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import dayjs from 'dayjs'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SiteCreateModal, { toSiteCreateRequest } from './SiteCreateModal'

const mutateAsync = vi.hoisted(() => vi.fn())

vi.mock('../../api/clients.api', () => ({
  useClients: () => ({ data: [], isError: false }),
}))
vi.mock('../../api/sites.api', () => ({
  useCreateSite: () => ({ mutateAsync, isPending: false }),
}))

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

describe('SiteCreateModal', () => {
  it('날짜와 선택 입력을 API 계약으로 변환한다', () => {
    expect(toSiteCreateRequest({
      siteName: '  새 현장  ',
      clientId: 3,
      startDate: dayjs('2026-10-01'),
      endDate: dayjs('2026-10-31'),
      address: '  서울  ',
    })).toEqual({
      siteName: '새 현장',
      clientId: 3,
      startDate: '2026-10-01',
      endDate: '2026-10-31',
      address: '서울',
      memo: undefined,
    })
  })

  it('현장명 없이 요청하지 않는다', async () => {
    render(<SiteCreateModal open onClose={vi.fn()} onCreated={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '추가' }))

    expect(await screen.findByText('현장명을 입력하세요')).toBeInTheDocument()
    expect(mutateAsync).not.toHaveBeenCalled()
  })

  it('생성 성공 시 새 현장 ID를 전달한다', async () => {
    mutateAsync.mockResolvedValue({ id: 17 })
    const onCreated = vi.fn()
    render(<SiteCreateModal open onClose={vi.fn()} onCreated={onCreated} />)
    fireEvent.change(screen.getByPlaceholderText('현장명'), { target: { value: '새 현장' } })
    fireEvent.click(screen.getByRole('button', { name: '추가' }))

    await waitFor(() => expect(mutateAsync).toHaveBeenCalledWith({
      siteName: '새 현장',
      clientId: undefined,
      address: undefined,
      startDate: undefined,
      endDate: undefined,
      memo: undefined,
    }))
    expect(onCreated).toHaveBeenCalledWith(17)
  })

  it('서버 실패 시 입력을 유지하고 오류를 표시한다', async () => {
    mutateAsync.mockRejectedValue({ response: { data: { error: '거래처를 찾을 수 없습니다.' } } })
    const onCreated = vi.fn()
    render(<SiteCreateModal open onClose={vi.fn()} onCreated={onCreated} />)
    fireEvent.change(screen.getByPlaceholderText('현장명'), { target: { value: '새 현장' } })
    fireEvent.click(screen.getByRole('button', { name: '추가' }))

    expect(await screen.findByText('거래처를 찾을 수 없습니다.')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('현장명')).toHaveValue('새 현장')
    expect(onCreated).not.toHaveBeenCalled()
  })
})
