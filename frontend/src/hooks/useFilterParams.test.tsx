import { describe, it, expect } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import type { ReactNode } from 'react'
import { useFilterParams } from './useFilterParams'

const SCHEMA = {
  q: { type: 'string' },
  status: { type: 'enum', values: ['IN_PROGRESS', 'COMPLETED'] },
  min: { type: 'number' },
  from: { type: 'date' },
  clientId: { type: 'id' },
} as const

function setup(initialUrl: string) {
  const wrapper = ({ children }: { children: ReactNode }) => (
    <MemoryRouter initialEntries={[initialUrl]}>{children}</MemoryRouter>
  )
  return renderHook(
    () => {
      const [filters, setFilters] = useFilterParams(SCHEMA)
      return { filters, setFilters, search: useLocation().search }
    },
    { wrapper },
  )
}

describe('useFilterParams', () => {
  it('스키마 타입대로 URL 값을 읽는다', () => {
    const { result } = setup('/sites?q=QA&status=COMPLETED&min=1500&from=2026-10-22')

    expect(result.current.filters).toEqual({ q: 'QA', status: 'COMPLETED', min: 1500, from: '2026-10-22' })
  })

  it('형식이 틀린 값은 버리고 URL에서도 지우되 스키마 밖 파라미터는 남긴다', () => {
    const { result } = setup('/sites?status=DELETED&min=abc&from=2026-1-2&q=ok&page=3')

    expect(result.current.filters).toEqual({ q: 'ok' })
    const params = new URLSearchParams(result.current.search)
    expect(params.get('status')).toBeNull()
    expect(params.get('min')).toBeNull()
    expect(params.get('from')).toBeNull()
    expect(params.get('q')).toBe('ok')
    expect(params.get('page')).toBe('3')
  })

  it('id 필드는 양의 정수만 받고 나머지는 URL에서 지운다', () => {
    expect(setup('/sites?clientId=42').result.current.filters).toEqual({ clientId: 42 })

    for (const bad of ['0', '-3', '1.5', '1e3', '007', 'abc', '99999999999999999999']) {
      const { result } = setup(`/sites?clientId=${bad}`)
      expect(result.current.filters).toEqual({})
      expect(new URLSearchParams(result.current.search).get('clientId')).toBeNull()
    }
  })

  it('빈 문자열 값은 필터로 보지 않는다', () => {
    const { result } = setup('/sites?q=&status=IN_PROGRESS')

    expect(result.current.filters).toEqual({ status: 'IN_PROGRESS' })
  })

  it('setFilters는 값을 쓰고 비운 값은 URL에서 지운다', () => {
    const { result } = setup('/sites?q=old&page=2')

    act(() => result.current.setFilters({ q: '새 현장', min: 0 }))
    expect(result.current.filters).toEqual({ q: '새 현장', min: 0 })
    expect(new URLSearchParams(result.current.search).get('page')).toBe('2')

    act(() => result.current.setFilters({ q: '', min: undefined }))
    expect(result.current.filters).toEqual({})
    expect(new URLSearchParams(result.current.search).toString()).toBe('page=2')
  })
})
