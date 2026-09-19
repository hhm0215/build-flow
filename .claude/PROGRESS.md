# BuildFlow 개발 진행상황

> 이 파일은 매 작업 세션 시작/종료 시 업데이트합니다.
> Claude와 대화 시작할 때 "PROGRESS.md 읽어줘"라고 하면 빠르게 컨텍스트 복원 가능.

## 현재 브랜치: `develop`

## 현재 진행 중 — 실사용 UI 라이프사이클 (2026-09-19)

- 현장 생성·거래처 등록·견적 확정 UI 첫 단계는 완료했다. 남은 현장 수정·견적 수정/삭제 등은 `.claude/BACKLOG.md` P0 항목을 따른다.
- 손익 집계의 Kafka 멱등성·재시도·동시 갱신 보강도 별도 P0 작업으로 남아 있다.

---

## 완료된 작업

### ✅ 실사용 UI 라이프사이클 — 거래처 등록 (2026-09-19)
- 현장 생성 모달 안에서 거래처를 등록하면 새 ID가 현장 폼에 자동 선택되도록 연결했다. 업체명 필수·선택 필드 길이/이메일 검사·서버 오류 표시·재시도를 구현했다.
- 백엔드 DTO에 맞춘 POST mutation과 nullable 거래처 응답 타입, MSW POST/GET 및 신규 거래처의 현장 연결을 추가했다. 계약 검토는 역할 분리 에이전트가 읽기 전용으로 수행했다.
- 독립 코드 리뷰에서 중복 제출·거래처 모달 경합, Kafka 손익 지연, 조회 오류 시 금액 오표시를 발견해 수정했다. 현장 상세 금액은 최신 확정 견적·매입 목록으로 계산하며 조회 실패·로딩 상태를 구분한다.
- 프론트 lint·Vitest 37개·production build 통과. 프론트 Docker 재빌드 후 HTTP 200, 미인증 거래처 API 401 확인. 영속 데이터가 남는 거래처 POST 실서비스 스모크와 브라우저 수동 E2E는 수행하지 않았다.
- 계획: `.claude/plans/2026-09-19-client-create-ui.md`.

### ✅ 실사용 UI 라이프사이클 — 견적 확정 동선 (2026-09-19)
- DRAFT 견적 행에만 확정 버튼을 표시하고, 확인 모달에서 기존 API mutation을 연결했다. 중복 요청·진행 중 닫기 방지, 서버 오류 표시·재시도를 추가했다.
- 현장 허브·상세의 프론트 손익 합계에서 초안 견적 금액을 제외하도록 통일했다.
- 프론트 lint·Vitest 27개·production build 통과. 프론트 Docker 컨테이너 재빌드·재기동 후 HTTP 200 확인. 실제 Kafka 손익 이벤트 스모크는 집계 신뢰성 P0 이후로 보류했다.
- 계획: `.claude/plans/2026-09-19-estimate-confirm-ui.md`.

### ✅ 단일 관리자 loginId 인증 전환 (2026-09-19)
- 실사용 데이터가 없음을 확인하고 관리자 1개를 가족이 공유하기로 결정. 공개 signup·VIEWER 역할을 제거하고 `admin_accounts` 고정 PK 1, 로컬 대화형 관리자 초기화, `loginId/password` 로그인을 구현했다.
- 최초 관리자 비밀번호 최소 길이를 사용자 요청에 따라 9자로 조정하고 서버·스크립트·테스트·문서를 일치시켰다. 계정 1개와 60자 BCrypt 해시를 DB에서 확인했다.
- JWT `authVersion=2` 및 Gateway access/ADMIN 검증, Discovery 자동 라우트 비활성화, 프론트·MSW·실서버 개발 모드·로그인 401 표시를 동기화했다.
- Gradle 전체 테스트, 프론트 lint·Vitest 20개·build, PowerShell 구문 검사 통과. 공개 signup 403, 잘못된 로그인 401, 미인증 현장 요청 401, Discovery 자동 경로 404를 확인했다.
- 로컬 대화형 `verify-admin.ps1`의 관리자 로그인·현장 생성/조회/삭제 API 스모크 통과. 임시 현장 제거 후 DB는 관리자 1명, 현장 0개, 현장 손익 0건. 기존 BuildFlow 데이터는 없었고 다른 프로젝트 컨테이너·볼륨은 건드리지 않았다.
- 계획: `.claude/plans/2026-09-19-single-admin-login-id.md`. 기존 실데이터 스키마 마이그레이션 체계는 P1 백로그로 분리했다.

