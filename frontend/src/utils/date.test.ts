import { describe, expect, it } from 'vitest'
import { todayKst } from './date'

describe('todayKst', () => {
  it('UTC 자정 전후에도 서울 날짜를 돌려준다', () => {
    expect(todayKst(new Date('2026-10-31T14:59:59Z'))).toBe('2026-10-31')
    expect(todayKst(new Date('2026-10-31T15:00:00Z'))).toBe('2026-11-01')
  })
})
