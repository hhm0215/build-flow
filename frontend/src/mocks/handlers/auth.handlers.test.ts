import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { setupServer } from 'msw/node'
import type { ApiResponse, LoginResponse } from '../../types'
import { authHandlers } from './auth.handlers'

const server = setupServer(...authHandlers)
const url = new URL('/api/v1/auth/refresh', document.baseURI)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

describe('인증 refresh MSW 계약', () => {
  it('refreshToken 누락은 400, 있으면 회전된 TokenResponse를 돌려준다', async () => {
    const missing = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })
    expect(missing.status).toBe(400)

    const refreshed = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: 'mock-refresh-token-buildflow' }),
    })
    expect(refreshed.status).toBe(200)
    const body = await refreshed.json() as ApiResponse<LoginResponse>
    expect(body.success).toBe(true)
    expect(body.data).toMatchObject({ tokenType: 'Bearer' })
    expect(body.data?.accessToken).toBeTruthy()
    expect(body.data?.refreshToken).not.toBe('mock-refresh-token-buildflow')
  })
})
