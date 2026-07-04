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

### ✅ frontend — useListFilters 훅 추상화 (2026-07-04, P1 완료)
- 5페이지(estimate/purchase/tax/warranty/site) 필터 scaffolding을 `useListFilters` 훅으로 통합
- filterFn(검색+술어)만 페이지에 남기고 resetFilters·activeCount는 schema/groups에서 자동 도출
- 5.5 리뷰 자가 발견: `filtered` memo가 검색어 타이핑마다 재계산되던 디바운스 회귀 → `filterSig`+ref로 in-cycle fix
- 계획: `.claude/plans/2026-07-04-use-list-filters.md`

---

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

## 다음 세션 진입점 (2026-07-04 갱신, PR #42 머지 반영 — 테스트 파운데이션 + CI)

**현재 git 상태**:
- `origin/main` = `e26fdfd` (PR #42 머지 — 테스트 파운데이션 + CI, ADR-015)
- `origin/develop` = `1b58e05` — main과 PR 머지 커밋 하나 차이(정상)
- 직진 사이클: ... → #40(ADR-014 확정) → #41(chat Phase 1) → #42(테스트 파운데이션+CI)
- ⚠️ 이 진입점 갱신 커밋은 develop에만 존재 → 다음 PR에 번들됨(024a6b9 패턴)

**✅ CI 가동**: PR마다 GitHub Actions 자동 실행(백엔드 `./gradlew test` + 프론트 lint/test/build). PR #42가 첫 실행 green.
**로컬 테스트**: 백엔드 `JAVA_HOME=... ./gradlew test`, 프론트 `cd frontend && bun run test`.

**다음 작업**: P0 chat-service Phase 2 (SSE `SseEmitter` 스트리밍 + 프론트 채팅 UI). 런타임 검증(Ollama)은 사용자 환경 필요.

**BACKLOG 현황**: P0·P1 없음. **P2는 chat-service RAG(L, 새 서비스) 하나만** — 설계 자문부터.

**✅ 능동 발의 실험 종료**: 2회차 회고 완료 → **확정(CONFIRMED, ADR-014 v1.0)**. 승인률 100%(3/3)·정합성 이탈 0. [TRIAL] 딱지 제거, 규칙 상시 적용. 발의 로그 상시 축적 중단.
- 로컬 환경: gradle 9.6.1 + openjdk@17 설치됨, `JAVA_HOME=/opt/homebrew/opt/openjdk@17/...`로 `./gradlew` 실행 가능

**자동화 가이드**: `docs/AUTOMATION_GUIDE.md` (8단계 + 5.5단계 자동 코드 리뷰)

**대기 작업 (사용자 액션 필요)**:
- 통합 검증: `brew install gradle && cd notification-service && gradle wrapper --gradle-version 8.10` 후 `docker compose -f docker-compose.yml -f docker-compose.app.yml build notification-service && up -d`
- Docker Daemon 미실행 — Docker Desktop 기동 필요

**남은 BACKLOG**:
- P1: useListFilters 추상화 (설계 합의 필요)
- P2: Gradle wrapper, chat-service RAG

**다음 세션 첫 액션**:
1. `git fetch` 후 `git log --oneline origin/main..origin/develop` 실측
2. `.claude/BACKLOG.md`에서 다음 항목 선택 (useListFilters는 설계 문서부터, Gradle wrapper는 사용자 로컬 액션 필요)
3. 워크플로우 8단계 그대로 적용

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
