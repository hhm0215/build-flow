import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AxiosError, type AxiosAdapter, type AxiosResponse } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import axiosInstance from '../api/axiosInstance'
import { useAuthStore } from '../stores/authStore'
import LogoutButton from './LogoutButton'

const originalAdapter = axiosInstance.defaults.adapter

function renderLogout() {
  render(
    <MemoryRouter initialEntries={['/dashboard']}>
      <Routes>
        <Route path="/dashboard" element={<LogoutButton />} />
        <Route path="/login" element={<p>관리자 로그인 화면</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  useAuthStore.getState().setTokens('active-token')
})

afterEach(() => {
  axiosInstance.defaults.adapter = originalAdapter
  useAuthStore.getState().logout()
  vi.restoreAllMocks()
})

describe('서버 로그아웃', () => {
  it('현재 토큰으로 서버 폐기를 요청하고 성공 응답 후에만 로컬 인증을 지운다', async () => {
    let confirmLogout: (() => void) | undefined
    const adapter = vi.fn<AxiosAdapter>((config) => new Promise<AxiosResponse>((resolve) => {
      confirmLogout = () => resolve({
        config, status: 200, statusText: 'OK', headers: {},
        data: { success: true, data: null, error: null },
      })
    }))
    axiosInstance.defaults.adapter = adapter
    renderLogout()

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))
    await waitFor(() => expect(adapter).toHaveBeenCalledTimes(1))
    expect(adapter.mock.calls[0][0].url).toBe('/auth/logout')
    expect(adapter.mock.calls[0][0].method).toBe('post')
    expect(adapter.mock.calls[0][0].headers.get('Authorization')).toBe('Bearer active-token')
    expect(useAuthStore.getState().accessToken).toBe('active-token')
    expect(screen.getByRole('button', { name: '로그아웃 중…' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '로그아웃 중…' }))
    expect(adapter).toHaveBeenCalledTimes(1)

    await act(async () => confirmLogout?.())
    expect(await screen.findByText('관리자 로그인 화면')).toBeInTheDocument()
    expect(useAuthStore.getState().accessToken).toBeNull()
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
    expect(JSON.parse(localStorage.getItem('buildflow-auth')!).state.accessToken).toBeNull()
  })

  it.each([401, 500, 'network', 'unsuccessful-response'] as const)(
    '%s 실패를 명시하고 토큰을 보존하여 서버 로그아웃을 재시도할 수 있다',
    async (failure) => {
      const adapter = vi.fn<AxiosAdapter>(async (config) => {
        if (failure === 'network') throw new AxiosError('Network Error', 'ERR_NETWORK', config)
        const response = {
          config, status: typeof failure === 'number' ? failure : 200,
          statusText: '', headers: {}, data: { success: false, data: null, error: 'failed' },
        }
        if (typeof failure === 'number') throw new AxiosError('Request failed', undefined, config, undefined, response)
        return response
      })
      axiosInstance.defaults.adapter = adapter
      renderLogout()

      fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))
      expect(await screen.findByRole('alert')).toHaveTextContent('서버 로그아웃을 확인하지 못했습니다')
      expect(screen.getByRole('alert')).toHaveTextContent('로그인 정보가 아직 유효할 수 있어')
      expect(useAuthStore.getState().accessToken).toBe('active-token')
      expect(useAuthStore.getState().isAuthenticated).toBe(true)
      expect(screen.queryByText('관리자 로그인 화면')).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: '로그아웃' })).toBeEnabled()

      adapter.mockImplementationOnce(async (config) => ({
        config, status: 200, statusText: 'OK', headers: {},
        data: { success: true, data: null, error: null },
      }))
      fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))
      expect(await screen.findByText('관리자 로그인 화면')).toBeInTheDocument()
      expect(adapter.mock.calls[1][0].headers.get('Authorization')).toBe('Bearer active-token')
      expect(useAuthStore.getState().accessToken).toBeNull()
    },
  )
})
