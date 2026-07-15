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

axiosInstance.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      handleSessionExpired()
    }
    return Promise.reject(error)
  },
)

export default axiosInstance
