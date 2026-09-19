import { afterEach, describe, expect, it } from 'vitest'
import axiosInstance, { shouldHandleSessionExpired } from './axiosInstance'
import { useAuthStore } from '../stores/authStore'

afterEach(() => {
  useAuthStore.getState().logout()
})

describe('401 세션 만료 처리', () => {
  it('로그인 실패는 세션 만료로 취급하지 않는다', () => {
    expect(shouldHandleSessionExpired(401, '/auth/login')).toBe(false)
    expect(shouldHandleSessionExpired(401, '/api/v1/auth/login')).toBe(false)
    expect(shouldHandleSessionExpired(401, '/auth/login?source=form')).toBe(false)
  })

  it('보호 API의 401과 다른 응답 코드를 구분한다', () => {
    expect(shouldHandleSessionExpired(401, '/sites')).toBe(true)
    expect(shouldHandleSessionExpired(403, '/sites')).toBe(false)
  })

  it('로그인 API의 401에서 전역 인터셉터가 기존 인증 상태를 지우지 않는다', async () => {
    useAuthStore.getState().setTokens('active-token')

    await expect(axiosInstance.post('/auth/login', {}, {
      adapter: async (config) => Promise.reject({ config, response: { status: 401 } }),
    })).rejects.toMatchObject({ response: { status: 401 } })

    expect(useAuthStore.getState().accessToken).toBe('active-token')
    expect(useAuthStore.getState().isAuthenticated).toBe(true)
  })
})