### ✅ 실사용 UI 라이프사이클 — 현장 생성 첫 단계 (2026-09-19)
- 현장 추가 버튼을 생성 폼·기존 mutation에 연결하고 선택적 거래처 조회, 날짜 변환, 오류 표시, 성공 시 상세 이동을 구현했다. MSW 거래처 목록과 생성 응답도 맞췄다.
- UI 테스트 4개를 포함한 프론트 20개 테스트, lint, build 통과. 관리자 인증 후 현장 생성/조회/삭제 API 스모크도 통과했다. 브라우저 수동 E2E는 수행하지 않았고 나머지 UI 동선은 P0 백로그에 남는다.
- 계획: `.claude/plans/2026-09-19-ui-lifecycle-first-slice.md`.

### ✅ Windows fresh clone 개발 준비 (2026-09-18)
- PowerShell helper 3종: 안전한 `.env` 생성·전체 스택 관리, 서비스별 환경 로드, 대화형 관리자 생성
- README/Windows 가이드를 Bun 1.3.11, 루트 Gradle wrapper, 포트 3000, native/Compose Ollama 실제 경로에 맞게 전면 정렬
- 모든 개발 host publish를 `127.0.0.1`로 제한하고 컨테이너 Ollama를 선택 profile로 분리
- 루트 `.dockerignore`로 백엔드 context 약 366MB → 서비스별 약 12~89kB, `.env`·VCS·로컬 산출물 전송 차단
- Gradle wrapper 공식 SHA-256 고정, CI runner Ubuntu 24.04 고정, Windows PowerShell 5.1·`gradlew.bat` job 추가
- 검증: Gradle 전체 테스트, frontend lint·Vitest 7개·build, Compose/15개 컨테이너/HTTP 200, PR CI run `35340803038` 3개 job 통과
- 자동 리뷰 2회 CRITICAL/HIGH 0, 계획: `.claude/plans/2026-09-18-windows-fresh-clone-readiness.md`

### ✅ 실데이터 파일럿 선행 런타임 안정화 (2026-09-15)
- notification-service Docker DB 환경/MySQL health 의존성 추가, 보증보험 업로드를 `buildflow_warranty_uploads` named volume으로 영속화
- site-service Docker Ollama 주소를 호스트 네이티브 인스턴스로 정렬, AI summary 실제 호출 HTTP 200
- 트레이싱 앱 8개의 Zipkin endpoint를 서비스 DNS로 정렬 — Zipkin에서 8개 서비스 span 수집 확인
- 결합 Compose가 `buildflow-net`을 직접 생성·관리하도록 수정, clean host 단일 기동 경로 정렬
- Bun packageManager/Docker/CI를 1.3.11로 고정, GitHub Actions를 `checkout@v7`·`setup-java@v6`으로 갱신, `.dockerignore`로 frontend build context 약 266.69MB → 5.63kB 축소
- 서비스별 IDE `bin/` 산출물 ignore, 풀 Docker/계약 동기화/신규 서비스 편입 규칙을 CLAUDE.md에 고정
- 검증: Gradle 전체 테스트 29개, frontend lint·Vitest 7개·build, Compose config, 16개 컨테이너 기동, 핵심 health/API HTTP 200 모두 통과
- 계획: `.claude/plans/2026-09-15-pilot-runtime-stabilization.md`

### ✅ 인프라 서비스
- **eureka-server** — 서비스 디스커버리 완료
- **config-server** — 중앙 설정 서버 완료
- **gateway-server** — API Gateway, JWT 검증, 라우팅 완료
- **docker-compose.yml** — MySQL, Redis, Kafka(KRaft), Zipkin 로컬 환경 완료

### ✅ auth-service (Port 8081)
- 단일 관리자 로그인 / 토큰 갱신 / 로그아웃 (공개 회원가입 없음)
- JWT (access 30분, refresh 7일)
- Redis 블랙리스트 (로그아웃 시 access token 무효화)
- 역할: ADMIN 1개 계정 공유

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

