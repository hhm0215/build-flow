import { describe, it, expect } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import type { ReactNode } from 'react'
import { useListFilters } from './useListFilters'
import { FilterSchema } from './useFilterParams'

const SCHEMA: FilterSchema = {
  q: { type: 'string' },
  status: { type: 'enum', values: ['A', 'B'] },
  minAmount: { type: 'number' },
  maxAmount: { type: 'number' },
}

interface Filters {
  q: string
  status: string
  minAmount: number
  maxAmount: number
}

interface Item {
  name: string
  status: string
  amount: number
}

const ITEMS: Item[] = [
  { name: 'alpha', status: 'A', amount: 100 },
  { name: 'beta', status: 'B', amount: 300 },
]

function wrapper({ children }: { children: ReactNode }) {
  return <MemoryRouter>{children}</MemoryRouter>
}

function setup(groups?: (keyof Filters & string)[][]) {
  return renderHook(
    () =>
      useListFilters<Item, Filters>({
        schema: SCHEMA,
        items: ITEMS,
        groups,
        filterFn: (item, f) => !f.status || item.status === f.status,
      }),
    { wrapper },
  )
}

describe('useListFilters', () => {
  it('필터 미적용 시 전체 반환, activeCount 0', () => {
    const { result } = setup()
    expect(result.current.filtered).toHaveLength(2)
    expect(result.current.activeCount).toBe(0)
  })

  it('filterFn으로 걸러지고 단일 필터는 activeCount 1', () => {
    const { result } = setup()
    act(() => result.current.setFilters({ status: 'A' }))
    expect(result.current.filtered.map((i) => i.name)).toEqual(['alpha'])
    expect(result.current.activeCount).toBe(1)
  })

  it('범위쌍 groups는 한 그룹으로 카운트', () => {
    const { result } = setup([['minAmount', 'maxAmount']])
    act(() => result.current.setFilters({ status: 'A', minAmount: 50 }))
    // status(1) + minAmount/maxAmount 범위그룹(1) = 2
    expect(result.current.activeCount).toBe(2)
  })

  it('resetFilters가 모든 필터를 비운다', () => {
    const { result } = setup()
    act(() => result.current.setFilters({ status: 'A' }))
    expect(result.current.activeCount).toBe(1)
    act(() => result.current.resetFilters())
    expect(result.current.activeCount).toBe(0)
    expect(result.current.filtered).toHaveLength(2)
  })
})
