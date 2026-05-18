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

## 다음 작업 (우선순위 순)

### 🔜 1. 프론트엔드 CRUD 모달/폼 구현
- 각 페이지에서 생성/수정 모달 (현재 버튼만 있고 동작 없음)
- 견적서: 항목(EstimateItem) 추가/제거 폼
- 매입/세금계산서/보증보험: 생성/수정 폼

### 🔜 2. 현장 상세 페이지 구현
- 현장별 견적서/매입/세금계산서/보증보험 통합 뷰
- 현장 손익 대시보드 (Profit API 연동)

### 🔜 3. 프론트엔드 세부 기능
- 헤더 알림 벨 아이콘 (미읽음 뱃지)
- 검색/필터링 기능
- 반응형 레이아웃

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

## 알려진 이슈

- `estimate-service/build.gradle` line 9 타이포 (`boo,t`) — ✅ 수정 완료 (2026-04-09)
- tax-service `ddl-auto: update`로 변경 완료
- Gradle wrapper (gradlew) 미설치 — 빌드 시 설치 필요
