import { useSyncExternalStore } from 'react'

// 레이아웃 분기 기준. index.css의 @media 값과 반드시 같게 유지한다.
export const MOBILE_MAX_WIDTH = 767
export const COMPACT_MAX_WIDTH = 1023

export const MOBILE_QUERY = `(max-width: ${MOBILE_MAX_WIDTH}px)`
export const COMPACT_QUERY = `(max-width: ${COMPACT_MAX_WIDTH}px)`

function getMediaQueryList(query: string): MediaQueryList | null {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return null
  return window.matchMedia(query)
}

/**
 * CSS 미디어 쿼리 일치 여부를 구독한다.
 * matchMedia가 없는 환경(일부 테스트)에서는 데스크톱(false)으로 간주한다.
 */
export function useMediaQuery(query: string): boolean {
  return useSyncExternalStore(
    (onChange) => {
      const mql = getMediaQueryList(query)
      if (!mql) return () => {}
      if (typeof mql.addEventListener === 'function') {
        mql.addEventListener('change', onChange)
        return () => mql.removeEventListener('change', onChange)
      }
      // 구형 Safari 호환
      mql.addListener?.(onChange)
      return () => mql.removeListener?.(onChange)
    },
    () => getMediaQueryList(query)?.matches ?? false,
    () => false,
  )
}

export interface Viewport {
  /** 너비 < 768px — 휴대폰 */
  isMobile: boolean
  /** 너비 < 1024px — 휴대폰 + 태블릿. 사이드바를 Drawer로 접는 기준 */
  isCompact: boolean
}

export function useViewport(): Viewport {
  const isMobile = useMediaQuery(MOBILE_QUERY)
  const isCompact = useMediaQuery(COMPACT_QUERY)
  return { isMobile, isCompact: isCompact || isMobile }
}
