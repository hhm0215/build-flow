# BuildFlow 백로그

> 요구사항 / 문제 / 다음 작업의 **단일 진실원**.
> 우선순위 그룹(P0/P1/P2) 안에서 위에서 아래 순으로 진행. 글로벌 번호 없음 — 추가/제거 시 재매김 불필요.
> 작업 시작 시 여기서 선택, 완료 시 PROGRESS.md "완료된 작업"으로 이동 후 여기서 제거.
>
> 새 항목 형식:
> - **제목** (헤딩)
>   - 배경/이유
>   - 산출물
>   - 관련 파일
>   - 예상 규모: S/M/L
>   - 상태: TODO / IN_PROGRESS / BLOCKED

---

## P0 — 다음 1~2 작업

### 실사용 UI 라이프사이클 연결
- **배경**: 현장 생성·거래처 등록·견적 확정 첫 단계는 연결됐지만 현장·견적 수정/삭제 및 여러 도메인의 수정/삭제가 화면에 연결되지 않았다. 화면만으로 전체 입력·보정 흐름을 완주하려면 후속 동선이 필요하다.
- **산출물**: 현장 수정, 견적 수정/삭제, 매입·세금계산서 수정/삭제, 보증보험 OCR 실패 보정/수정의 화면 동선과 오류 처리; MSW·실서버 계약 테스트
- **관련 파일**: `frontend/src/pages`, `frontend/src/api`, `frontend/src/mocks`, 도메인별 컨트롤러
- **예상 규모**: L (파일럿에 필요한 최소 동선부터 분할 가능)
- **상태**: IN_PROGRESS (현장 생성·거래처 등록·견적 확정·현장 수정·DRAFT 견적 수정/삭제·보증보험 OCR 실패 수동 보정/수정·세금계산서 입금 확인 계약/오류 처리 완료. 확정본 삭제 정책 사용자 결정 대기, 매입·세금계산서 수정/삭제 동선 남음. 매입 UI와 Kafka 신뢰성 보강의 선후 순서 사용자 확인 중)

### Kafka 손익 집계 신뢰성 보강
- **배경**: `eventId`는 발행하지만 소비자가 중복 처리 여부를 확인하지 않고, 소비자 예외를 로그 후 삼켜 메시지 재처리가 보장되지 않는다. 금액 누적형 `SiteProfit`은 동시 갱신과 발행-DB 커밋 경계에도 취약하다. 문서상 site-service가 소비한다는 `tax.registered`·`tax.payment.confirmed`는 실제 소비자가 없다.
- **산출물**: 소비자 멱등성, 재시도/DLT, 발행-커밋 일관성(outbox 권장; after-commit은 단기 완화), 동시 갱신 제어, 세금 이벤트 집계 계약 정리, 중복·실패·순서 역전 회귀 테스트
- **관련 파일**: `site-service/.../KafkaConsumerService.java`, `notification-service/.../KafkaConsumerService.java`, 이벤트 발행 서비스, `SiteProfit`
- **예상 규모**: L (설계 후 단계 분할)
- **상태**: TODO

---

## P1 — 중기

### 실데이터용 DB 스키마 마이그레이션 체계
- **배경**: DB 사용 7개 서비스 모두 `ddl-auto: update`이며 버전 관리형 마이그레이션 도구가 없다. 인증 스키마 전환과 실데이터 보존 전에 명시적 백업·복구·검증 경로가 필요하다.
- **산출물**: 서비스별 버전 마이그레이션 전략, 인증 스키마 전환 스크립트, 백업·복구 검증, 운영 프로파일의 `ddl-auto` 정책
- **관련 파일**: 7개 서비스 `application*.yml`, 각 서비스 DB 스키마, 배포 문서
- **예상 규모**: M~L
- **상태**: TODO

### API 명세를 실제 계약과 동기화
- **배경**: `docs/API_SPEC.md`에 존재하지 않는 `/api/v1/specifications/**`, `/tax-invoices/**`, `/payments/**`, `/chat/sessions/**` 등이 기재되어 있고 실제 `/estimates/parse`, `/dashboard/stats`, `/chat/stream` 등은 빠져 있다. 오류 래퍼 형태도 구현과 다르다.
- **산출물**: 컨트롤러/Gateway/프론트 호출 기준 엔드포인트·요청/응답·인증 표 갱신, 미구현 제안 API는 계획으로 명확히 분리
- **관련 파일**: `docs/API_SPEC.md`, `docs/ARCHITECTURE.md`, `gateway-server`, 각 서비스 컨트롤러
- **예상 규모**: M
- **상태**: TODO

