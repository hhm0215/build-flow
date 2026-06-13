# BuildFlow 개발 진행상황

> 이 파일은 매 작업 세션 시작/종료 시 업데이트합니다.
> Claude와 대화 시작할 때 "PROGRESS.md 읽어줘"라고 하면 빠르게 컨텍스트 복원 가능.

## 현재 브랜치: `develop`

---

## 완료된 작업

### ✅ 인프라 서비스
- **eureka-server** — 서비스 디스커버리 완료
- **config-server** — 중앙 설정 서버 완료
- **gateway-server** — API Gateway, JWT 검증, 라우팅 완료
- **docker-compose.yml** — MySQL, Redis, Kafka(KRaft), Zipkin 로컬 환경 완료

### ✅ auth-service (Port 8081)
- 회원가입 / 로그인 / 토큰 갱신 / 로그아웃
- JWT (access 30분, refresh 7일)
- Redis 블랙리스트 (로그아웃 시 access token 무효화)
- 역할: ADMIN(혜민), VIEWER(아버지)

### ✅ estimate-service (Port 8082) — CRUD + AI 파싱 완료
- 견적서 CRUD (생성/조회/수정/삭제)
- 견적 항목 자동 합계 계산
- 견적 확정(CONFIRM) — 확정 후 수정/삭제 불가
- `siteId`로 현장별 견적서 필터링
- Kafka 이벤트 구조 정의 (`KafkaEvent<T>`)
- 공내역서 AI 파싱: Ollama 기반 (qwen2.5:7b) — 엑셀 업로드 → POI 파싱 → Ollama JSON 구조화
  - 기술 전환: Claude API(claude-sonnet-4-6) → Ollama(로컬 LLM, 무료)
  - `58f6fa8` Ollama API 설정 (application.yml, OllamaConfig)
  - `3ac97b4` docker-compose에 Ollama 서비스 추가
  - `9128d2e` Ollama 기반 공내역서 AI 파싱 구현

### ✅ frontend — 관리자 로그인 + 현장관리 허브
- 관리자 아이디 로그인 화면 개편
- 현장관리 허브 화면 대규모 개편 (SiteListPage)
- `4b83125` feat(frontend): 관리자 아이디 로그인 및 현장관리 허브 화면 개편

### ✅ site-service (Port 8083) — CRUD 완료
- 거래처(Client) CRUD: `POST/GET/PUT /api/v1/clients`
- 현장(Site) CRUD: `POST/GET/PUT/DELETE /api/v1/sites` + 상태변경 `PATCH /api/v1/sites/{id}/status`
- SiteStatus: IN_PROGRESS, SETTLING, WARRANTY, COMPLETED
- JPA Auditing 활성화, ddl-auto: update
- `ed70866` chore(site-service): JPA Auditing 활성화, ddl-auto update 변경, global 패키지 구성
- `a6f986f` feat(site-service): 거래처(Client) CRUD 구현
- `efc75c7` feat(site-service): 현장(Site) CRUD 구현

### ✅ site-service — 손익 계산 + Kafka 연동
- SiteProfit 엔티티: 현장별 총견적액, 총매입액, 마진, 마진율 저장
- Kafka Consumer: `estimate.parsed`, `purchase.registered` 토픽 수신 → 손익 자동 갱신
- Profit API: `GET /api/v1/sites/{id}/profit` — 마진/마진율 조회
- ProfitService: 견적/매입 금액 누적 시 마진 자동 재계산

---

### ✅ purchase-service (Port 8084) — CRUD + Kafka 발행 완료
- 매입(Purchase) CRUD: `POST/GET/PUT/DELETE /api/v1/purchases` (siteId 필터 지원)
- Purchase 엔티티: siteId, 품목명, 수량, 단가, 총액(자동계산), 공급업체, 매입일
- Kafka 발행: `purchase.registered` 토픽 → site-service 소비하여 손익 재계산
- global 패키지: ApiResponse, ErrorCode, BusinessException, GlobalExceptionHandler, KafkaEvent
- `b6248f5` chore(purchase-service): JPA Auditing 활성화, ddl-auto update 변경, global 패키지 구성
- `076cfd9` feat(purchase-service): 매입(Purchase) CRUD + Kafka 발행 구현

---

### ✅ tax-service (Port 8085) — CRUD + 미수금 추적 완료
- 세금계산서(TaxInvoice) CRUD: `POST/GET/PUT/DELETE /api/v1/taxes` (siteId, type 필터)
- TaxInvoice 엔티티: siteId, 구분(SALES/PURCHASE), 공급가액, 세액, 총액(자동계산), 거래처, 입금여부
- 입금 확인: `PATCH /api/v1/taxes/{id}/confirm-payment`
- 미수금 조회: `GET /api/v1/taxes/outstanding?siteId={id}`
- 미수금 = 매출 세금계산서 총액 - 입금 확인 금액
- `98d49c3` chore(tax-service): JPA Auditing 활성화, ddl-auto update 변경, global 패키지 구성
- `2ca9b70` feat(tax-service): 세금계산서 CRUD + 미수금 추적 + 입금 확인 구현

