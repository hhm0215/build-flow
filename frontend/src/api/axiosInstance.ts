import axios, { type InternalAxiosRequestConfig } from 'axios'
import { useAuthStore } from '../stores/authStore'

const axiosInstance = axios.create({
  baseURL: '/api/v1',
  timeout: 10000,
})

const LOGIN_PATHS = ['/auth/login', '/api/v1/auth/login']
const LOGOUT_PATHS = ['/auth/logout', '/api/v1/auth/logout']
const REFRESH_PATHS = ['/auth/refresh', '/api/v1/auth/refresh']

function pathOf(requestUrl?: string) {
  return requestUrl?.split('?')[0] ?? ''
}

type RetriableConfig = InternalAxiosRequestConfig & { _authRetried?: boolean }

axiosInstance.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken
  // refresh 요청은 만료된 access를 실어 보낼 이유가 없다 — 본문의 refresh 토큰만 쓴다.
  if (token && !REFRESH_PATHS.includes(pathOf(config.url))) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/** 인증 만료 공통 처리 — axios 인터셉터와 스트리밍 fetch(chat.api)가 공유한다. */
export function handleSessionExpired() {
  useAuthStore.getState().logout()
  window.location.href = '/login'
}

/** 로그인/로그아웃 실패는 호출 화면에서 처리하고, 보호 API의 401만 세션 만료로 처리한다. */
export function shouldHandleSessionExpired(status?: number, requestUrl?: string) {
  const path = pathOf(requestUrl)
  const handledByCaller = [...LOGIN_PATHS, ...LOGOUT_PATHS, ...REFRESH_PATHS]
  return status === 401 && !handledByCaller.includes(path)
}

/** refresh 토큰이 서버에서 거부된 응답 — 세션 종료로 본다. 네트워크·5xx는 일시 장애로 보고 세션을 지우지 않는다. */
const REFRESH_REJECTED_STATUSES = [400, 401, 403, 404]

let refreshInFlight: Promise<string | null> | null = null

async function requestRefresh(): Promise<string | null> {
  const usedRefreshToken = useAuthStore.getState().refreshToken
  if (!usedRefreshToken) return null
  try {
    const response = await axiosInstance.post('/auth/refresh', { refreshToken: usedRefreshToken })
    const tokens = response.data?.data
    if (response.data?.success !== true || !tokens?.accessToken || !tokens?.refreshToken) {
      throw new Error('Refresh response was not confirmed')
    }
    // 재발급 대기 중 사용자가 로그아웃했다면 세션을 되살리지 않는다.
    if (!useAuthStore.getState().isAuthenticated) return null
    useAuthStore.getState().setTokens(tokens.accessToken, tokens.refreshToken)
    return tokens.accessToken as string
  } catch (error) {
    const status = (error as { response?: { status?: number } })?.response?.status
    if (status === undefined || !REFRESH_REJECTED_STATUSES.includes(status)) throw error
    // 다른 탭이 먼저 회전했다면 저장소의 새 토큰을 쓴다.
    await useAuthStore.persist.rehydrate()
    const latest = useAuthStore.getState()
    if (latest.refreshToken && latest.refreshToken !== usedRefreshToken && latest.accessToken) {
      return latest.accessToken
    }
    return null
  }
}

/**
 * access 토큰을 한 번만 재발급한다. 동시에 여러 요청이 401을 받아도 refresh 호출은 하나로 합친다
 * (서버가 refresh를 회전하므로 병렬 호출은 두 번째가 실패해 세션을 끊는다).
 * 새 access 토큰, 서버가 refresh를 거부하면 null을 돌려주고 일시 장애는 예외로 던진다.
 */
export function refreshAccessToken(): Promise<string | null> {
  if (!refreshInFlight) {
    refreshInFlight = requestRefresh().finally(() => {
      refreshInFlight = null
    })
  }
  return refreshInFlight
}

axiosInstance.interceptors.response.use(
  (response) => response,
  async (error) => {
    const config = error.config as RetriableConfig | undefined
    const status = error.response?.status
    const path = pathOf(config?.url)
    if (status !== 401 || !config || LOGIN_PATHS.includes(path) || REFRESH_PATHS.includes(path)) {
      return Promise.reject(error)
    }
    const isLogout = LOGOUT_PATHS.includes(path)
    if (config._authRetried) {
      if (!isLogout) handleSessionExpired()
      return Promise.reject(error)
    }

    let accessToken: string | null
    try {
      accessToken = await refreshAccessToken()
    } catch {
      // 재발급 서버 일시 장애 — 원 요청 실패만 알리고 로그인 상태는 유지한다.
      return Promise.reject(error)
    }
    if (!accessToken) {
      // refresh가 서버에서 거부됨 = 세션 종료. 로그아웃 요청은 기존 계약대로 호출 화면이 실패를 알리고 토큰을 보존한다.
      if (!isLogout) handleSessionExpired()
      return Promise.reject(error)
    }
    config._authRetried = true
    config.headers.Authorization = `Bearer ${accessToken}`
    return axiosInstance(config)
  },
)

export default axiosInstance
