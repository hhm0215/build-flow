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

  it.each([500, 'network', 'unsuccessful-response'] as const)(
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
  it.each([400, 401, 403, 404])('만료 access로 로그아웃 → refresh가 %s로 거부되면 로컬 토큰을 지우고 로그인으로 간다', async (status) => {
    useAuthStore.getState().setTokens('expired-access', 'dead-refresh')
    const adapter = vi.fn<AxiosAdapter>(async (config) => {
      const code = config.url === '/auth/refresh' ? status : 401
      const response = { config, status: code, statusText: '', headers: {}, data: { success: false, data: null, error: 'x' } }
      throw new AxiosError('Request failed', undefined, config, undefined, response)
    })
    axiosInstance.defaults.adapter = adapter
    renderLogout()

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))

    expect(await screen.findByText('관리자 로그인 화면')).toBeInTheDocument()
    expect(adapter.mock.calls.map((c) => c[0].url)).toEqual(['/auth/logout', '/auth/refresh'])
    expect(useAuthStore.getState()).toMatchObject({ accessToken: null, refreshToken: null, isAuthenticated: false })
    expect(JSON.parse(localStorage.getItem('buildflow-auth')!).state.accessToken).toBeNull()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('refresh 토큰 없이 로그아웃이 401이면 살아 있는 토큰이 없으므로 로컬을 지우고 로그인으로 간다', async () => {
    const adapter = vi.fn<AxiosAdapter>(async (config) => {
      const response = { config, status: 401, statusText: '', headers: {}, data: { success: false, data: null, error: 'x' } }
      throw new AxiosError('Request failed', undefined, config, undefined, response)
    })
    axiosInstance.defaults.adapter = adapter
    renderLogout()

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))

    expect(await screen.findByText('관리자 로그인 화면')).toBeInTheDocument()
    expect(adapter).toHaveBeenCalledTimes(1)
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
  })

  it.each([503, 'network'] as const)('만료 access로 로그아웃 → refresh가 %s(일시 장애)면 토큰을 보존하고 재시도를 안내한다', async (failure) => {
    useAuthStore.getState().setTokens('expired-access', 'live-refresh')
    const adapter = vi.fn<AxiosAdapter>(async (config) => {
      if (config.url === '/auth/refresh' && failure === 'network') throw new AxiosError('Network Error', 'ERR_NETWORK', config)
      const code = config.url === '/auth/refresh' ? 503 : 401
      const response = { config, status: code, statusText: '', headers: {}, data: { success: false, data: null, error: 'x' } }
      throw new AxiosError('Request failed', undefined, config, undefined, response)
    })
    axiosInstance.defaults.adapter = adapter
    renderLogout()

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('서버 로그아웃을 확인하지 못했습니다')
    expect(useAuthStore.getState()).toMatchObject({
      accessToken: 'expired-access', refreshToken: 'live-refresh', isAuthenticated: true,
    })
    expect(screen.queryByText('관리자 로그인 화면')).not.toBeInTheDocument()
  })
})