---

### ✅ notification-service (Port 8086) — 인앱 알림 + 하자보증보험 완료
- build.gradle에 JPA, MySQL, Validation 의존성 추가
- Kafka Consumer: estimate.parsed, purchase.registered 수신 → 인앱 알림 자동 생성
- 알림 API: `GET /api/v1/notifications`, 미읽음 건수, 개별/전체 읽음 처리
- 하자보증보험 CRUD: `POST/GET/PUT/DELETE /api/v1/warranties` (siteId 필터)
- 만료 임박 조회: `GET /api/v1/warranties/expiring?days=30`
- 응답에 만료까지 남은 일수, 만료 여부 포함
- `c16e73e` chore: JPA/MySQL 추가, global 패키지 구성
- `c3109f2` feat: 인앱 알림 (Kafka Consumer + 조회/읽음)
- `1066213` feat: 하자보증보험 CRUD + 만료 임박 조회
- ⚠️ 미구현: PDF 업로드 + Tesseract OCR, 만료 경고 스케줄러 (다음 단계)

---

### ✅ site-service — AI 요약 대시보드 완료
- Ollama 기반 현장 종합 분석 (qwen2.5:7b)
- DashboardController: `/api/v1/dashboard/stats`, `/api/v1/dashboard/summary`
- `f8ba39b` feat(site-service): AI 요약 대시보드 구현

---

### ✅ frontend — API 연동 기반 구축 완료
- 도메인 타입 전면 재설계 (백엔드 DTO 1:1 대응)
- 전 서비스 CRUD API 모듈 + TanStack Query 훅 구현
- MSW 핸들러 전체 엔드포인트 커버 (GET/POST/PUT/DELETE)
- 신규 API: dashboard, notifications, warranties
- 페이지 컴포넌트 타입 적용 (Estimate/Purchase/Site/Tax ListPage)
- `0c11e87` refactor(frontend): 도메인 타입 재정의 + API/MSW CRUD 패턴 통일
- `1e32ebb` refactor(frontend): 페이지 컴포넌트 신규 타입 적용

---

### ✅ frontend — 알림 + 보증보험 페이지 + 대시보드 API 연동
- NotificationListPage: 알림 목록 (읽음/미읽음, 전체 읽음)
- WarrantyListPage: 하자보증보험 CRUD + 만료 임박 배너
- DashboardPage: 하드코딩 제거 → useDashboardStats/useDashboardSummary 연동
- 사이드바 메뉴에 알림/보증보험 추가, 라우트 등록

---

### ✅ frontend — CRUD 등록 모달 4종 완료
- 견적서 작성 / 매입 등록 / 세금계산서 등록 / 보증보험 등록 모달
- `3b2a919`, `21e708f`, `f2d9ed7`, `219d1e0`

---

### ✅ frontend — 현장 상세 페이지 (SiteDetailPage)
- `/sites/:id` 라우트 신설, SiteListPage에서 진입 버튼 추가
- 헤더: 현장명/상태/거래처/공사기간/주소/메모 + 상태 변경 Select (Antd message 토스트)
- 손익 카드 4종: 매출(견적합계) / 매입 / 마진(마진율) / 미수금 — `useSiteProfit` 우선, 미응답 시 클라이언트 계산 폴백
- 탭 4개: 견적서 / 매입 / 세금계산서 / 보증보험 (각 도메인 API에 `siteId` 필터 전달)
- 잘못된 siteId 가드 + 목록 복귀 동작
- AI 요약 영역은 비용 검토 후 별도 진행 (이번 범위 제외)

---

### ✅ frontend — 헤더 알림 벨 + 브레드크럼 보정 (2026-06-07)
- NotificationBell: 헤더 우측 미읽음 뱃지(99+ 캡) + 최근 5건 드롭다운 + ESC/외부 클릭 닫기
- 알림 클릭 시 markAsRead + siteId 있으면 현장 상세로 이동
- `/sites/:id` 브레드크럼: matchPath + useSite로 siteName 동적 표시
- 비숫자 siteId NaN 가드
- `ddfddf1` feat(frontend): 헤더 알림 벨 + /sites/:id 브레드크럼 보정

---

### ✅ frontend — 공내역서 업로드 UI (2026-06-09)
- 엑셀 업로드 모달 신설: 드래그앤드롭 + .xlsx/.xls 검증 + 10MB 제한
- `POST /api/v1/estimates/parse` 호출 (multipart/form-data, timeout 120s)
- 파싱 결과 항목 테이블 미리보기 + 합계 표시
- "견적서로 만들기" → 견적서 작성 모달 열기 + items/title 자동 채움 (afterOpenChange + form.setFieldsValue)
- MSW 핸들러: 1.5초 지연 + 10개 더미 항목 (실서버 Ollama는 30초~1분 소요, 모달에 안내)
- 신규 타입: `ParseResult`, `ParsedItemResult`
- 신규 훅: `useParseEstimateFile`

