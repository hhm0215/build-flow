import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ClientEditModal, { toClientUpdateRequest } from './ClientEditModal'
import type { Client } from '../../types'

const mutateAsync = vi.hoisted(() => vi.fn())

vi.mock('../../api/clients.api', () => ({
  useUpdateClient: () => ({ mutateAsync, isPending: false }),
}))

const client: Client = {
  id: 6,
  companyName: '기존 거래처',
  representative: '김대표',
  businessNo: null,
  phone: null,
  email: null,
  address: '서울',
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

describe('ClientEditModal', () => {
  it('비운 선택 필드를 null로 보내 전체 교체한다', () => {
    expect(toClientUpdateRequest({ companyName: ' 새 이름 ', representative: '  ' })).toEqual({
      companyName: '새 이름',
      representative: null,
      businessNo: null,
      phone: null,
      email: null,
      address: null,
      memo: null,
    })
  })

  it('기존 값을 채우고 거래처 PUT 요청을 보낸다', async () => {
    mutateAsync.mockResolvedValue({ ...client, companyName: '수정된 거래처' })
    const onClose = vi.fn()
    render(<ClientEditModal client={client} onClose={onClose} />)
    expect(screen.getByText(/연결된 모든 현장에 반영됩니다/)).toBeInTheDocument()
    expect(screen.getByPlaceholderText('업체명')).toHaveValue('기존 거래처')
    expect(screen.getByPlaceholderText('주소 (선택)')).toHaveValue('서울')
    fireEvent.change(screen.getByPlaceholderText('업체명'), { target: { value: ' 수정된 거래처 ' } })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => expect(mutateAsync).toHaveBeenCalledWith({
      id: 6,
      companyName: '수정된 거래처',
      representative: '김대표',
      businessNo: null,
      phone: null,
      email: null,
      address: '서울',
      memo: null,
    }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('공백 업체명은 요청하지 않는다', async () => {
    render(<ClientEditModal client={client} onClose={vi.fn()} />)
    fireEvent.change(screen.getByPlaceholderText('업체명'), { target: { value: '   ' } })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))
    expect(await screen.findByText('업체명을 입력하세요')).toBeInTheDocument()
    expect(mutateAsync).not.toHaveBeenCalled()
  })

  it('서버 실패 시 입력을 보존하고 재시도한다', async () => {
    mutateAsync.mockRejectedValueOnce({ response: { data: { error: '수정할 수 없습니다.' } } })
    mutateAsync.mockResolvedValueOnce(client)
    const onClose = vi.fn()
    render(<ClientEditModal client={client} onClose={onClose} />)
    fireEvent.click(screen.getByRole('button', { name: '저장' }))
    expect(await screen.findByText('수정할 수 없습니다.')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('업체명')).toHaveValue('기존 거래처')
    fireEvent.click(screen.getByRole('button', { name: '저장' }))
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
  })

  it('빠른 중복 저장을 한 번의 PUT으로 제한한다', async () => {
    let resolveMutation: ((value: Client) => void) | undefined
    mutateAsync.mockReturnValue(new Promise((resolve) => { resolveMutation = resolve }))
    const onClose = vi.fn()
    render(<ClientEditModal client={client} onClose={onClose} />)
    const save = screen.getByRole('button', { name: '저장' })
    fireEvent.click(save)
    fireEvent.click(save)
    await waitFor(() => expect(mutateAsync).toHaveBeenCalledTimes(1))
    fireEvent.click(screen.getByRole('button', { name: '취소' }))
    expect(onClose).not.toHaveBeenCalled()
    await act(async () => { resolveMutation?.(client) })
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
