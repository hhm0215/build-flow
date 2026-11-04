/**
 * 새 버전 배포 뒤 이미 열려 있던 페이지가 옛 해시의 lazy 청크를 요청해 실패하는 경우를 다룬다.
 * 전체 새로고침으로 새 index.html·청크를 받게 하되, 새로고침해도 같은 오류가 반복되면
 * 무한 새로고침이 되지 않도록 sessionStorage에 마지막 시도 시각을 남겨 한 번만 시도한다.
 */

export const CHUNK_RELOAD_KEY = 'buildflow:chunk-reload-at'
/** 이 시간 안에 다시 청크 오류가 나면 새로고침으로 해결되지 않은 것으로 보고 멈춘다 */
export const CHUNK_RELOAD_WINDOW_MS = 60_000

const CHUNK_ERROR_PATTERNS = [
  /Failed to fetch dynamically imported module/i, // Chromium
  /Importing a module script failed/i, // Safari
  /error loading dynamically imported module/i, // Firefox
  /Unable to preload CSS/i, // Vite CSS preload
  /is not a valid JavaScript MIME type/i, // SPA fallback가 HTML을 돌려준 경우
]

export function isChunkLoadError(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false
  const { name, message } = error as { name?: unknown; message?: unknown }
  if (name === 'ChunkLoadError') return true
  if (typeof message !== 'string') return false
  return CHUNK_ERROR_PATTERNS.some((pattern) => pattern.test(message))
}

type ReloadStorage = Pick<Storage, 'getItem' | 'setItem'>

/**
 * 새로고침을 시도해도 되면 시각을 기록하고 true를 돌려준다.
 * 저장소 접근이 막혀 기록할 수 없으면 반복 방지를 보장할 수 없으므로 false.
 */
export function claimChunkReload(storage: ReloadStorage | null | undefined, now: number): boolean {
  if (!storage) return false
  try {
    const last = Number(storage.getItem(CHUNK_RELOAD_KEY))
    if (Number.isFinite(last) && last > 0 && now - last >= 0 && now - last < CHUNK_RELOAD_WINDOW_MS) {
      return false
    }
    storage.setItem(CHUNK_RELOAD_KEY, String(now))
    return true
  } catch {
    return false
  }
}

function sessionStorageOrNull(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.sessionStorage
  } catch {
    return null
  }
}

/** 한 번만 전체 새로고침한다. 새로고침했으면 true. */
export function reloadOnceForChunkError(
  reload: () => void = () => window.location.reload(),
  storage: ReloadStorage | null = sessionStorageOrNull(),
  now: number = Date.now(),
): boolean {
  if (!claimChunkReload(storage, now)) return false
  reload()
  return true
}

/** Vite가 동적 import 선로딩 실패 시 보내는 이벤트를 받아 한 번만 새로고침한다. */
export function installChunkReloadHandler(target: Window = window): void {
  target.addEventListener('vite:preloadError', (event) => {
    if (reloadOnceForChunkError()) {
      // 새로고침으로 처리했으므로 Vite가 오류를 다시 던지지 않게 한다.
      event.preventDefault()
    }
    // 새로고침하지 못하면 오류가 그대로 전파되어 ErrorBoundary 안내가 보인다.
  })
}