---

### ✅ frontend — /review 후속 fix 2건 (2026-06-10) — 워크플로우 시범 사이클
- `estimates.api.ts`: 공내역서 parse 요청에서 `Content-Type: multipart/form-data` 수동 헤더 제거 (axios 자동 boundary에 위임)
- `UploadParseModal.tsx`: parse 실패 시 `isAxiosError` narrow 후 백엔드 `error.response?.data?.error?.message` 우선 노출
- 워크플로우 시스템(BACKLOG/RETROSPECTIVE/plans + CLAUDE.md 사이클) 도입 후 첫 시범 사이클로 완주
- 계획 문서: `.claude/plans/2026-06-10-review-fix-2.md`

---

### ✅ 잡다한 수정 누적 (~2026-06-08)
- `estimate-service/build.gradle` line 9 타이포(`boo,t`) 수정 (2026-04-09)
- tax-service `ddl-auto: update`로 변경
- ESLint v9 flat config 도입 (`66af734`, 2026-06-08)

> 미완 항목 "Gradle wrapper 설치"는 `BACKLOG.md` P2로 이관.

---

### ✅ frontend — 검색/필터링 5페이지 일괄 패턴화 (2026-06-08)
- 공통 부품: FilterBar(children 패턴) + FilterSearch / FilterSelect / FilterDateRange / FilterAmountRange
- 공통 훅: useFilterParams(URL 동기화 + 스키마 검증 + 무효값 자동 정리) + useDebouncedValue(250ms)
- 5페이지 적용:
  - PurchaseListPage: 검색(품목/공급업체) · 매입일 · 금액 범위
  - EstimateListPage: 검색(제목) · 상태 · 견적일 · 총액 범위
  - WarrantyListPage: 검색(보험사/증권번호) · 유효/만료 · 만료일 범위
  - TaxListPage: 검색(거래처) · 구분 · 입금상태 · 발행일 · 총액 범위
  - SiteListPage: 사이드바 인라인 검색 + 상태
- /review 적용 fix (P1/P2): cleanupOnceRef 제거 + setFilters functional updater + AmountRange 0 잔류 방지 + Select/DateRange 메모이제이션 + motion 스태거 캡 + 날짜 ISO 정규화 + SiteListPage 4-dataset 메모이제이션
- 별도 PR 예정: useListFilters 추상화, useFilterParams 타입 추론, Warranty 필드 명명 일관성

---

## 다음 작업

→ **`.claude/BACKLOG.md`** 참조 (우선순위 단일 진실원).

---

## 서비스 포트 정리

| 서비스 | 포트 |
|--------|------|
| eureka-server | 8761 |
| config-server | 8888 |
| gateway-server | 8080 |
| auth-service | 8081 |
| estimate-service | 8082 |
| site-service | 8083 |
| purchase-service | 8084 |
| tax-service | 8085 |
| notification-service | 8086 |

## Kafka 토픽 현황

| 토픽 | 발행 서비스 | 소비 서비스 | 구현 여부 |
|------|-----------|-----------|---------|
| estimate.parsed | estimate-service | site-service | ✅ 발행+소비 구현 |
| purchase.registered | purchase-service | site-service | ✅ 발행+소비 구현 |

## 다음 세션 진입점 (2026-06-13 갱신)

**현재 git 상태**:
- `origin/main` = `9b18e86` (PR #21 머지 시점, Merge pull request #21)
- `origin/develop` = main과 동기화 (PR #21 머지 직후, 추가 커밋 없음)
- 다음 세션은 BACKLOG P0 항목 진행 후 새 PR 생성 사이클 시작 가능

**다음 세션 첫 액션**:
1. `git fetch` 후 `git log --oneline origin/main..origin/develop` 실측 (혹시 다른 머신·시점에서 추가 push 있는지)
2. `.claude/BACKLOG.md` P0 항목(현재: notification-service OCR + 만료 스케줄러) 계획 문서 작성부터 시작
3. 워크플로우 5+1+1+1 단계(이제 8단계 — 백로그→계획→구현→결과→회고→PR 생성→PR 자동 머지→main 동기화) 그대로 적용

**활성화된 워크플로우 자동화** (2026-06-13 갱신):
- ✅ PR 생성 자동 (`gh pr create`)
- ✅ PR 자동 머지 (`gh pr merge --merge`) — SHA 검증 안전망 통과 시
- ✅ main 브랜치 보호 룰: force-push/delete 차단, PR 경로 강제
- ⚠️ 활성 PR 동안 develop 추가 push 시 PR 본문 즉시 갱신 의무 (RETROSPECTIVE 회고)

## 회고

→ **`.claude/RETROSPECTIVE.md`** 참조.