### 로컬 서버 배포 보안 하드닝
- **배경**: 개발 Compose는 loopback 전용으로 제한했지만 향후 LAN 공개 시 프론트 리버스 프록시만 노출하고 Gateway·8081~8087·MySQL·Redis·Kafka 직접 접근을 차단해야 함
- **산출물**: 로컬 서버용 Compose override, 외부 노출 포트 정책, Redis/DB 보안, 백업·복구 및 방화벽 런북
- **관련 파일**: `docker-compose.yml`, `docker-compose.app.yml`, 신규 배포 override, `docs/`
- **예상 규모**: M
- **상태**: TODO

### 실데이터 파일럿 온보딩 (현장 1개 끝까지 입력)
- **배경**: 실사용 UI 동선과 금액 집계 신뢰성을 먼저 보강한 뒤, 추측 대신 실데이터에서 다음 요구사항 도출 — USB의 실제 현장 자료 1개를 거래처→현장→견적(공내역서 파싱)→매입→세금계산서→보증보험까지 실제로 입력
- **산출물**: 온보딩 중 드러난 갭 목록(입력 필드 부족, 현장별 문서함(원본 파일 보관 — 현재 warranty PDF만 저장됨) 필요성, 과거 현장 일괄 입력 UX 등)을 BACKLOG 신규 항목으로 전환
- **예상 규모**: S~M (사용자 참여 필요 — USB 자료 준비)
- **상태**: TODO

---

## P2 — 인프라/툴링

### chat-service Phase 3 (Phase 1·2 완료, 견고화/확장)
- **Phase 3**: 도구 확장(estimate/purchase by site) + C 폴백 라우터 + tool_call_id 견고화 — 예상 M
- **추가(Phase 2 리뷰/검증에서 이관)**: 모델 실시간 토큰 스트리밍(Ollama stream:true+tools 단일 콜 — 현재는 선택 라운드 답변 조각 relay), 프론트 per-token 전체 리스트 리렌더 최적화, buildMessages 이력 tail 쿼리(LIMIT)
- 7b 토큰 잡음("마argin율") 2026-07-04·07-15 반복 실측 → 견고화 근거

### ADR-003 / 아키텍처 문서 ↔ 실구현 drift 정정
- **배경**: 2026-07-22 실측 — `docs/DECISIONS.md` ADR-003이 "Redis 3용도(캐시 + JWT 블랙리스트 + Redisson 분산락)"로 기록하고 면접 답변 스크립트까지 포함하나, `Redisson`/`RLock`/`@Cacheable`/`CacheManager` 사용처 **0건**. 실사용은 JWT 블랙리스트 + refresh 토큰 + chat 세션뿐. 같은 계열로 CLAUDE.md/AGENTS.md의 "서비스 간 동기 통신 = OpenFeign"도 실제로는 chat-service 2곳(SiteClient/TaxClient)만
- **선택지**: (a) 문서를 현재 구현에 맞게 정정 (b) ADR대로 Cache Aside·분산락을 실제 구현 후 문서 유지
- **산출물**: ADR-003 v2(정정 또는 구현 반영) + CLAUDE.md/AGENTS.md 통신 방식 표기 정렬
- **관련 파일**: `docs/DECISIONS.md`, `CLAUDE.md`, `AGENTS.md`, `auth-service/.../RedisConfig.java`
- **예상 규모**: S(문서 정정) / M(구현)
- **상태**: TODO

### Docker Config Client 중복 import 경고 정리
- **배경**: 2026-09-15 풀 스택 로그에서 Docker용 Config Server 연결은 성공하지만 기본 `application.yml`의 `localhost:8888` import도 함께 평가되어 서비스마다 불필요한 connection-refused 경고가 발생
- **산출물**: 로컬 기본값과 Docker URL을 단일 placeholder/property 경로로 통합하고 7개 비즈니스 서비스 시작 로그에서 중복 localhost 요청 제거
- **관련 파일**: 7개 서비스 `application.yml`, `application-docker.yml`, `docker-compose.app.yml`
- **예상 규모**: S
- **상태**: TODO

