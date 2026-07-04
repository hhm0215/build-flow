# useListFilters 훅 추상화

- **시작일**: 2026-07-04
- **BACKLOG 항목**: P1 "useListFilters 추상화 (별도 PR — 큰 결정)"
- **예상 규모**: M
- **상태**: DONE

## 목표

5개 ListPage(estimate/purchase/tax/warranty/site)가 복붙하는 필터 scaffolding을
`useListFilters` 훅 하나로 통합한다. 각 페이지의 **의미 있는 차이(필터 술어)**는 명시적으로 남기고,
**기계적 배선(schema 연결·debounce·memo·activeCount·resetFilters)**만 흡수한다.
5페이지 lint/build 통과 + 필터 동작(검색/enum/날짜범위/금액범위/reset/activeCount) 회귀 없음 = 완료.

## 배경 / 동기

실측(2026-07-04) 결과, 5페이지가 아래 패턴을 동일하게 반복:

```ts
const [filters, setFilters] = useFilterParams<T>(FILTER_SCHEMA)
const debouncedQ = useDebouncedValue(filters.q ?? '', 250)
const filtered = useMemo(() => items.filter(it => { ...술어... }), [items, debouncedQ, ...필터필드])
const activeCount = (filters.q?1:0) + (filters.status?1:0) + (범위쌍?1:0) + ...
const resetFilters = () => setFilters({ q:'', status:undefined, ... })
```

**페이지별로 실제 다른 부분은 단 2가지**:
1. **검색 대상 필드**: est.title / purchase 품목 / inv.counterparty / `${insuranceCompany} ${policyNumber}` / site명
2. **필터 술어**: status·type·payment·날짜필드명(startDate/endDate vs expiryFrom/expiryTo)·금액범위 조합

나머지(activeCount 그룹핑, resetFilters 초기값)는 **schema에서 도출 가능**한데 지금은 손으로 반복 → drift·실수 여지.

## 접근법

### 채택: Approach A — 콜백 기반 (scaffolding만 흡수, 술어는 명시 유지)

```ts
// hooks/useListFilters.ts
export function useListFilters<const S extends FilterSchema, T>(opts: {
  schema: S
  items: T[]
  filterFn: (item: T, filters: InferFilters<S>, q: string) => boolean
  searchKey?: keyof S        // 기본 'q' — 디바운스 대상
  debounceMs?: number        // 기본 250
  groups?: (keyof S | (keyof S)[])[]  // activeCount 그룹핑 (기본: 키 각각 1그룹)
}): {
  filters: InferFilters<S>
  setFilters: (next: Partial<InferFilters<S>>) => void
  filtered: T[]
  activeCount: number
  resetFilters: () => void
}
```

핵심 결정:
- **filterFn은 콜백으로 유지** — 검색/술어는 페이지마다 진짜 다르므로 명시적으로 남긴다(가독성 보존).
- **filterFn 안정성**: `useRef`로 최신 filterFn 보관 → `filtered` memo는 `[items, filters, debouncedQ]`에만 의존.
  인라인 filterFn을 매 렌더 새로 넘겨도 memo 무효화 안 됨(페이지가 useCallback 강제당하지 않음).
- **resetFilters 자동 도출**: schema 타입별 초기값(string/date→`''`, number/enum→`undefined`)을 순회 생성. 페이지가 손으로 안 씀.
- **activeCount 자동 도출**: `groups`로 범위쌍(`['startDate','endDate']`)을 1그룹 처리. 미지정 시 키 각각 1.

### 반려: Approach B — 완전 선언형 schema

schema에 연산자를 박아(`{type:'range', field:'totalAmount'}`) 술어까지 자동 생성.
→ **반려 사유**: tax의 `payment==='PAID' && !paymentConfirmed`, warranty의 `status==='VALID' && !w.expired`,
검색이 조인 필드(`insuranceCompany + policyNumber`)를 훑는 케이스가 선언형에 안 맞음.
억지로 밀면 schema에 특수 케이스 분기가 쌓여 **오히려 가독성 ↓** — BACKLOG가 경고한 바로 그 리스크.

