import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export const AUTH_STORAGE_KEY = 'buildflow-auth'

interface AuthState {
  accessToken: string | null
  /** access 만료 시 `/auth/refresh`로 회전 발급받는 데 쓴다. 서버가 Redis 값과 대조·회전·로그아웃 폐기한다. */
  refreshToken: string | null
  isAuthenticated: boolean
  setTokens: (accessToken: string, refreshToken?: string | null) => void
  logout: () => void
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      accessToken: null,
      refreshToken: null,
      isAuthenticated: false,
      setTokens: (accessToken, refreshToken = null) => set({ accessToken, refreshToken, isAuthenticated: true }),
      logout: () => set({ accessToken: null, refreshToken: null, isAuthenticated: false }),
    }),
    {
      name: AUTH_STORAGE_KEY,
      partialize: (state) => ({
        accessToken: state.accessToken,
        refreshToken: state.refreshToken,
        isAuthenticated: state.isAuthenticated,
      }),
    },
  ),
)

// 다른 탭이 토큰을 회전하거나 로그아웃하면 이 탭도 같은 값을 쓴다.
// 오래된 refresh 토큰으로 재발급을 시도해 회전된 세션까지 끊는 경합을 줄인다.
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (event) => {
    if (event.key === AUTH_STORAGE_KEY) void useAuthStore.persist.rehydrate()
  })
}