### ✅ frontend — useListFilters 훅 추상화 (2026-07-04, P1 완료)
- 5페이지(estimate/purchase/tax/warranty/site) 필터 scaffolding을 `useListFilters` 훅으로 통합
- filterFn(검색+술어)만 페이지에 남기고 resetFilters·activeCount는 schema/groups에서 자동 도출
- 5.5 리뷰 자가 발견: `filtered` memo가 검색어 타이핑마다 재계산되던 디바운스 회귀 → `filterSig`+ref로 in-cycle fix
- 계획: `.claude/plans/2026-07-04-use-list-filters.md`

---

### ✅ chat-service Phase 2 — SSE 스트리밍 + 채팅 패널 UI (2026-07-15)
- 백엔드: `POST /api/v1/chat/stream`(SseEmitter 180s, 전용 executor) — 이벤트 session/status/token/done/error. 동기 API 유지
- 프론트: fetch+ReadableStream SSE 클라이언트(UTF-8 청크 경계 파서, 단위 테스트) + ChatPanel 플로팅 패널(전송/중단/새 대화) MainLayout 장착
- 설계 변경: 툴 선택 라운드 답변을 조각 relay(chunkAnswer) — 5.5 리뷰 HIGH(이중 LLM 생성) fix. 실시간 stream:true+tools는 Phase 3
- 5.5 리뷰(high) findings 10건 전부 in-cycle fix: emitter 생명주기/중단 INFO 분류/CHAT_BUSY 거부 처리/ErrorCode 보존/lombok.config/reader.cancel/uuid 폴백 등
- 런타임 검증(Gateway 경유): 툴콜 스트리밍·2라운드 체인·세션 연속성·중단(부분 미저장, ERROR 0)·DB 영속화 전부 ✅
- 환경 함정 4건 런북화(plan 문서): 11434 이중 리스너(네이티브/도커 Ollama), nginx 8080/8081 점유, DB_PASSWORD 필수, config-server 우회
- 계획: `.claude/plans/2026-07-04-chat-service.md` "Phase 2 결과"

### ✅ chat-service Phase 1 런타임 검증 + 잠복 버그 3건 fix (2026-07-04)
- Claude가 직접 실행: docker(mysql/redis/ollama+qwen2.5:7b) + bootRun(eureka→site→chat) → 툴콜 E2E 검증
- "등록된 현장 목록" → listSites 툴콜 → Feign 실데이터 답변 ✅ / 같은 세션 "마진 얼마?" → getSiteProfit 체인 ✅ / chat_messages 영속화 ✅
- 잠복 버그 fix: ① bitnami/kafka Docker Hub 소멸 → bitnamilegacy 교체 ② 전 서비스 JDBC `allowPublicKeyRetrieval=true` 추가(새 볼륨에서 기동 불가 버그) ③ init SQL에 buildflow_chat 추가
- 7b 토큰 잡음("마argin율") 실측 → Phase 3 견고화 근거. 검증 후 mariadb 복구·컨테이너 정리 완료

### ✅ 테스트 파운데이션 + CI (2026-07-04, ADR-015)
- **CI**: GitHub Actions `.github/workflows/ci.yml` — PR(→main)/push(develop)마다 백엔드 `./gradlew test`(JDK 17) + 프론트 `bun lint/test/build`
- **프론트**: Vitest + Testing Library + jsdom 셋업 + 파일럿 `useListFilters.test.tsx`(4개 통과)
- **백엔드**: 파일럿 `ToolExecutorTest`/`ToolCatalogTest`(chat-service, Mockito) + 기존 notification 3종 = `./gradlew test` green
- **워크플로우**: 5.6단계(순수 로직 변경 시 단위 테스트 동반) 신설, ADR-015. 수동 회귀 검증 토일 → CI 자동화
- 로컬 검증: `bun run test`(4 pass) + `./gradlew :chat-service:test :notification-service:test`(green)

