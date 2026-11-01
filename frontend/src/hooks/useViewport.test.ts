import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { COMPACT_QUERY, MOBILE_QUERY, useViewport } from './useViewport'

type Listener = () => void

/** width 기준으로 max-width 쿼리를 판정하는 matchMedia 대역. resize(w)로 change 이벤트를 발생시킨다. */
function installMatchMedia(initialWidth: number) {
  let width = initialWidth
  const listeners = new Map<string, Set<Listener>>()
  const evaluate = (query: string) => {
    const m = /max-width:\s*(\d+)px/.exec(query)
    return m ? width <= Number(m[1]) : false
  }
  vi.stubGlobal('matchMedia', (query: string) => ({
    get matches() { return evaluate(query) },
    media: query,
    addEventListener: (_: string, cb: Listener) => {
      if (!listeners.has(query)) listeners.set(query, new Set())
      listeners.get(query)!.add(cb)
    },
    removeEventListener: (_: string, cb: Listener) => listeners.get(query)?.delete(cb),
    addListener: vi.fn(),
    removeListener: vi.fn(),
  }))
  return {
    resize(next: number) {
      width = next
      listeners.forEach((set) => set.forEach((cb) => cb()))
    },
    listenerCount: () => [...listeners.values()].reduce((n, s) => n + s.size, 0),
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('useViewport', () => {
  it('쿼리 기준값은 모바일 767px · 컴팩트 1023px이다', () => {
    expect(MOBILE_QUERY).toBe('(max-width: 767px)')
    expect(COMPACT_QUERY).toBe('(max-width: 1023px)')
  })

  it.each([
    [375, { isMobile: true, isCompact: true }],
    [767, { isMobile: true, isCompact: true }],
    [768, { isMobile: false, isCompact: true }],
    [1023, { isMobile: false, isCompact: true }],
    [1024, { isMobile: false, isCompact: false }],
    [1440, { isMobile: false, isCompact: false }],
  ])('너비 %ipx → %o', (width, expected) => {
    installMatchMedia(width)
    const { result } = renderHook(() => useViewport())
    expect(result.current).toEqual(expected)
  })

  it('화면 크기가 바뀌면 다시 계산하고 언마운트 시 구독을 해제한다', () => {
    const mm = installMatchMedia(1440)
    const { result, unmount } = renderHook(() => useViewport())
    expect(result.current.isCompact).toBe(false)

    act(() => mm.resize(375))
    expect(result.current).toEqual({ isMobile: true, isCompact: true })

    act(() => mm.resize(900))
    expect(result.current).toEqual({ isMobile: false, isCompact: true })

    unmount()
    expect(mm.listenerCount()).toBe(0)
  })

  it('matchMedia가 없는 환경은 데스크톱으로 간주한다', () => {
    vi.stubGlobal('matchMedia', undefined)
    const { result } = renderHook(() => useViewport())
    expect(result.current).toEqual({ isMobile: false, isCompact: false })
  })
})
