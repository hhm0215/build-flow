import { describe, expect, it } from 'vitest'
import { buildSiteDisplayMap } from './siteName'

describe('buildSiteDisplayMap', () => {
  it('현장명과 보관 여부를 매핑하고 모르는 ID는 #ID로 둔다', () => {
    const display = buildSiteDisplayMap([
      { id: 1, siteName: '강남 리모델링', archivedAt: null },
      { id: 2, siteName: '옛 현장', archivedAt: '2026-10-01T00:00:00' },
    ])
    expect(display(1)).toEqual({ label: '강남 리모델링', archived: false })
    expect(display(2)).toEqual({ label: '옛 현장', archived: true })
    expect(display(9)).toEqual({ label: '#9', archived: false })
  })

  it('현장 목록을 아직 못 불러왔으면 #ID로 둔다', () => {
    expect(buildSiteDisplayMap(undefined)(3)).toEqual({ label: '#3', archived: false })
  })
})
