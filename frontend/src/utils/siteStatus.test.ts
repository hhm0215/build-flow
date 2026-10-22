import { describe, expect, it } from 'vitest'
import { canTransitionSiteStatus, INITIAL_SITE_STATUSES, SITE_STATUSES, siteCountCaption } from './siteStatus'

describe('canTransitionSiteStatus', () => {
  it('수주 전은 시공 중으로만 바꿀 수 있다', () => {
    expect(canTransitionSiteStatus('BIDDING', 'IN_PROGRESS')).toBe(true)
    expect(canTransitionSiteStatus('BIDDING', 'BIDDING')).toBe(true)
    for (const next of ['SETTLING', 'WARRANTY', 'COMPLETED'] as const) {
      expect(canTransitionSiteStatus('BIDDING', next)).toBe(false)
    }
  })

  it('수주 이후 상태는 수주 전으로 돌아가지 않고 서로는 자유롭게 이동한다', () => {
    for (const from of ['IN_PROGRESS', 'SETTLING', 'WARRANTY', 'COMPLETED'] as const) {
      expect(canTransitionSiteStatus(from, 'BIDDING')).toBe(false)
    }
    expect(canTransitionSiteStatus('COMPLETED', 'IN_PROGRESS')).toBe(true)
    expect(canTransitionSiteStatus('IN_PROGRESS', 'WARRANTY')).toBe(true)
  })

  it('시작 상태는 시공 중(기본)과 수주 전뿐이다', () => {
    expect(INITIAL_SITE_STATUSES).toEqual(['IN_PROGRESS', 'BIDDING'])
    expect(SITE_STATUSES[0]).toBe('BIDDING')
  })
})

describe('siteCountCaption', () => {
  it('수주 전 현장이 있을 때만 건수를 덧붙인다', () => {
    expect(siteCountCaption({ IN_PROGRESS: 2 }, 2)).toBe('전체 2개 현장')
    expect(siteCountCaption({ IN_PROGRESS: 2, BIDDING: 1 }, 3)).toBe('전체 3개 현장 · 수주 전 1건')
  })
})
