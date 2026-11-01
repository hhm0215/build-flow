import { AxiosError, type AxiosAdapter, type AxiosResponse } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import axiosInstance, { shouldHandleSessionExpired } from './axiosInstance'
import { AUTH_STORAGE_KEY, useAuthStore } from '../stores/authStore'

afterEach(() => {
  useAuthStore.getState().logout()
})

describe('401 세션 만료 처리', () => {
  it('로그인 실패는 세션 만료로 취급하지 않는다', () => {
    expect(shouldHandleSessionExpired(401, '/auth/login')).toBe(false)
    expect(shouldHandleSessionExpired(401, '/api/v1/auth/login')).toBe(false)
    expect(shouldHandleSessionExpired(401, '/auth/login?source=form')).toBe(false)
  })

  it('refresh 자체의 401은 재발급 루프를 만들지 않도록 호출 측에서 처리한다', () => {
    expect(shouldHandleSessionExpired(401, '/auth/refresh')).toBe(false)
  })

  it('보호 API의 401과 다른 응답 코드를 구분한다', () => {
    expect(shouldHandleSessionExpired(401, '/sites')).toBe(true)
    expect(shouldHandleSessionExpired(403, '/sites')).toBe(false)
  })

  it('로그아웃 실패는 호출 화면에서 처리하도록 전역 상태를 유지한다', () => {
    expect(shouldHandleSessionExpired(401, '/auth/logout')).toBe(false)
    expect(shouldHandleSessionExpired(401, '/api/v1/auth/logout')).toBe(false)
    expect(shouldHandleSessionExpired(401, '/auth/logout?source=sidebar')).toBe(false)
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

type Reply = { status: number; data?: unknown } | Error

const originalAdapter = axiosInstance.defaults.adapter

/** URL별 응답 큐로 가짜 서버를 만든다 — 요청 순서·Authorization 헤더·본문을 기록한다. */
function fakeServer(routes: Record<string, Reply[]>) {
  const calls: { url: string; auth: string | null; body: unknown }[] = []
  const adapter = vi.fn<AxiosAdapter>(async (config) => {
    const url = config.url ?? ''
    calls.push({
      url,
      auth: (config.headers.get('Authorization') as string | undefined) ?? null,
      body: typeof config.data === 'string' ? JSON.parse(config.data) : config.data,
    })
    const reply = routes[url]?.shift()
    if (!reply) throw new Error(`unexpected request ${url}`)
    if (reply instanceof Error) throw reply
    const response: AxiosResponse = { config, status: reply.status, statusText: '', headers: {}, data: reply.data }
    if (reply.status >= 400) {
      throw new AxiosError('fail', String(reply.status), config, null, response)
    }
    return response
  })
  axiosInstance.defaults.adapter = adapter
  return { adapter, calls }
}

const unauthorized = { status: 401, data: { success: false, data: null, error: '토큰이 만료되었습니다.' } }
const rotated = (n: number) => ({
  status: 200,
  data: {
    success: true,
    data: { accessToken: `access-${n}`, refreshToken: `refresh-${n}`, tokenType: 'Bearer', expiresIn: 1800000 },
    error: null,
  },
})
const ok = (data: unknown) => ({ status: 200, data: { success: true, data, error: null } })

describe('access 만료 후 refresh 재발급', () => {
  beforeEach(() => {
    useAuthStore.getState().setTokens('expired-access', 'refresh-0')
  })
  afterEach(() => {
    axiosInstance.defaults.adapter = originalAdapter
    vi.restoreAllMocks()
  })

  it('첫 API 401 → refresh 성공 → 새 access로 원 요청을 재시도하고 회전된 토큰을 저장한다', async () => {
    const { calls } = fakeServer({ '/taxes': [unauthorized, ok([{ id: 1 }])], '/auth/refresh': [rotated(1)] })

    const response = await axiosInstance.get('/taxes')

    expect(response.data.data).toEqual([{ id: 1 }])
    expect(calls.map((c) => c.url)).toEqual(['/taxes', '/auth/refresh', '/taxes'])
    expect(calls[0].auth).toBe('Bearer expired-access')
    expect(calls[1].auth).toBeNull()
    expect(calls[1].body).toEqual({ refreshToken: 'refresh-0' })
    expect(calls[2].auth).toBe('Bearer access-1')
    expect(useAuthStore.getState()).toMatchObject({ accessToken: 'access-1', refreshToken: 'refresh-1', isAuthenticated: true })
    expect(JSON.parse(localStorage.getItem(AUTH_STORAGE_KEY)!).state.refreshToken).toBe('refresh-1')
  })

  it('동시에 401을 받은 요청들은 refresh 한 번을 공유한다 (회전 토큰 이중 사용 방지)', async () => {
    const { calls } = fakeServer({
      '/taxes': [unauthorized, ok('t')],
      '/sites': [unauthorized, ok('s')],
      '/purchases': [unauthorized, ok('p')],
      '/auth/refresh': [rotated(1)],
    })

    const results = await Promise.all(['/taxes', '/sites', '/purchases'].map((url) => axiosInstance.get(url)))

    expect(results.map((r) => r.data.data)).toEqual(['t', 's', 'p'])
    expect(calls.filter((c) => c.url === '/auth/refresh')).toHaveLength(1)
    expect(calls.filter((c) => c.url !== '/auth/refresh' && c.auth === 'Bearer access-1')).toHaveLength(3)
  })

  it('앱 재진입: 저장소의 만료된 access + 유효한 refresh면 로그인 상태를 유지하고 재발급한다', async () => {
    useAuthStore.getState().logout()
    localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify({
      state: { accessToken: 'stale-access', refreshToken: 'stored-refresh', isAuthenticated: true }, version: 0,
    }))
    await useAuthStore.persist.rehydrate()
    expect(useAuthStore.getState().isAuthenticated).toBe(true)
    const { calls } = fakeServer({ '/taxes': [unauthorized, ok([])], '/auth/refresh': [rotated(2)] })

    await axiosInstance.get('/taxes')

    expect(calls[1].body).toEqual({ refreshToken: 'stored-refresh' })
    expect(useAuthStore.getState()).toMatchObject({ accessToken: 'access-2', isAuthenticated: true })
  })

  it('refresh가 거부(401)되면 로그인 상태를 지운다', async () => {
    fakeServer({ '/taxes': [unauthorized], '/auth/refresh': [unauthorized] })

    await expect(axiosInstance.get('/taxes')).rejects.toMatchObject({ response: { status: 401 } })

    expect(useAuthStore.getState()).toMatchObject({ accessToken: null, refreshToken: null, isAuthenticated: false })
  })

  it('refresh 토큰이 없던 기존 세션은 재발급 없이 로그인으로 보낸다', async () => {
    useAuthStore.getState().setTokens('expired-access')
    const { calls } = fakeServer({ '/taxes': [unauthorized] })

    await expect(axiosInstance.get('/taxes')).rejects.toBeTruthy()

    expect(calls.map((c) => c.url)).toEqual(['/taxes'])
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
  })

  it('refresh 서버 일시 장애(네트워크·5xx)는 세션을 지우지 않는다', async () => {
    fakeServer({
      '/taxes': [unauthorized, unauthorized],
      '/auth/refresh': [new Error('Network Error'), { status: 503 }],
    })

    await expect(axiosInstance.get('/taxes')).rejects.toMatchObject({ response: { status: 401 } })
    await expect(axiosInstance.get('/taxes')).rejects.toMatchObject({ response: { status: 401 } })

    expect(useAuthStore.getState()).toMatchObject({
      accessToken: 'expired-access', refreshToken: 'refresh-0', isAuthenticated: true,
    })
  })

  it('재시도한 요청도 401이면 반복하지 않고 로그인으로 보낸다', async () => {
    const { calls } = fakeServer({ '/taxes': [unauthorized, unauthorized], '/auth/refresh': [rotated(1)] })

    await expect(axiosInstance.get('/taxes')).rejects.toBeTruthy()

    expect(calls).toHaveLength(3)
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
  })

  it('다른 탭이 먼저 회전해 refresh가 거부되면 저장소의 새 토큰으로 재시도한다', async () => {
    fakeServer({ '/taxes': [unauthorized, ok('t')], '/auth/refresh': [unauthorized] })
    // 다른 탭이 refresh-0을 회전해 저장소에만 새 값을 쓴 상태
    localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify({
      state: { accessToken: 'other-tab-access', refreshToken: 'other-tab-refresh', isAuthenticated: true }, version: 0,
    }))

    const response = await axiosInstance.get('/taxes')

    expect(response.data.data).toBe('t')
    expect(useAuthStore.getState()).toMatchObject({ accessToken: 'other-tab-access', isAuthenticated: true })
  })

  it('만료된 access로 로그아웃하면 재발급한 토큰으로 서버 폐기를 재시도한다', async () => {
    const { calls } = fakeServer({ '/auth/logout': [unauthorized, ok(null)], '/auth/refresh': [rotated(1)] })

    const response = await axiosInstance.post('/auth/logout')

    expect(response.data.success).toBe(true)
    expect(calls.map((c) => [c.url, c.auth])).toEqual([
      ['/auth/logout', 'Bearer expired-access'],
      ['/auth/refresh', null],
      ['/auth/logout', 'Bearer access-1'],
    ])
  })
})

describe('탭 간 로그아웃 세대값과 최신 access 재사용', () => {
  beforeEach(() => {
    useAuthStore.getState().setTokens('expired-access', 'refresh-0')
  })
  afterEach(() => {
    axiosInstance.defaults.adapter = originalAdapter
    vi.restoreAllMocks()
  })

  it('refresh 회전은 같은 세션 세대값을 유지하고 로그인은 새 세대값을 만든다', async () => {
    const before = useAuthStore.getState().sessionId
    expect(before).toBeTruthy()
    fakeServer({ '/taxes': [unauthorized, ok('t')], '/auth/refresh': [rotated(1)] })

    await axiosInstance.get('/taxes')

    expect(useAuthStore.getState().sessionId).toBe(before)
    useAuthStore.getState().setTokens('next-login', 'next-refresh')
    expect(useAuthStore.getState().sessionId).not.toBe(before)
  })

  it('재발급 대기 중 다른 탭이 로그아웃했다면(storage 이벤트 전) 늦은 응답으로 세션을 되살리지 않는다', async () => {
    let finishRefresh: (() => void) | undefined
    const adapter = vi.fn<AxiosAdapter>((config) => {
      if (config.url === '/auth/refresh') {
        return new Promise<AxiosResponse>((resolve) => {
          finishRefresh = () => resolve({ config, status: 200, statusText: '', headers: {}, data: rotated(9).data })
        })
      }
      const response: AxiosResponse = { config, status: 401, statusText: '', headers: {}, data: unauthorized.data }
      return Promise.reject(new AxiosError('fail', '401', config, null, response))
    })
    axiosInstance.defaults.adapter = adapter
    const pending = axiosInstance.get('/taxes')
    await vi.waitFor(() => expect(finishRefresh).toBeDefined())
    // 다른 탭의 로그아웃이 저장소에만 반영된 상태
    localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify({
      state: { accessToken: null, refreshToken: null, sessionId: null, isAuthenticated: false }, version: 0,
    }))

    finishRefresh?.()
    await expect(pending).rejects.toBeTruthy()

    expect(adapter.mock.calls.map((c) => c[0].url)).toEqual(['/taxes', '/auth/refresh'])
    expect(useAuthStore.getState()).toMatchObject({ accessToken: null, isAuthenticated: false })
    expect(JSON.parse(localStorage.getItem(AUTH_STORAGE_KEY)!).state).toMatchObject({
      accessToken: null, refreshToken: null, isAuthenticated: false,
    })
  })

  it('이 탭이 로그아웃한 세션 토큰이 다른 탭에서 다시 쓰이면 따르지 않고 로그아웃 상태를 다시 쓴다', () => {
    const ended = useAuthStore.getState().sessionId
    useAuthStore.getState().logout()
    const revived = JSON.stringify({
      state: { accessToken: 'late-access', refreshToken: 'late-refresh', sessionId: ended, isAuthenticated: true },
      version: 0,
    })
    localStorage.setItem(AUTH_STORAGE_KEY, revived)

    window.dispatchEvent(new StorageEvent('storage', { key: AUTH_STORAGE_KEY, newValue: revived }))

    expect(useAuthStore.getState().isAuthenticated).toBe(false)
    expect(JSON.parse(localStorage.getItem(AUTH_STORAGE_KEY)!).state.isAuthenticated).toBe(false)
  })

  it('다른 세션(새 로그인)의 저장값은 그대로 따른다', async () => {
    useAuthStore.getState().logout()
    const fresh = JSON.stringify({
      state: { accessToken: 'new-login', refreshToken: 'new-refresh', sessionId: 'other-session', isAuthenticated: true },
      version: 0,
    })
    localStorage.setItem(AUTH_STORAGE_KEY, fresh)

    window.dispatchEvent(new StorageEvent('storage', { key: AUTH_STORAGE_KEY, newValue: fresh }))

    await vi.waitFor(() => expect(useAuthStore.getState().accessToken).toBe('new-login'))
  })

  it('refresh 409(다른 탭이 방금 회전)면 세션을 지우지 않고 그 탭이 저장한 새 토큰으로 재시도한다', async () => {
    const { calls } = fakeServer({ '/taxes': [unauthorized, ok('t')], '/auth/refresh': [{ status: 409 }] })
    const sessionId = useAuthStore.getState().sessionId
    setTimeout(() => {
      localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify({
        state: { accessToken: 'other-tab-access', refreshToken: 'other-tab-refresh', sessionId, isAuthenticated: true },
        version: 0,
      }))
    }, 250)

    const response = await axiosInstance.get('/taxes')

    expect(response.data.data).toBe('t')
    expect(calls.map((c) => [c.url, c.auth])).toEqual([
      ['/taxes', 'Bearer expired-access'],
      ['/auth/refresh', null],
      ['/taxes', 'Bearer other-tab-access'],
    ])
    expect(useAuthStore.getState()).toMatchObject({ refreshToken: 'other-tab-refresh', isAuthenticated: true })
  })

  it('refresh 409 뒤 새 토큰이 오지 않으면 일시 장애로 보고 세션을 유지한다', async () => {
    fakeServer({ '/taxes': [unauthorized], '/auth/refresh': [{ status: 409 }] })

    await expect(axiosInstance.get('/taxes')).rejects.toMatchObject({ response: { status: 401 } })

    expect(useAuthStore.getState()).toMatchObject({
      accessToken: 'expired-access', refreshToken: 'refresh-0', isAuthenticated: true,
    })
  }, 10_000)

  it('실패 요청의 Authorization이 이미 갱신된 access와 다르면 refresh 없이 새 access로 재시도한다', async () => {
    const calls: [string, string | null][] = []
    let first = true
    axiosInstance.defaults.adapter = vi.fn<AxiosAdapter>(async (config) => {
      calls.push([config.url ?? '', (config.headers.get('Authorization') as string | undefined) ?? null])
      if (first) {
        first = false
        // 이 요청이 나간 사이 다른 요청이 access를 갱신했다.
        useAuthStore.getState().rotateTokens('fresh-access', 'refresh-1')
        const response: AxiosResponse = { config, status: 401, statusText: '', headers: {}, data: unauthorized.data }
        throw new AxiosError('fail', '401', config, null, response)
      }
      return { config, status: 200, statusText: '', headers: {}, data: ok('t').data }
    })

    const response = await axiosInstance.get('/taxes')

    expect(response.data.data).toBe('t')
    expect(calls).toEqual([
      ['/taxes', 'Bearer expired-access'],
      ['/taxes', 'Bearer fresh-access'],
    ])
    expect(useAuthStore.getState().refreshToken).toBe('refresh-1')
  })
})
