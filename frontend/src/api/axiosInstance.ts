import axios from 'axios'
import { useAuthStore } from '../stores/authStore'

const axiosInstance = axios.create({
  baseURL: '/api/v1',
  timeout: 10000,
})

axiosInstance.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/** 인증 만료 공통 처리 — axios 인터셉터와 스트리밍 fetch(chat.api)가 공유한다. */
export function handleSessionExpired() {
  useAuthStore.getState().logout()
  window.location.href = '/login'
}

/** 로그인 실패(401)는 폼에서 처리하고, 보호 API의 401만 세션 만료로 처리한다. */
export function shouldHandleSessionExpired(status?: number, requestUrl?: string) {
  const path = requestUrl?.split('?')[0]
  return status === 401 && path !== '/auth/login' && path !== '/api/v1/auth/login'
}

axiosInstance.interceptors.response.use(
  (response) => response,
  (error) => {
    if (shouldHandleSessionExpired(error.response?.status, error.config?.url)) {
      handleSessionExpired()
    }
    return Promise.reject(error)
  },
)

export default axiosInstance