### ✅ chat-service Phase 1 — 툴콜 에이전트 챗봇 (2026-07-04)
- 신규 마이크로서비스(포트 8087, buildflow_chat). 아키텍처 A(툴콜 에이전트) — 벡터스토어 없이 OpenFeign 실데이터 기반
- `OllamaToolService` 에이전트 루프(Ollama `/api/chat` 툴콜 왕복) + 도구 4종(listSites/getSiteProfit/getOutstandingTax/getDashboardSummary)
- Feign: SiteClient/TaxClient, 이력: MySQL(ChatSession/ChatMessage) + Redis 세션 TTL, API: `POST /api/v1/chat`
- Gateway `/api/v1/chat/**` 라우트 추가, settings.gradle 등록. compileJava 10모듈 통과(JDK 17), 5.5 리뷰 CRITICAL/HIGH 0
- 계획: `.claude/plans/2026-07-04-chat-service.md` (Phase 2 SSE+UI, Phase 3 확장 남음)

### ✅ 인프라 — Gradle wrapper 생성 + 백엔드 컴파일 검증 (2026-07-04)
- 루트에 `gradlew` + gradle 8.10 wrapper 생성·커밋 (멀티프로젝트 9개 서비스 공통)
- `brew install gradle`(9.6.1)로 호스트 gradle 확보 → `gradle wrapper --gradle-version 8.10`
- `brew install openjdk@17` + `JAVA_HOME` 지정 → `./gradlew compileJava` **9개 서비스 전부 BUILD SUCCESSFUL** (프로젝트 최초 백엔드 컴파일 검증)
- 실행법 CLAUDE.md "빌드 & 실행 > Gradle"에 JDK 17/JAVA_HOME 문서화

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

### ✅ P2 LOW 2건 정리 (2026-06-26)
- `WarrantyOcrParser.findPeriod` — `if (!label.find())` 단발 → `while (label.find())` 다중 라벨 순회. 두 날짜 모두 추출되는 첫 라벨 채택, 못 찾으면 첫 라벨의 start만 fallback으로 반환 (테스트 1건 추가)
- `DefectWarranty.isExpiringSoon` — 호출자 0건 dead 메서드 제거. 만료 판정은 Repository의 BETWEEN 쿼리(`findExpiringSoon`/`findExpiringNotYetAlerted`)와 `isExpired()` 메서드가 담당
- 5.5단계 인라인 검토 (변경 양 작아 finder agent 생략) — HIGH/CRITICAL 없음

---

### ✅ P1 MEDIUM 6건 + 5.5단계 첫 적용 (2026-06-22)
- useMemo+setState 안티패턴 → React Query refetchInterval 함수 전달 (setState 제거)
- `useWarranties` refetchInterval 옵션 타입 확장 (`number | false | function`)
- `DefectWarranty.isExpiringSoon/isExpired` — boundary today 포함 (당일 임박, 다음날 만료)
- `SiteSelect` — AntD `SelectProps<number>` spread, Form.Item 주입 props forward
- `WarrantyOcrParser` — PERIOD_LABEL + DATE 분리 패턴 (단방 날짜 추출 가능, 테스트 2건 추가)
- `DefectWarranty.update` — null 인자 skip (partial update 시맨틱)
- `DefectWarrantyService.delete` — TransactionSynchronization.afterCommit으로 파일 cleanup 이동 (commit 전 inverse-orphan 방지)
- **5.5단계 자동 코드 리뷰** (ADR-013 첫 적용) — finder 2개 병렬, HIGH 1건 발견 즉시 같은 PR fix, MEDIUM 2건 + LOW 2건은 BACKLOG 분리 등록

---

### ✅ warranty 핫픽스 Phase 1.5 — /code-review CRITICAL+HIGH 7건 (2026-06-22)
- **@Async tx race 해소**: `createFromOcr`에서 `@Transactional` 제거 → save 자체 tx commit 후 processAsync 호출 (PENDING 영구 고착 사고 방지)
- **servlet.multipart YAML 위치 수정**: `spring.servlet.multipart.*`로 이동 → 20MB 한도 정상 적용
- **applyOcrResult 가드**: 1개 이상 추출 시 SUCCESS, 모두 null이면 FAILED (거짓 SUCCESS 뱃지 방지)
- **Tesseract bean prototype scope + ObjectProvider**: 동시 호출 시 native lib race 회피
- **Kafka send 동기 await**: `CompletableFuture.get(5s)` + 실패 시 markExpiringAlertSent skip → 다음 cron 재시도
- **timezone 명시**: `@Scheduled(zone="Asia/Seoul")` + `LocalDate.now(KST)` → UTC 컨테이너에서도 09:00 KST 정확 발사
- **ocrStatus 마이그레이션 안전**: `@ColumnDefault("'MANUAL'")` → 기존 운영 데이터 backfill
- 계획 문서: `.claude/plans/2026-06-22-warranty-hotfix.md`
- /code-review MEDIUM 7건은 P1 신규 등록

