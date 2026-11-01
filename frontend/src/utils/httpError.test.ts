import { AxiosError, AxiosHeaders } from 'axios'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { describe, expect, it } from 'vitest'
import { isNotFoundError, retryUnlessNotFound } from './httpError'

function httpError(status: number) {
  const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig
  const response = { config, status, statusText: '', headers: {}, data: null } as AxiosResponse
  return new AxiosError('fail', String(status), config, null, response)
}

describe('httpError', () => {
  it('HTTP 404 응답만 리소스 없음으로 본다', () => {
    expect(isNotFoundError(httpError(404))).toBe(true)
    expect(isNotFoundError(httpError(500))).toBe(false)
    expect(isNotFoundError(new Error('Network Error'))).toBe(false)
    expect(isNotFoundError(undefined)).toBe(false)
  })

  it('404는 재시도하지 않고 다른 오류는 1회만 재시도한다', () => {
    expect(retryUnlessNotFound(0, httpError(404))).toBe(false)
    expect(retryUnlessNotFound(0, httpError(500))).toBe(true)
    expect(retryUnlessNotFound(1, httpError(500))).toBe(false)
  })
})
