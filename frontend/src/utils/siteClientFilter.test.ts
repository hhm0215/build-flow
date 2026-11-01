import { describe, expect, it } from 'vitest'
import type { Client, Site } from '../types'
import { buildClientFilterOptions, matchesClientFilter, sitesOfClientPath } from './siteClientFilter'

const client = (id: number, companyName: string, archivedAt: string | null = null): Client => ({
  id, companyName, representative: null, businessNo: null, phone: null, email: null,
  address: null, memo: null, archivedAt,
  createdAt: '2026-10-01T00:00:00', updatedAt: '2026-10-01T00:00:00',
})

const site = (id: number, c: Client | null): Site => ({
  id, siteName: `현장 ${id}`, client: c, address: null, status: 'IN_PROGRESS',
  startDate: null, endDate: null, memo: null, archivedAt: null,
  createdAt: '2026-10-01T00:00:00', updatedAt: '2026-10-01T00:00:00',
})

describe('buildClientFilterOptions', () => {
  it('보관 거래처는 뒤로 보내고 표시하며 같은 그룹은 이름순', () => {
    const options = buildClientFilterOptions(
      [client(1, '하나건설'), client(2, '가람상사', '2026-10-20T00:00:00'), client(3, '나래종합')],
      [],
    )
    expect(options).toEqual([
      { value: 3, label: '나래종합', archived: false },
      { value: 1, label: '하나건설', archived: false },
      { value: 2, label: '가람상사', archived: true },
    ])
  })

  it('거래처 목록이 없으면 현장에 포함된 거래처로 채우고 중복은 하나로', () => {
    const a = client(1, '하나건설')
    const options = buildClientFilterOptions(undefined, [site(10, a), site(11, a), site(12, null)])
    expect(options).toEqual([{ value: 1, label: '하나건설', archived: false }])
  })

  it('URL의 거래처 ID가 어디에도 없으면 별도 선택지를 만든다', () => {
    const options = buildClientFilterOptions([client(1, '하나건설')], [], 99)
    expect(options.map((o) => o.value)).toEqual([1, 99])
    expect(options[1].label).toBe('거래처 #99 (목록에 없음)')
  })
})

describe('matchesClientFilter', () => {
  it('미지정이면 모두 통과, 지정이면 그 거래처 현장만 통과', () => {
    const a = client(1, '하나건설')
    expect(matchesClientFilter(site(1, null), undefined)).toBe(true)
    expect(matchesClientFilter(site(1, a), 1)).toBe(true)
    expect(matchesClientFilter(site(1, a), 2)).toBe(false)
    expect(matchesClientFilter(site(1, null), 1)).toBe(false)
  })
})

describe('sitesOfClientPath', () => {
  it('보관 현장이 있으면 보관 포함으로 연다', () => {
    expect(sitesOfClientPath(5)).toBe('/sites?clientId=5')
    expect(sitesOfClientPath(5, 0)).toBe('/sites?clientId=5')
    expect(sitesOfClientPath(5, 2)).toBe('/sites?clientId=5&archive=ALL')
  })
})
