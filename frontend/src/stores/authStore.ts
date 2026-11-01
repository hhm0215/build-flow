import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export const AUTH_STORAGE_KEY = 'buildflow-auth'

interface AuthState {
  accessToken: string | null
  /** access 만료 시 `/auth/refresh`로 회전 발급받는 데 쓴다. 서버가 Redis 값과 대조·회전·로그아웃 폐기한다. */
  refreshToken: string | null
  /**
   * 로그인마다 새로 만드는 클라이언트 세션 세대값. refresh 회전은 유지하고 로그아웃은 지운다.
   * 다른 탭의 로그아웃 뒤에 늦게 도착한 재발급 응답이 세션을 되살리지 않도록 비교한다(보안 토큰 아님).
   */
  sessionId: string | null
  isAuthenticated: boolean
  /** 로그인 성공 — 새 세션을 시작한다. */
  setTokens: (accessToken: string, refreshToken?: string | null) => void
  /** refresh 회전 — 같은 세션의 토큰만 바꾼다. */
  rotateTokens: (accessToken: string, refreshToken: string) => void
  logout: () => void
}

type PersistedAuth = Pick<AuthState, 'accessToken' | 'refreshToken' | 'sessionId' | 'isAuthenticated'>

function newSessionId() {
  // crypto.randomUUID는 보안 컨텍스트(HTTPS·localhost)에서만 있다 — LAN http 접속도 고려해 대체값을 둔다.
  const random = globalThis.crypto?.randomUUID?.()
  return random ?? `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
}

/** 이 탭이 로그아웃으로 끝낸 세션. 다른 탭이 같은 세션 토큰을 다시 써도 따르지 않는다. */
const endedSessionIds = new Set<string>()

const LOGGED_OUT = { accessToken: null, refreshToken: null, sessionId: null, isAuthenticated: false } as const

export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      ...LOGGED_OUT,
      setTokens: (accessToken, refreshToken = null) => {
        set({ accessToken, refreshToken, sessionId: newSessionId(), isAuthenticated: true })
      },
      rotateTokens: (accessToken, refreshToken) => {
        set({ accessToken, refreshToken, isAuthenticated: true })
      },
      logout: () => {
        const { sessionId } = get()
        if (sessionId) endedSessionIds.add(sessionId)
        set({ ...LOGGED_OUT })
      },
    }),
    {
      name: AUTH_STORAGE_KEY,
      partialize: (state): PersistedAuth => ({
        accessToken: state.accessToken,
        refreshToken: state.refreshToken,
        sessionId: state.sessionId,
        isAuthenticated: state.isAuthenticated,
      }),
    },
  ),
)

/** 다른 탭이 쓴 최신 저장값. 읽을 수 없으면 null(메모리 상태만 믿는다). */
export function readPersistedAuth(raw?: string | null): PersistedAuth | null {
  try {
    const text = raw === undefined ? localStorage.getItem(AUTH_STORAGE_KEY) : raw
    // 로그아웃도 null 토큰을 저장하므로 키가 없으면 "알 수 없음"으로 본다.
    if (!text) return null
    const state = JSON.parse(text)?.state
    if (!state || typeof state !== 'object') return null
    return {
      accessToken: state.accessToken ?? null,
      refreshToken: state.refreshToken ?? null,
      sessionId: state.sessionId ?? null,
      isAuthenticated: state.isAuthenticated === true,
    }
  } catch {
    return null
  }
}

/** 이 탭이 로그아웃한 세션을 다른 탭이 늦게 되살렸는지. */
export function isEndedSession(sessionId: string | null) {
  return sessionId !== null && endedSessionIds.has(sessionId)
}

// 다른 탭이 토큰을 회전하거나 로그아웃하면 이 탭도 같은 값을 쓴다.
// 오래된 refresh 토큰으로 재발급을 시도해 회전된 세션까지 끊는 경합을 줄인다.
// 단, 이 탭이 로그아웃한 세션의 토큰이 나중에 다시 쓰이면(늦은 재발급 응답) 따르지 않고 로그아웃 상태를 다시 쓴다.
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (event) => {
    if (event.key !== AUTH_STORAGE_KEY) return
    const incoming = readPersistedAuth(event.newValue)
    if (incoming?.isAuthenticated && isEndedSession(incoming.sessionId)) {
      useAuthStore.setState({ ...LOGGED_OUT })
      return
    }
    void useAuthStore.persist.rehydrate()
  })
}
