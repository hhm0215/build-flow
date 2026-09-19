import { describe, expect, it, vi } from 'vitest'
import { enableMocking, isMockMode, isMockModeEnabled } from './mockMode'

declare const process: { env: { MSW_DISABLED?: string } }

describe('MSW 활성화 조건', () => {
  it('개발 모드에서 MSW_DISABLED가 설정되지 않았을 때만 모의 API를 사용한다', () => {
    expect(isMockModeEnabled(true, true)).toBe(true)
    expect(isMockModeEnabled(true, false)).toBe(false)
    expect(isMockModeEnabled(false, true)).toBe(false)
    expect(isMockMode).toBe(isMockModeEnabled(import.meta.env.DEV, process.env.MSW_DISABLED !== 'true'))
  })

  it('실제 API 모드에서는 worker 모듈을 불러오지도 시작하지도 않는다', async () => {
    const start = vi.fn().mockResolvedValue(undefined)
    const loadWorker = vi.fn().mockResolvedValue({ worker: { start } })

    await enableMocking(false, loadWorker)

    expect(loadWorker).not.toHaveBeenCalled()
    expect(start).not.toHaveBeenCalled()
  })

  it('모의 API 모드에서는 worker를 시작한다', async () => {
    const start = vi.fn().mockResolvedValue(undefined)
    const loadWorker = vi.fn().mockResolvedValue({ worker: { start } })

    await enableMocking(true, loadWorker)

    expect(loadWorker).toHaveBeenCalledOnce()
    expect(start).toHaveBeenCalledWith({ onUnhandledRequest: 'bypass' })
  })
})
