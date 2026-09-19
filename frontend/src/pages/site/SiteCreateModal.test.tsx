import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import dayjs from 'dayjs'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SiteCreateModal, { toSiteCreateRequest } from './SiteCreateModal'

const mutateAsync = vi.hoisted(() => vi.fn())
const createClientMutateAsync = vi.hoisted(() => vi.fn())

vi.mock('../../api/clients.api', () => ({
  useClients: () => ({ data: [], isError: false }),
  useCreateClient: () => ({ mutateAsync: createClientMutateAsync, isPending: false }),
}))
vi.mock('../../api/sites.api', () => ({
  useCreateSite: () => ({ mutateAsync, isPending: false }),
}))

beforeEach(() => {
  mutateAsync.mockReset()
  createClientMutateAsync.mockReset()
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

  it('새 거래처를 등록하면 현장 폼에 자동 선택한다', async () => {
    createClientMutateAsync.mockResolvedValue({ id: 6, companyName: '새 거래처' })
    mutateAsync.mockResolvedValue({ id: 18 })
    render(<SiteCreateModal open onClose={vi.fn()} onCreated={vi.fn()} />)

    fireEvent.click(screen.getByRole('button', { name: '새 거래처 등록' }))
    fireEvent.change(screen.getByPlaceholderText('업체명'), { target: { value: '새 거래처' } })
    fireEvent.click(screen.getByRole('button', { name: '등록' }))
    await waitFor(() => expect(createClientMutateAsync).toHaveBeenCalled())

    fireEvent.change(screen.getByPlaceholderText('현장명'), { target: { value: '연결된 현장' } })
    fireEvent.click(screen.getByRole('button', { name: '추가' }))
    await waitFor(() => expect(mutateAsync).toHaveBeenCalledWith(expect.objectContaining({
      siteName: '연결된 현장',
      clientId: 6,
    })))
  })

  it('추가 버튼을 빠르게 두 번 눌러도 현장을 한 번만 생성한다', async () => {
    let resolveMutation: ((value: { id: number }) => void) | undefined
    mutateAsync.mockReturnValue(new Promise((resolve) => { resolveMutation = resolve }))
    render(<SiteCreateModal open onClose={vi.fn()} onCreated={vi.fn()} />)
    fireEvent.change(screen.getByPlaceholderText('현장명'), { target: { value: '중복 방지 현장' } })

    const addButton = screen.getByRole('button', { name: '추가' })
    fireEvent.click(addButton)
    fireEvent.click(addButton)

    await waitFor(() => expect(mutateAsync).toHaveBeenCalledTimes(1))
    await act(async () => { resolveMutation?.({ id: 19 }) })
  })

  it('현장 생성 중에는 새 거래처 모달을 열지 않는다', async () => {
    let resolveMutation: ((value: { id: number }) => void) | undefined
    mutateAsync.mockReturnValue(new Promise((resolve) => { resolveMutation = resolve }))
    render(<SiteCreateModal open onClose={vi.fn()} onCreated={vi.fn()} />)
    fireEvent.change(screen.getByPlaceholderText('현장명'), { target: { value: '진행 중 현장' } })
    fireEvent.click(screen.getByRole('button', { name: '추가' }))
    await waitFor(() => expect(mutateAsync).toHaveBeenCalledTimes(1))

    fireEvent.click(screen.getByRole('button', { name: '새 거래처 등록' }))
    expect(screen.queryByPlaceholderText('업체명')).not.toBeInTheDocument()
    await act(async () => { resolveMutation?.({ id: 20 }) })
  })
})
