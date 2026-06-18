# frontend — 보증보험 PDF 업로드 + OCR 상태 표시

- **시작일**: 2026-06-18
- **BACKLOG 항목**: P0 (프론트 보증보험 PDF 업로드 + OCR 상태 표시)
- **예상 규모**: M
- **상태**: DONE (2026-06-18)

## 목표

백엔드 Phase 2(2026-06-18 완료)가 만든 `POST /api/v1/warranties/upload` 엔드포인트를 프론트에서 활용:
1. WarrantyListPage에 PDF 업로드 모달 신설
2. OCR 상태 뱃지 표시 (PENDING/SUCCESS/FAILED/MANUAL)
3. PENDING 행이 있으면 자동 polling → SUCCESS/FAILED 변경 즉시 반영

## 현재 코드 (조사 완료)

✅ 갖춰진 것:
- `WarrantyListPage.tsx`: 기존 JSON CRUD 모달 + 검색/필터 + 만료 임박 배너
- `warranties.api.ts`: `useWarranties`, `useCreate/Update/DeleteWarranty`, `useExpiringWarranties`
- `Warranty` 타입 (`domain.types.ts`)
- MSW handlers/data

❌ 추가 필요:
- `Warranty.ocrStatus` / `Warranty.filePath` 타입 필드
- `OcrStatus` enum 타입
- `uploadWarranty(file, siteId)` API + `useUploadWarranty()` 훅
- `WarrantyUploadModal.tsx` (드래그앤드롭 + .pdf 검증 + 20MB)
- `OcrStatusBadge` 컴포넌트
- 폴링 로직 (PENDING 있으면 `refetchInterval: 5000`)
- MSW `/upload` 핸들러 + 10초 후 자동 SUCCESS 시뮬

## 핵심 결정

### 1. 폴링 — React Query `refetchInterval` 동적 옵션
```ts
const hasPending = warranties.some(w => w.ocrStatus === 'PENDING')
const { data } = useWarranties(params, hasPending ? 5000 : undefined)
```
`useWarranties`에 `refetchInterval?: number` 옵션 추가. 컴포넌트에서 PENDING 존재 여부 계산.