---

### ✅ 백엔드 DefectWarranty.coverageAmount 필드 추가 (2026-06-21)
- `DefectWarranty.coverageAmount BIGINT` 필드 + 빌더/update 메서드 인자 추가
- `WarrantyCreateRequest` / `WarrantyUpdateRequest`에 `Long coverageAmount` 옵션 필드
- `WarrantyResponse`에 `coverageAmount` 포함
- `DefectWarrantyService.create/update` 호출부 갱신
- `docs/ERD.md`: warranties → defect_warranties (실제 테이블명) + insurance_company/policy_number/coverage_amount 컬럼 반영, NOT NULL 표시
- `ddl-auto: update`로 컬럼 자동 마이그레이션 (NULL 허용)
- frontend는 이미 옵셔널 처리 완료 (사이클 2 짝 완성)

---

### ✅ 위임 모드 P1 일괄 처리 + 자동화 가이드 (2026-06-21)
- **사이클 1**: InputNumber `as 0`/`as any` 캐스트 정리 — 5개 InputNumber `<number>` 제네릭 명시 + createWarranty/updateWarranty 시그니처 좁힘. lint warning 4 → 0
- **사이클 2**: `Warranty.coverageAmount` optional 처리 + WarrantyListPage/SiteDetailPage null 가드. 백엔드 추가는 BACKLOG P2로 분리
- **사이클 3**: `SiteSelect` 컴포넌트 신설 + 4개 모달(estimate/purchase/tax/warranty) siteId 입력을 useSites 기반 검색 드롭다운으로 교체
- **사이클 4**: `useFilterParams` 함수 오버로드 + `InferFilters<S>` 타입 추론. Warranty 필터 `endStart/endEnd` → `expiryFrom/expiryTo` 명명. useListFilters 추상화는 위험 분리로 별도 P1 등록
- **사이클 5**: `docs/AUTOMATION_GUIDE.md` 신설 — 자동화 3모드 / 8단계 사이클 / 진입 키워드 / 멈춤 조건 / FAQ / 트러블슈팅 / 치트시트
- 위임 모드 진입 키워드 1회로 5 사이클 자동 처리 (ADR-012 본격 활용)

---

### ✅ frontend — 보증보험 PDF 업로드 모달 + OCR 상태 뱃지 + 폴링 (2026-06-18)
- 신규: `components/OcrStatusBadge.tsx`, `pages/warranty/WarrantyUploadModal.tsx`
- 갱신: `types/domain.types.ts`(OcrStatus + ocrStatus/filePath 추가), `api/warranties.api.ts`(uploadWarranty + useUploadWarranty + refetchInterval), `WarrantyListPage`(UploadCloud 버튼 + 보험사 셀 뱃지 + PENDING 5초 폴링), MSW 핸들러(/upload 202 + 10초 자동 SUCCESS 시뮬)
- `bun run lint` 0 errors / `bun run build` 통과
- 계획 문서: `.claude/plans/2026-06-18-warranty-upload-ui.md`

---

### ✅ notification-service — PDF OCR Phase 2 (2026-06-18)
- `notification-service/Dockerfile` 전용 신설 — debian jammy + Tesseract 4 + kor·eng traineddata (다른 8 서비스는 alpine 그대로)
- `docker-compose.app.yml` notification-service build.dockerfile 경로 변경
- `build.gradle`: `pdfbox:3.0.3` + `tess4j:5.13.0` 의존성
- `OcrStatus` enum (PENDING/SUCCESS/FAILED/MANUAL) + `DefectWarranty` 필드 추가, 기존 빌더는 default MANUAL
- `WarrantyOcrParser` 정규식 — 보험사 화이트리스트 12종 + 증권번호/날짜 추출, 6 단위 테스트
- `WarrantyOcrService.@Async` 하이브리드 파이프라인: PDFBox 텍스트 1차 → 50자 미만이면 Tess4J 2차 (300 DPI)
- `WarrantyOcrConfig.@EnableAsync` + Tesseract 빈 (`kor+eng`, datapath 외부화)
- `POST /api/v1/warranties/upload` (multipart, 202 Accepted) — 즉시 PENDING + 비동기 OCR
- `application.yml`: `app.upload-dir`, `app.ocr.{min-text-length,scan-dpi,tessdata-path,languages}`, multipart 20MB 한도
- `.gitignore`: `uploads/` 추가
- ⚠️ 빌드 검증 못함 (gradlew 부재, BACKLOG P2). 실제 PDF 샘플 정규식 튜닝은 첫 사용 후 보강
- 계획 문서: `.claude/plans/2026-06-18-warranty-ocr-phase2.md`

