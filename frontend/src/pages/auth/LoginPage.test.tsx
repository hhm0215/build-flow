import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import axiosInstance from '../../api/axiosInstance'
import { useAuthStore } from '../../stores/authStore'
import LoginPage from './LoginPage'

vi.mock('../../mocks/mockMode', () => ({ isMockMode: false }))

beforeEach(() => {
  useAuthStore.getState().logout()
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false,
    media: query,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }))
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('LoginPage 실제 API 모드', () => {
  it('loginId와 비밀번호만 로그인 API로 전송한다', async () => {
    const post = vi.spyOn(axiosInstance, 'post').mockResolvedValueOnce({
      data: { data: { accessToken: 'access-token' } },
    })
    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>,
    )

    fireEvent.change(screen.getByPlaceholderText('관리자 아이디 입력'), {
      target: { value: 'admin' },
    })
    fireEvent.change(screen.getByPlaceholderText('관리자 비밀번호 입력'), {
      target: { value: 'secret-password' },
    })
    fireEvent.click(screen.getByRole('button', { name: '관리자 로그인' }))

    await waitFor(() => expect(post).toHaveBeenCalledWith('/auth/login', {
      loginId: 'admin',
      password: 'secret-password',
    }))
  })

  it('Mock 모드 안내를 표시하지 않는다', () => {
    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>,
    )

    expect(screen.queryByText('Mock 모드')).not.toBeInTheDocument()
  })

  it('로그인 API 오류를 폼에 표시한다', async () => {
    vi.spyOn(axiosInstance, 'post').mockRejectedValueOnce({
      response: { data: { error: '아이디 또는 비밀번호가 올바르지 않습니다.' } },
    })
    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>,
    )

    fireEvent.change(screen.getByPlaceholderText('관리자 아이디 입력'), {
      target: { value: 'admin' },
    })
    fireEvent.change(screen.getByPlaceholderText('관리자 비밀번호 입력'), {
      target: { value: 'incorrect' },
    })
    fireEvent.click(screen.getByRole('button', { name: '관리자 로그인' }))

    expect(await screen.findByText('아이디 또는 비밀번호가 올바르지 않습니다.')).toBeInTheDocument()
  })
})
