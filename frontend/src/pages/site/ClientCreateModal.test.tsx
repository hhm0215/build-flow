import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ClientCreateModal, { toClientCreateRequest } from './ClientCreateModal'
import type { Client } from '../../types'

const mutateAsync = vi.hoisted(() => vi.fn())

vi.mock('../../api/clients.api', () => ({
  useCreateClient: () => ({ mutateAsync, isPending: false }),
}))

const createdClient: Client = {
  id: 6,
  companyName: '새 거래처',
  representative: null,
  businessNo: null,
  phone: null,
  email: null,
  address: null,
  memo: null,
  createdAt: '2026-09-19T00:00:00',
  updatedAt: '2026-09-19T00:00:00',
}

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

describe('ClientCreateModal', () => {
  it('회사명과 선택 입력을 다듬어 API 계약으로 변환한다', () => {
    expect(toClientCreateRequest({
      companyName: '  새 거래처  ',
      representative: '  김대표  ',
      phone: '   ',
    })).toEqual({
      companyName: '새 거래처',
      representative: '김대표',
      businessNo: undefined,
      phone: undefined,
      email: undefined,
      address: undefined,
      memo: undefined,
    })
  })

  it('공백 회사명은 요청하지 않는다', async () => {
    render(<ClientCreateModal open onClose={vi.fn()} onCreated={vi.fn()} />)
    fireEvent.change(screen.getByPlaceholderText('업체명'), { target: { value: '   ' } })
    fireEvent.click(screen.getByRole('button', { name: '등록' }))

    expect(await screen.findByText('업체명을 입력하세요')).toBeInTheDocument()
    expect(mutateAsync).not.toHaveBeenCalled()
  })

  it('성공 시 생성된 거래처를 전달한다', async () => {
    mutateAsync.mockResolvedValue(createdClient)
    const onCreated = vi.fn()
    render(<ClientCreateModal open onClose={vi.fn()} onCreated={onCreated} />)
    fireEvent.change(screen.getByPlaceholderText('업체명'), { target: { value: ' 새 거래처 ' } })
    fireEvent.click(screen.getByRole('button', { name: '등록' }))

    await waitFor(() => expect(mutateAsync).toHaveBeenCalledWith({
      companyName: '새 거래처',
      representative: undefined,
      businessNo: undefined,
      phone: undefined,
      email: undefined,
      address: undefined,
      memo: undefined,
    }))
    expect(onCreated).toHaveBeenCalledWith(createdClient)
  })

  it('서버 오류 시 입력을 보존하고 재시도할 수 있다', async () => {
    mutateAsync
      .mockRejectedValueOnce({ response: { data: { error: '거래처 등록에 실패했습니다.' } } })
      .mockResolvedValueOnce(createdClient)
    const onCreated = vi.fn()
    render(<ClientCreateModal open onClose={vi.fn()} onCreated={onCreated} />)
    fireEvent.change(screen.getByPlaceholderText('업체명'), { target: { value: '새 거래처' } })
    fireEvent.click(screen.getByRole('button', { name: '등록' }))

    expect(await screen.findByText('거래처 등록에 실패했습니다.')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('업체명')).toHaveValue('새 거래처')
    fireEvent.click(screen.getByRole('button', { name: '등록' }))
    await waitFor(() => expect(onCreated).toHaveBeenCalledWith(createdClient))
  })
})
