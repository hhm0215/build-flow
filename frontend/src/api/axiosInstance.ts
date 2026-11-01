import axios, { type InternalAxiosRequestConfig } from 'axios'
import { isEndedSession, readPersistedAuth, useAuthStore } from '../stores/authStore'

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

type RetriableConfig = InternalAxiosRequestConfig & { _authRetried?: boolean; _authTokenSynced?: boolean }

function sentAuthorization(config: InternalAxiosRequestConfig): string | null {
  const headers = config.headers as InternalAxiosRequestConfig['headers'] | undefined
  const value = headers?.get?.('Authorization') ?? headers?.Authorization
  return typeof value === 'string' ? value : null
}

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

/** 서버가 직전 refresh의 동시 재발급을 세션 폐기 없이 알리는 응답. */
const REFRESH_IN_PROGRESS_STATUS = 409
const ROTATION_WAIT_MS = 2000
const ROTATION_POLL_MS = 100

async function waitForRotatedAccess(usedRefreshToken: string): Promise<string | null> {
  for (let waited = 0; waited <= ROTATION_WAIT_MS; waited += ROTATION_POLL_MS) {
    const persisted = readPersistedAuth()
    if (persisted?.isAuthenticated && persisted.accessToken && persisted.refreshToken
      && persisted.refreshToken !== usedRefreshToken && !isEndedSession(persisted.sessionId)) {
      await useAuthStore.persist.rehydrate()
      return persisted.accessToken
    }
    await new Promise((resolve) => setTimeout(resolve, ROTATION_POLL_MS))
  }
  return null
}

let refreshInFlight: Promise<string | null> | null = null

async function requestRefresh(): Promise<string | null> {
  const { refreshToken: usedRefreshToken, sessionId: usedSessionId } = useAuthStore.getState()
  if (!usedRefreshToken) return null
  try {
    const response = await axiosInstance.post('/auth/refresh', { refreshToken: usedRefreshToken })
    const tokens = response.data?.data
    if (response.data?.success !== true || !tokens?.accessToken || !tokens?.refreshToken) {
      throw new Error('Refresh response was not confirmed')
    }
    // 재발급 대기 중 이 탭이나 다른 탭(아직 storage 이벤트 전)이 로그아웃·재로그인했다면
    // 늦게 도착한 응답으로 세션을 되살리거나 새 세션을 덮어쓰지 않는다.
    const current = useAuthStore.getState()
    const persisted = readPersistedAuth()
    const persistedChanged = persisted !== null
      && (!persisted.isAuthenticated || persisted.sessionId !== usedSessionId)
    if (!current.isAuthenticated || current.sessionId !== usedSessionId || persistedChanged) {
      if (persisted?.isAuthenticated && persisted.accessToken && !isEndedSession(persisted.sessionId)) {
        await useAuthStore.persist.rehydrate()
        return persisted.accessToken
      }
      return null
    }
    current.rotateTokens(tokens.accessToken, tokens.refreshToken)
    return tokens.accessToken as string
  } catch (error) {
    const status = (error as { response?: { status?: number } })?.response?.status
    if (status === REFRESH_IN_PROGRESS_STATUS) {
      // 다른 탭이 같은 refresh로 방금 회전했다(서버 유예 내 409, 세션 유지). 그 탭이 저장소에 쓸 새 토큰을 잠시 기다린다.
      const rotatedAccess = await waitForRotatedAccess(usedRefreshToken)
      if (rotatedAccess) return rotatedAccess
      throw error
    }
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

    // 요청을 보낸 뒤 다른 요청·탭이 이미 access를 갱신했다면 refresh(회전) 없이 새 access로 한 번 재시도한다.
    const currentAccess = useAuthStore.getState().accessToken
    if (!config._authTokenSynced && currentAccess && sentAuthorization(config) !== `Bearer ${currentAccess}`) {
      config._authTokenSynced = true
      config.headers.Authorization = `Bearer ${currentAccess}`
      return axiosInstance(config)
    }

    let accessToken: string | null
    try {
      accessToken = await refreshAccessToken()
    } catch {
      // 재발급 서버 일시 장애 — 원 요청 실패만 알리고 로그인 상태는 유지한다.
      return Promise.reject(error)
    }
    if (!accessToken) {
      // refresh가 서버에서 거부됨(400/401/403/404) 또는 refresh 없음 = 서버에 살아 있는 토큰이 없다.
      // 로그아웃 요청이면 로컬 토큰만 지우고 화면 이동은 호출 화면(LogoutButton)이 맡는다.
      if (isLogout) useAuthStore.getState().logout()
      else handleSessionExpired()
      return Promise.reject(error)
    }
    config._authRetried = true
    config.headers.Authorization = `Bearer ${accessToken}`
    return axiosInstance(config)
  },
)

export default axiosInstance
