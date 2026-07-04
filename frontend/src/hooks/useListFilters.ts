import { useCallback, useMemo, useRef } from 'react'
import { FilterSchema, useFilterParams } from './useFilterParams'
import { useDebouncedValue } from './useDebouncedValue'

/**
 * ListPage 공통 필터 scaffolding 통합 훅.
 *
 * 5개 ListPage(estimate/purchase/tax/warranty/site)가 반복하던
 * `useFilterParams + useDebouncedValue + filtered useMemo + activeCount + resetFilters`
 * 배선을 흡수한다. 페이지마다 실제 다른 부분(검색 대상 필드·필터 술어)은
 * `filterFn` 콜백으로 명시적으로 남긴다.
 *
 * - `resetFilters`: schema 타입에서 자동 도출 (string/date→'', number/enum→undefined)
 * - `activeCount`: `groups`로 범위쌍을 1그룹 처리, 그 외 키는 각각 1그룹 자동 처리
 * - `filtered`: filterFn을 ref로 보관해 인라인 콜백도 memo 무효화 없이 사용
 *
 * 타입 파라미터: `useListFilters<Item, Filters>({ ... })`
 */
interface UseListFiltersOptions<T, F extends object> {
  schema: FilterSchema
  items: T[]
  /** 페이지별 필터 술어. 검색어는 세 번째 인자로 주입된다(디바운스 적용, 원본 문자열). */
  filterFn: (item: T, filters: Partial<F>, q: string) => boolean
  /** 디바운스 대상 검색 필드 (기본 'q') */
  searchKey?: keyof F & string
  /** 디바운스 지연(ms, 기본 250) */
  debounceMs?: number
  /**
   * activeCount 그룹핑. 함께 1개로 세야 하는 키들(예: 범위쌍)만 선언한다.
   * 여기에 없는 schema 키는 각각 단독 그룹으로 자동 계산된다.
   */
  groups?: (keyof F & string)[][]
}

interface UseListFiltersResult<T, F extends object> {
  filters: Partial<F>
  setFilters: (next: Partial<F>) => void
  filtered: T[]
  activeCount: number
  resetFilters: () => void
}

function isActive(filters: Record<string, unknown>, key: string): boolean {
  const v = filters[key]
  return v !== undefined && v !== null && v !== ''
}

export function useListFilters<T, F extends object>({
  schema,
  items,
  filterFn,
  searchKey = 'q' as keyof F & string,
  debounceMs = 250,
  groups,
}: UseListFiltersOptions<T, F>): UseListFiltersResult<T, F> {
  const [filters, setFilters] = useFilterParams<F>(schema)

  const rawQ = (filters as Record<string, unknown>)[searchKey]
  const debouncedQ = useDebouncedValue(
    typeof rawQ === 'string' ? rawQ : '',
    debounceMs,
  )

  // filterFn·filters를 ref로 보관 → 인라인 콜백이 매 렌더 바뀌어도, 검색어(searchKey)
  // 원본값이 바뀌어도 filtered memo는 재계산되지 않는다. 검색은 debouncedQ로만 반영.
  const filterFnRef = useRef(filterFn)
  filterFnRef.current = filterFn
  const filtersRef = useRef(filters)
  filtersRef.current = filters

  // 검색 필드를 제외한 필터 값들의 시그니처. 이 값이 바뀔 때만(그리고 debouncedQ가
  // 바뀔 때만) filtered를 재계산 → 검색어 타이핑 중 불필요한 재필터를 막는다(디바운스 보존).
  const filterSig = useMemo(() => {
    const f = filters as Record<string, unknown>
    return Object.keys(schema)
      .filter((k) => k !== searchKey)
      .map((k) => `${k}:${f[k] ?? ''}`)
      .join('|')
  }, [schema, filters, searchKey])

  const filtered = useMemo(
    () => items.filter((item) => filterFnRef.current(item, filtersRef.current, debouncedQ)),
    // filterSig(검색 제외 필터값 시그니처)를 트리거로만 사용 — 콜백은 refs로 최신값을 읽는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [items, debouncedQ, filterSig],
  )

  const resetFilters = useCallback(() => {
    const empty: Record<string, unknown> = {}
    for (const key of Object.keys(schema)) {
      const t = schema[key].type
      empty[key] = t === 'string' || t === 'date' ? '' : undefined
    }
    setFilters(empty as Partial<F>)
  }, [schema, setFilters])

  const activeCount = useMemo(() => {
    const grouped = new Set<string>()
    const resolved: string[][] = []
    for (const g of groups ?? []) {
      resolved.push(g)
      g.forEach((k) => grouped.add(k))
    }
    for (const key of Object.keys(schema)) {
      if (!grouped.has(key)) resolved.push([key])
    }
    const f = filters as Record<string, unknown>
    return resolved.reduce(
      (acc, group) => acc + (group.some((k) => isActive(f, k)) ? 1 : 0),
      0,
    )
  }, [groups, schema, filters])

  return { filters, setFilters, filtered, activeCount, resetFilters }
}