### 프론트 프로덕션 번들 분할
- **배경**: `bun run build` 결과 메인 JS chunk가 약 1.36MB(gzip 약 430KB)로 Vite 500KB 경고 발생
- **산출물**: 페이지 lazy loading 또는 `manualChunks` 적용, 기존 라우팅·SSE 동작 회귀 없이 경고 해소/합리적 임계값 문서화
- **관련 파일**: `frontend/src/App.tsx`, `frontend/vite.config.ts`
- **예상 규모**: S~M
- **상태**: TODO

### 테스트 커버리지 확장 (파운데이션은 2026-07-04 완료)
- 파일럿(chat ToolExecutor/ToolCatalog, useListFilters) 완료 → 나머지 순수 로직으로 확장
- 후보: ProfitService 마진계산, estimate OllamaService JSON추출, tax 미수금 계산, 프론트 다른 훅
- **예상 규모**: S~M (점진적)

---

## 보류 / 결정 대기

(없음)

---

## 변경 이력

| 날짜 | 작업 |
|------|------|
| 2026-06-10 | 초기 작성 — PROGRESS.md "다음 작업" 섹션에서 이관 |
| 2026-06-10 | /review 후속 fix 2건 완료 → 제거 |
| 2026-06-10 | PR 생성 자동화 + main 보호 룰 도입 (BACKLOG 항목 외 메타 작업) |
| 2026-06-10 | 글로벌 번호 제거 (#1, #2 같은 번호 매김 폐기, 헤딩만 사용) — drift 정렬 |
| 2026-06-13 | PR 머지 자동화 도입 (BACKLOG 항목 외 메타 작업) — ADR-011 v2 |
| 2026-06-14 | docker-compose obsolete `version:` 제거 + `.env.example` 신설 (윈도우 이동 전 정리) |
| 2026-06-18 | warranty 만료 스케줄러 + Kafka 발행 Phase 1 완료 → P0 항목을 Phase 2(OCR)로 갱신 |
| 2026-06-18 | warranty PDF OCR Phase 2 완료 → P0 항목을 프론트 업로드/상태 표시로 갱신 |
| 2026-06-18 | ADR-012 위임 모드 정식 도입 (BACKLOG 항목 외 메타 작업) |
| 2026-06-18 | 프론트 PDF 업로드 + OCR 상태 표시 완료 → P0 비움. P1에 Warranty 타입 일치 신규 등록 |
| 2026-06-21 | 위임 모드 5 사이클 일괄 처리 — P1 4건 완료(InputNumber/Warranty optional/SiteSelect/필터 명명+추론), useListFilters는 별도 분리 |
| 2026-06-21 | 백엔드 DefectWarranty.coverageAmount 필드 추가 완료 — 사이클 2 짝 완성 (frontend↔backend 일치) |
| 2026-06-22 | /code-review로 critical+high 7건 발견 → 사이클 A 핫픽스 완료. MEDIUM 7건은 P1 신규 등록 |
| 2026-06-22 | ADR-013 자동 코드 리뷰 5.5단계 정식 도입 (BACKLOG 항목 외 메타 작업) |
| 2026-06-22 | P1 MEDIUM 6건 fix 완료 + 5.5단계 자동 리뷰 HIGH 1건 즉시 fix. MEDIUM 2건/LOW 2건 분리 등록 |
| 2026-06-26 | P2 LOW 2건 정리 — parser 다중 라벨 순회 + isExpiringSoon dead 메서드 제거 |
| 2026-07-01 | P1 MEDIUM 2건(parser 거리 제약 + update Optional 3-state) 완료 + 5.5 리뷰 HIGH 2건(greedy group 캡처 + gap 임계값) 즉시 fix |
| 2026-07-01 | ADR-014 [TRIAL] 능동 발의 실험 도입 — 다음 세션 회고 대상, P0에 피드백 항목 등록 |
| 2026-07-03 | ADR-014 [TRIAL] 1회차 회고(발의 0건) → 실험 연장, 회고 트리거 조정 (PR #35) |
| 2026-07-03 | BACKLOG 발의(실험 데이터 #2) → 미추적 AGENTS.md 버전 관리 편입 완료 |
| 2026-07-04 | P1 useListFilters 추상화 완료 — 5페이지 필터 훅 통합, 5.5 리뷰 회귀 1건 자가 fix (설계 자문 발의 #3) |
| 2026-07-04 | Gradle wrapper(8.10) 생성·커밋 완료 — 컴파일 검증은 JDK 17 필요로 후속 분리 |
| 2026-07-04 | openjdk@17 설치 + `./gradlew compileJava` 9개 서비스 컴파일 통과 — 백엔드 컴파일 검증 최초 성공, P2 닫음 |
| 2026-07-04 | 능동 발의 실험 2회차 회고 → **확정(CONFIRMED)** — ADR-014 v1.0 정식화, [TRIAL] 종료, P0 회고 항목 제거 |
| 2026-07-04 | chat-service Phase 1 완료 — 툴콜 에이전트(아키텍처 A), 도구 4종, 이력 저장. P2를 Phase 2~3로 갱신 |
| 2026-07-04 | 테스트 파운데이션 완료 — CI(GitHub Actions) + Vitest(프론트) + 백엔드 파일럿 단위 테스트, ADR-015. chat Phase 2를 P0로 |
| 2026-07-04 | chat Phase 1 런타임 검증 통과(툴콜+Feign+세션 실동작) + 잠복 버그 3건 fix(kafka 이미지 소멸/JDBC PublicKeyRetrieval/init chat 스키마) |
| 2026-07-15 | chat Phase 2 완료(SSE 스트리밍+채팅 패널) — 5.5 리뷰 10건 전부 fix, Gateway 경유 런타임 검증 통과. P0 비움, P1에 실데이터 파일럿 온보딩 등록, Phase 3에 실시간 스트리밍 이관 |
| 2026-07-22 | 외부 서류 작업 중 근거 실측에서 문서↔실구현 drift 발견 → P2에 "ADR-003 / 아키텍처 문서 drift 정정" 등록 |
| 2026-09-15 | 실데이터 파일럿 선행 런타임 안정화 완료 → notification DB/volume, site Ollama, Zipkin, managed network, Bun/context, ignore/검증 규칙 정렬 |
| 2026-09-18 | Windows fresh clone 개발 준비 완료 → PowerShell helper, 비밀값 로컬 생성, README/셋업, loopback Compose, Windows CI 정렬 |
| 2026-09-19 | 코드·문서 재점검: 실사용 UI, Kafka 집계 신뢰성, DB 마이그레이션, API 명세 정합성을 신규 작업으로 등록 |
| 2026-09-19 | 단일 관리자 인증 전환 및 현장 생성 첫 단계 검증 완료 — 인증 항목 제거, UI 후속은 P0 유지 |
| 2026-09-19 | 견적 확정 UI·초안 손익 제외 기준 완료 — UI 후속과 Kafka 집계 신뢰성 P0 유지 |
| 2026-09-19 | 거래처 생성→현장 자동 선택 동선 완료 — UI 수정/삭제 후속 P0 유지 |
| 2026-09-19 | GitHub 위임 규칙 정렬 및 PR #49 CI/SHA 검증 후 merge 완료 — 운영 메타 작업 제거 |
| 2026-09-19 | 현장 수정 UI PR #50 CI/SHA 검증 후 merge 완료 — 견적 후속 P0 유지 |
| 2026-09-20 | DRAFT 견적 수정/삭제 PR #51 CI/SHA 검증 후 merge 완료 — 확정본 삭제 정책 및 매입/Kafka 선후 결정 대기 |
| 2026-09-20 | 보증보험 OCR 실패 수동 보정/수정 PR #52 CI/SHA 검증 후 merge 완료 — UI 라이프사이클 P0의 매입·세금계산서 후속 유지 |
| 2026-09-20 | 세금계산서 입금 확인의 필수 요청 본문·오류 처리 수정 — 매입·세금계산서 수정/삭제와 Kafka 신뢰성 P0 유지 |