---

### ✅ notification-service — 만료 스케줄러 Phase 1 (2026-06-18)
- `@EnableScheduling` 도입 + `WarrantyExpirationScheduler` (매일 09:00 cron)
- `KafkaProducerService` 신설 — `warranty.expiring` 토픽 (notification-service 최초 발행자)
- `WarrantyExpiringPayload`: warrantyId/siteId/insuranceCompany/endDate/daysUntilExpiry
- `DefectWarranty.lastExpiringAlertSentAt` + `markExpiringAlertSent` 추가 — cooldown 7일 단일 컬럼 중복 방지
- `findExpiringNotYetAlerted(today, threshold, cooldownThreshold)` 쿼리
- `KafkaConsumerService`에 `warranty.expiring` 핸들러 → 인앱 알림 `WARRANTY_EXPIRING` 자동 생성
- application.yml: kafka producer 설정 + `app.warranty.{alert-threshold-days=30, alert-cooldown-days=7, scheduler-cron="0 0 9 * * *"}` 외부화
- 계획 문서: `.claude/plans/2026-06-18-warranty-ocr-scheduler.md`
- Phase 2 (OCR): BACKLOG P0 재등록
- ⚠️ 빌드 검증 못함 (gradlew 부재 BACKLOG P2) — 정적 정합성 점검만 수행, 머지 후 `docker compose up`으로 통합 검증 필요

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

### ✅ warranty P1 MEDIUM 2건 정리 + 5.5 HIGH 2건 즉시 fix (2026-07-01)
- `WarrantyOcrParser.findPeriod` 거리 제약 강화: 라벨↔시작일 60자, 시작일↔종료일 15자. 발급일자 등 부가 날짜 오매칭 방지
- `DefectWarranty.update` 3-state 시맨틱: `Optional<T>` DTO로 skip/clear/update 구분 (memo/policyNumber/coverageAmount)
- 5.5단계 자동 리뷰 HIGH 2건 즉시 fix — PERIOD_LABEL greedy `[\s\S]{0,200}` 캡처가 다음 라벨을 삼키는 문제(라벨만 매칭+substring 방식으로 재작성) / START_TO_END_GAP=30이 오매칭 케이스 통과시켜 15로 조임
- 신규 테스트 3건 (parser 2건 + WarrantyUpdateRequest 3-state + DefectWarranty entity update)

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

### ✅ 풀 도커 실서비스 모드 전환 (2026-07-16, 커밋 4abbc7f + 23f35b8)
- 파일럿 온보딩 선행 작업: dev 서버는 MSW 전 도메인 목업이라 실사용 불가 → 프로덕션 빌드 풀 도커 스택으로 전환
- 잠복 갭 8건 발견·수정: chat-service 도커 편입 누락 / arm64 베이스 3종 / 로그인 loginId↔email 계약 불일치(MSW가 가림) / config-server git repo 요구 / 컨테이너 내 OLLAMA_URL localhost / 도커 Ollama OOM(VM 7.8GB) / Ollama 11434 이중 점유 / nginx SSE 버퍼링
- 스모크 전 구간 통과 (로그인→사이트/견적/매입/세금→chat SSE 툴콜 실DB 조회)
- 5.5 리뷰: CRITICAL/HIGH 0건, LOW 1건(nginx location 중복 — nonblocking)

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
| chat-service | 8087 |

## Kafka 토픽 현황

| 토픽 | 발행 서비스 | 소비 서비스 | 구현 여부 |
|------|-----------|-----------|---------|
| estimate.parsed | estimate-service | site-service | ✅ 발행+소비 구현 |
| purchase.registered | purchase-service | site-service | ✅ 발행+소비 구현 |