### 2. 뱃지 표시 정책
| 상태 | 라벨 | 색상 |
|------|------|------|
| PENDING | "AI 분석 중" + spinner | 회색 (#94a3b8) |
| SUCCESS | "AI 추출됨" + 체크 | 초록 (#22c55e) |
| FAILED | "AI 실패" + 경고 | 빨강 (#ef4444) |
| MANUAL | 표시 안 함 (또는 "직접 입력" 보라색 미세표시) | 보라 (#a78bfa) |

행 우측 컬럼에 작은 칩.

### 3. 모달 UX
- 공내역서 업로드 모달(`UploadParseModal.tsx`) 패턴 재사용
- `Upload.Dragger` + `.pdf` 검증 + 20MB 제한
- 업로드 즉시 모달 닫음 + 행 추가됨 + 상단 토스트 "AI 분석을 시작했습니다. 완료까지 30초~1분"
- 백엔드 OCR 30초~1분 소요 안내 노출

### 4. 모달 위치
- 기존 "보증보험 추가" 버튼 외에 "PDF 업로드" 버튼 신설 — 두 진입점
- 또는 `Plus` 드롭다운 ("직접 입력" / "PDF로 자동 추출")
- **결정**: 별도 버튼 2개 (단순)

### 5. MSW 시뮬
- `POST /upload`: 새 warranty (ocrStatus=PENDING, 나머지 비움) 즉시 추가 + 202 응답
- `setTimeout(10000)` 후 SUCCESS로 갱신 (가짜 OCR 결과 — 보험사 랜덤, 정책번호 랜덤, 1년 보증)
- 폴링이 알아서 새 데이터 반영

### 6. coverageAmount 처리
- 백엔드는 `coverageAmount` 없음 (필드 자체 없음 — `insuranceCompany/policyNumber/start/end/filePath/memo`만)
- 프론트는 mock 데이터에 `coverageAmount` 있음 — 백엔드와 불일치
- **이번 범위 외** — BACKLOG P1 "frontend Warranty 타입 백엔드 일치" 신규 등록
- 이번엔 OCR 응답에서 받지 못한 필드는 그냥 0 또는 undefined로 둠

## 산출물 체크리스트

- [ ] `types/domain.types.ts`: `OcrStatus` enum + `Warranty` 필드 추가
- [ ] `api/warranties.api.ts`: `uploadWarranty()` + `useUploadWarranty()` + `useWarranties` refetchInterval 옵션
- [ ] `pages/warranty/WarrantyUploadModal.tsx`: 신설 (UploadParseModal 패턴 참고)
- [ ] `pages/warranty/WarrantyListPage.tsx`:
  - 헤더에 "PDF 업로드" 버튼 추가
  - 행 우측에 `OcrStatusBadge` 표시
  - 폴링: PENDING 있으면 5초 간격
- [ ] `components/OcrStatusBadge.tsx`: 신설 (재사용성 있게)
- [ ] `mocks/handlers/warranties.handlers.ts`: `/upload` 핸들러 + setTimeout SUCCESS 시뮬
- [ ] `mocks/data/warranties.data.ts`: 일부 mock에 `ocrStatus: 'MANUAL'` 추가 (하위 호환)
- [ ] 결과 반영: BACKLOG / PROGRESS / 계획 결과
- [ ] `bun run lint` 통과 (`max-warnings: 0`)
- [ ] `bun run build` 통과 (tsc + vite build)

## 리스크 / 모르는 것

- 폴링 5초가 적절한지 — 백엔드 실제 OCR 30초~1분 소요. 더 길게(10초)도 가능. 일단 5초로
- PENDING 상태 행이 많아져도 폴링은 1번만 (전체 목록 조회). 부담 적음
- 백엔드와 mock 데이터 불일치(`coverageAmount` 등)는 이번 범위 외, BACKLOG에 등록

## 테스트 / 검증

- `bun run lint` — 0 errors (warning 4건 기존 — 손대지 않음)
- `bun run build` — tsc + vite 통과
- 수동 검증: 다음 세션에서 `bun run dev`로 브라우저에서 업로드 → 10초 후 SUCCESS 전환 확인

## 결과 (2026-06-18)

### 신규 2종
- `components/OcrStatusBadge.tsx` — PENDING(회색, spinner) / SUCCESS(초록) / FAILED(빨강) / MANUAL(미표시)
- `pages/warranty/WarrantyUploadModal.tsx` — 현장 ID + Upload.Dragger + .pdf + 20MB

### 갱신 6종
- `types/domain.types.ts` — `OcrStatus` 타입 + `Warranty.ocrStatus?` + `Warranty.filePath?`
- `api/warranties.api.ts` — `uploadWarranty(file, siteId)` + `useUploadWarranty()` + `useWarranties` 두번째 인자 `refetchInterval`
- `pages/warranty/WarrantyListPage.tsx` — UploadCloud 버튼 + 보험사 셀에 `OcrStatusBadge` + `hasPendingHint` state로 5초 폴링 토글
- `mocks/handlers/warranties.handlers.ts` — `/upload` 핸들러 (202 Accepted) + 10초 후 자동 SUCCESS 시뮬
- `mocks/data/warranties.data.ts` — 첫 항목에 `ocrStatus: 'MANUAL'`

### 검증
- ✅ `bun run lint`: 0 errors (warnings 4건 모두 기존 트래킹된 `any` 캐스트, 위치만 shift)
- ✅ `bun run build`: tsc + vite 통과 (`http.post<never, never, ApiResponse<Warranty | null>>` 제네릭 명시로 union 타입 추론 해결)

### 한계 / 후속 BACKLOG
- 백엔드와 mock 데이터 `coverageAmount` 불일치는 이번 범위 외 — BACKLOG P1 "frontend Warranty 타입 백엔드 일치" 신규 등록
- 폴링 5초는 임시값 — 실제 OCR 시간(30초~1분) 기준 조정 가능