### site 페이지 주의

SiteListPage는 목록 필터(q, status)는 동일 패턴이지만, 상세 패널에서 estimate/purchase/tax를
siteId로 재필터링하는 별도 로직 존재 → **그건 건드리지 않는다**. 목록 필터 scaffolding만 교체.

## 산출물 체크리스트

- [ ] `frontend/src/hooks/useListFilters.ts` 신규 (useFilterParams/useDebouncedValue 내부 사용)
- [ ] EstimateListPage 전환 (status + 날짜범위 + 금액범위)
- [ ] PurchaseListPage 전환 (날짜범위 + 금액범위, status 없음)
- [ ] TaxListPage 전환 (type + payment + 날짜범위 + 금액범위, unpaidTotal은 filtered에서 계속 파생)
- [ ] WarrantyListPage 전환 (status + expiryFrom/expiryTo — 날짜필드명 다름)
- [ ] SiteListPage 전환 (q + status만, 상세 패널 로직 불변)
- [ ] `bun run lint` (max-warnings 0) + `bun run build` 통과

## 리스크 / 모르는 것

- **memo 의존성 회귀**: 기존엔 `filters.status` 등 개별 필드를 dep에 나열. 훅에선 `filters` 객체 통째 의존.
  useFilterParams가 filters를 useMemo로 안정화하므로 searchParams 불변 시 재계산 없음 — 동작 동일해야 함. 전환 후 각 페이지 수동 확인.
- **activeCount 그룹핑 표현력**: 범위쌍만 있으면 `groups`로 충분. 더 복잡한 그룹이 나오면 그때 확장.
- **filterFn 시그니처에 q 주입**: 검색을 filterFn 안에 두는 현행 방식 유지(별도 searchFn 분리 안 함) — 페이지 코드 최소 변경.

## 테스트 / 검증

- `bun run lint` + `bun run build` 통과 (백엔드 무관, 프론트 전용)
- 5페이지 각각: 검색어 입력→디바운스 반영, enum 필터, 날짜/금액 범위, reset 버튼, activeCount 뱃지 수치
  — 기존과 동일하게 동작하는지 수기 확인 (자동 리뷰 5.5단계 병행)

## 결과 (작업 후 기록)

- **만든 것**: `useListFilters<Item, Filters>` 훅 신규. Approach A(콜백 기반) 그대로 구현.
  filterFn의 두 번째 인자에 `Partial<XxxFilters>` 주석만 달아 아이템/필터 타입을 추론 → 아이템 타입 import 불필요.
- **전환 완료**: estimate/purchase/tax/warranty/site 5페이지 전부. 페이지당 필터 배선 ~15줄 → 훅 호출 1개.
  - resetFilters·activeCount는 schema/`groups`에서 자동 도출 → 페이지에서 제거.
  - site는 activeCount/resetFilters 미사용, `filtered: filteredSites`로 별칭만 받음(상세 패널 siteId 로직 불변).
- **5.5 리뷰 자가 발견 + 즉시 fix**: 초기 구현에서 `filtered` memo가 `filters` 객체 전체에 의존 →
  검색어 타이핑마다 재필터되어 **디바운스 최적화가 무력화되는 회귀**. `filterSig`(검색 제외 필터값 시그니처)
  + `filtersRef` 패턴으로 "debouncedQ 또는 비검색 필터 변경 시에만 재계산"으로 교정. 결과 정확성은 원래도 동일했고
  성능 회귀만 제거. (능동 발의 실험: 리팩터링 발의 트리거 대신 in-cycle fix로 처리)
- **검증**: `bun run lint` 0경고 + `tsc && vite build` 통과.
- **미룬 것**: 없음. 런타임 수기 QA(5페이지 필터 동작)는 Docker/dev 서버 필요 → 사용자 확인 권장.