## 다음 세션 진입점 (2026-09-19 갱신)

**Git 상태**: 로컬 `develop`에 인증 전환(`c6cd517`), 현장 생성·견적 확정 UI(`47cb540`), 앞선 문서(`bfa736f`), 관리자 초기화 리뷰 수정(`81d85b2`), 거래처 등록·손익 표시 보강(`b5bf853`)을 작업 단위로 커밋했다. 원격 push/PR은 이번 GitHub 위임 사이클에서 진행 중이다.

**로컬 실행 상태**: Docker Desktop에서 프론트·Gateway·인증·현장 서비스와 필수 인프라가 실행 중이다. `.env`는 로컬에서 생성했고 Git에서 제외된다. 관리자 1명으로 로그인 및 현장 API 스모크를 통과했고 검증용 현장은 삭제했다.

**다음 작업**: `.claude/BACKLOG.md` P0 실사용 UI 라이프사이클의 다음 작은 동선(현장 수정 등)을 계획·구현한다. 견적 확정이 손익 이벤트를 발행하므로 실제 이벤트 검증은 P0 Kafka 집계 신뢰성 보강과 함께 수행한다. 실데이터 파일럿은 두 P0 위험을 해소한 뒤 진행한다.

**검증 상태**: Gradle 전체 테스트 및 변경된 인증 모듈 재테스트, 프론트 lint·Vitest 37개·production build, PowerShell 구문 검사, 관리자 로그인·현장 생성/조회/삭제 API 스모크 통과. 거래처 경로 미인증 요청은 401. 기존 프론트 번들 크기 경고는 P2 유지. 브라우저 수동 E2E, 거래처 POST 실서비스 스모크, 견적 확정 실제 이벤트, 전체 서비스 결합 검증은 아직 수행하지 않았다.

**자동화 가이드**: `docs/AUTOMATION_GUIDE.md` (8단계 + 5.5단계 자동 코드 리뷰). ADR-014 능동 발의 규칙은 상시 적용.

**활성화된 워크플로우 자동화** (2026-06-13 갱신):
- ✅ PR 생성 자동 (`gh pr create`)
- ✅ PR 자동 머지 (`gh pr merge --merge`) — SHA 검증 안전망 통과 시
- ✅ main 브랜치 보호 룰: force-push/delete 차단, PR 경로 강제
- ⚠️ 활성 PR 동안 develop 추가 push 시 PR 본문 즉시 갱신 의무 (RETROSPECTIVE 회고)

## 능동 발의 로그 (실험 종료 — 2026-07-04 확정, 아카이브)

> **[TRIAL] 종료**: 2회차 회고(2026-07-04) 승인률 100%(3/3)·정합성 이탈 0으로 **확정(CONFIRMED)**. ADR-014 v1.0 정식화, 규칙 상시 적용. 아래는 실험 기간 계측 기록 (상시 축적은 중단).

- [2026-07-03] 회고/규칙 진화 발의 — 판단: 회고 트리거를 "다음 세션 시작"→"발의 5건/실사이클 3회"로 조정 / 근거: 도입 세션(PR #34) 직후 종료로 회고 시점 발의 0건 공회전 / 대안: 트리거 유지 시 매 세션 빈 회고 반복 / 응답: 승인(실험 연장)
- [2026-07-03] BACKLOG 발의 — 판단: 미추적 AGENTS.md를 버전 관리에 편입 / 근거: 세션 시작부터 `?? AGENTS.md` 방치, .gitignore에도 없어 매 세션 노이즈 + 크로스툴 지침 drift 위험 / 대안: gitignore 로컬전용(공유 포기) 또는 방치(노이즈 지속) / 응답: 승인(커밋)
- [2026-07-04] 설계 자문 발의 — 판단: useListFilters를 Approach A(콜백 기반, scaffolding만 흡수)로 구현 / 근거: 5페이지 차이는 검색필드+술어뿐, reset·activeCount는 schema 도출 가능, tax/warranty 특수 술어는 선언형에 안 접힘 / 대안: Approach B 완전 선언형(가독성 ↓ 반려), 1페이지 PoC 우선 / 응답: 승인(A 전면 구현)

## 회고

→ **`.claude/RETROSPECTIVE.md`** 참조.
