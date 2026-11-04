import { describe, expect, it, vi } from 'vitest'
import {
  CHUNK_RELOAD_KEY,
  CHUNK_RELOAD_WINDOW_MS,
  claimChunkReload,
  isChunkLoadError,
  reloadOnceForChunkError,
} from './chunkReload'

function memoryStorage(initial: Record<string, string> = {}) {
  const data = new Map(Object.entries(initial))
  return {
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => void data.set(key, value),
    data,
  }
}

describe('isChunkLoadError', () => {
  it.each([
    'Failed to fetch dynamically imported module: https://x/assets/DashboardPage-abc.js',
    'Importing a module script failed.',
    'error loading dynamically imported module: /assets/a.js',
    'Unable to preload CSS for /assets/a.css',
    "Failed to load module script: Expected a JavaScript module script but the server responded with a MIME type of \"text/html\". Strict MIME type checking is enforced for module scripts per HTML spec. 'text/html' is not a valid JavaScript MIME type.",
  ])('청크 로드 실패 메시지를 인식한다: %s', (message) => {
    expect(isChunkLoadError(new TypeError(message))).toBe(true)
  })

  it('일반 오류·비오류 값은 청크 오류가 아니다', () => {
    expect(isChunkLoadError(new Error('Cannot read properties of undefined'))).toBe(false)
    expect(isChunkLoadError(null)).toBe(false)
    expect(isChunkLoadError('Failed to fetch dynamically imported module')).toBe(false)
  })

  it('name이 ChunkLoadError면 청크 오류다', () => {
    const error = new Error('x')
    error.name = 'ChunkLoadError'
    expect(isChunkLoadError(error)).toBe(true)
  })
})

describe('claimChunkReload', () => {
  it('기록이 없으면 허용하고 시각을 남긴다', () => {
    const storage = memoryStorage()
    expect(claimChunkReload(storage, 1_000)).toBe(true)
    expect(storage.data.get(CHUNK_RELOAD_KEY)).toBe('1000')
  })

  it('창 안에서 두 번째 시도는 막아 무한 새로고침을 방지한다', () => {
    const storage = memoryStorage()
    expect(claimChunkReload(storage, 1_000)).toBe(true)
    expect(claimChunkReload(storage, 1_000 + CHUNK_RELOAD_WINDOW_MS - 1)).toBe(false)
  })

  it('창이 지나면(다음 배포) 다시 한 번 허용한다', () => {
    const storage = memoryStorage({ [CHUNK_RELOAD_KEY]: '1000' })
    expect(claimChunkReload(storage, 1_000 + CHUNK_RELOAD_WINDOW_MS)).toBe(true)
  })

  it('저장소가 없거나 예외를 던지면 새로고침하지 않는다', () => {
    expect(claimChunkReload(null, 1_000)).toBe(false)
    const broken = {
      getItem: () => { throw new Error('denied') },
      setItem: () => { throw new Error('denied') },
    }
    expect(claimChunkReload(broken, 1_000)).toBe(false)
  })

  it('손상된 기록 값은 무시하고 허용한다', () => {
    expect(claimChunkReload(memoryStorage({ [CHUNK_RELOAD_KEY]: 'abc' }), 1_000)).toBe(true)
  })
})

describe('reloadOnceForChunkError', () => {
  it('같은 세션에서 연속 호출해도 reload는 한 번만 실행된다', () => {
    const storage = memoryStorage()
    const reload = vi.fn()
    expect(reloadOnceForChunkError(reload, storage, 5_000)).toBe(true)
    expect(reloadOnceForChunkError(reload, storage, 6_000)).toBe(false)
    expect(reload).toHaveBeenCalledTimes(1)
  })
})
