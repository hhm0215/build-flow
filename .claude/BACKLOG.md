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

(없음 — 실사용 UI 라이프사이클 연결 완료. 다음 우선순위는 P1의 실데이터용 DB 스키마 마이그레이션 체계다.)

---

## P1 — 중기

### VPS 비공개 파일럿 배포
- **배경**: 기존 OpenClaw 템플릿 VPS를 초기화하고 BuildFlow를 올리되, 2 vCPU/8 GiB 용량과 주간 백업만으로 공개 실사용을 가정할 수 없다. 사용자가 현재 VPS에 보존할 데이터·서비스가 없다고 확인했다.
- **산출물**: Plain OS Ubuntu 24.04 전환, 새 SSH 지문·전용 계정 검증, Docker/Compose 설치, loopback+SSH 터널 파일럿, 순차 빌드·CRUD/PDF·재부팅 복구 검증, DB+업로드 파일 외부 백업·격리 복원 게이트
- **현재 진행**: Plain OS Ubuntu 24.04 LTS 변경·새 host key 확인, 비root 배포 계정 키 접속, Docker 공식 apt 설치, root 소유 코드 배치 완료. 서버 전용 비밀값(`0600`) 생성, 순차 빌드로 앱 이미지 11개 성공, 컨테이너 15개 기동. 8081~8087 health `UP`, 프론트 200·비인증 현장 API 401·초기 재시작/OOM 0·비loopback published port 0 확인. 사용자가 관리자 계정을 직접 생성했고, SSH 터널의 웹 로그인·UI 로그아웃, 임시 현장 생성/조회/삭제·동일 토큰 재사용 401까지 확인했다. 전체 업무 CRUD/PDF·재부팅 복구 및 DB+업로드 파일 외부 백업/격리 복원은 미완료. 로컬 Docker Engine은 꺼져 있다. AI 모델과 도메인 공개는 범위 밖.
- **관련 파일**: `docker-compose.vps.yml`, `docs/VPS_PRIVATE_PILOT.md`, `.claude/plans/2026-10-01-vps-private-pilot.md`
- **예상 규모**: M~L
- **상태**: IN_PROGRESS

### 실데이터용 DB 스키마 마이그레이션 체계
- **배경**: 인증 서비스는 `schema.sql` + `ddl-auto: validate`, 나머지 DB 사용 6개 서비스는 `ddl-auto: update`이며 버전 관리형 마이그레이션 도구가 없다. 인증 스키마 전환과 실데이터 보존 전에 명시적 백업·복구·검증 경로가 필요하다.
- **단계**: A) 7개 스키마 백업·오프라인 무결성 검증 → B) 격리 임시 volume 복원·관리자 로그인 검증 → C) auth Flyway 파일럿 → D) chat/estimate → purchase/tax → site/notification 순차 전환 → E) runtime DB 사용자 최소 권한 분리
- **안전 기준**: 실제 MySQL `SHOW CREATE TABLE`/no-data dump 전에는 V1을 추정 작성하지 않음, 기존 DB는 백업·복원 성공 후 명시적 일회성 baseline, 상시 `baseline-on-migrate=true` 및 Flyway+Hibernate `update` 동시 사용 금지
- **현재 진행**: Phase A 완료. Phase B의 격리 임시 volume 실제 복원, manifest/행 수/digest/`CHECK TABLE`, auth `ddl-auto=validate`와 전후 DB 불변까지 통과했다. 관리자 비밀번호를 저장하지 않는 대화형 로그인·로그아웃 검증만 남았다.
- **산출물**: 서비스별 버전 마이그레이션 전략, 인증 스키마 전환 스크립트, 백업·격리 복구 검증, 운영 프로파일의 `ddl-auto` 정책
- **관련 파일**: 7개 서비스 `application*.yml`, 각 서비스 DB 스키마, 배포 문서
- **예상 규모**: M~L
- **상태**: IN_PROGRESS

### VPS 외부 HTTPS 테스트 접속
- **배경**: 사용자가 모바일·외부 PC에서 앱 설치 없이 VPS를 확인하려 한다. 현재 앱 포트는 모두 loopback이며 VPS 기본 호스트명이 공인 IP를 가리킨다. 자체 도메인은 아직 없다.
- **범위**: 비공개 파일럿의 **테스트 데이터만** 외부 HTTPS에서 볼 수 있게 한다. 실데이터 운영 승격·CI/CD 자동 배포와 구분한다.
- **산출물**: 공식 이미지 기반 HTTPS reverse proxy, 80→443 리다이렉트, 로그인 시도 제한, Host/CORS·프록시 헤더 검증, 80/443 외 앱·DB 포트 차단 재확인, 공인 인증서·외부망 로그인/401 스모크. 기본 호스트명 인증서 발급이 불가하면 임의 우회 없이 사용자 소유 도메인을 준비한다.
- **안전 기준**: `docs/SECURITY_OPERATIONS.md`의 공개 게이트를 적용한다. 특히 UI 로그아웃 전 토큰 재사용 401, 실제 TLS/Host/429/CORS·외부 포트 확인 전 개방 완료로 판정하지 않는다. 인증서/로그인/포트 중 하나라도 실패하면 공개를 중단하고 SSH 터널 파일럿으로 복귀한다. 실제 업무 데이터는 DB 마이그레이션·DB+업로드 파일 외부 백업/격리 복원 게이트 전 입력하지 않는다.
- **관련 파일**: 신규 공개 Compose override와 reverse proxy 설정, `frontend/nginx.conf`, `docs/VPS_PRIVATE_PILOT.md`
- **예상 규모**: M
- **상태**: IN_PROGRESS

### 보안·인프라 반복 점검 및 공개 전 하드닝
- **배경**: 사용자가 공개 전 체크리스트와 역할별 반복 유지보수를 요청했다. UI 로그아웃 토큰 미폐기와 Compose 검증 우회는 수정·비공개 실측했고, SSH root/password 허용·커널 패치 대기는 남았다.
- **산출물**: `AGENTS.md` 하네스·역할 지침·`docs/SECURITY_OPERATIONS.md`, 코드/CI 회귀, 읽기 전용 정기 점검 자동화와 변화 시 알림. SSH 정책 변경·패치/재부팅은 Web Console 및 별도 키 재접속 복구 확인 후 수행.
- **경계**: 모니터가 서버 변경·백업 복원·포트 개방·운영 배포를 무인 실행하지 않는다. 이메일/Discord 연동은 수신 채널·secret 설정 후 별도 실측.
- **관련 파일**: `AGENTS.md`, `.claude/agents/`, `frontend`, `scripts/vps/`, `docs/SECURITY_OPERATIONS.md`
- **예상 규모**: M
- **상태**: IN_PROGRESS

### 단일 VPS 운영 릴리스 CI/CD
- **배경**: ADR-018에 따라 로컬 `develop` 개발·GitHub CI와 VPS의 `main` 운영 릴리스를 분리한다. 현재 CI는 테스트·빌드를 검증하지만 VPS 자동 배포와 안전한 승격 경로는 없다.
- **선행조건**: VPS 파일럿 CRUD/PDF·재부팅 회귀, 버전 관리형 DB 마이그레이션, DB+업로드 파일 외부 백업·격리 복원 및 최소 권한 DB 계정 완료. 그 전에는 운영 DB 대상 자동 배포를 켜지 않는다.
- **산출물**: CI 성공·PR/SHA 검증 후 릴리스 고정, 배포 전 백업/복원 가능성·마이그레이션 점검, 순차 배포·헬스/스모크·실패 시 중단과 복귀 절차. GitHub에서 VPS로의 배포 인증은 최소 권한과 비밀값 관리 기준을 별도 설계한다. 사용자가 빌드·테스트 실패와 운영 배포 성공/실패를 실제 알림으로 받도록 구성한다. 1인 운영 기본안은 GitHub Actions 실패 메일 + Discord 전용 채널 웹훅이며 Slack은 보류한다. 웹훅 URL은 GitHub Environment secret에만 저장하고 알림에는 상태·커밋·실행 링크만 포함한다.
- **관련 파일**: `.github/workflows/`, `docker-compose.vps.yml`, `docs/VPS_PRIVATE_PILOT.md`, `docs/DECISIONS.md`
- **예상 규모**: M
- **상태**: TODO

### 문서 코어 Stage 1 — 원본 보존·가져오기·관계 검토 기반
- **배경**: USB 실제 자료는 폴더·파일명·날짜만으로 수정본, 추가공사, 다른 현장을 안전하게 구분할 수 없다. 실데이터 입력 전에 원본과 해석을 분리하고 사용자의 확정 판단을 축적할 기반이 필요하다. 장기 원칙은 `docs/PRODUCT_VISION.md`와 ADR-017을 따른다.
- **선행조건**: 실데이터용 DB 마이그레이션·백업/복구 체계를 먼저 완료하고 파일 저장소와 DB를 함께 복원할 수 있어야 한다.
- **산출물**: `DocumentBlob`·`DocumentSource`·`ImportBatch/Observation`·`ExtractionRun` 분리, 읽기 전용 가져오기 배치, 임시 복사→원본/사본 SHA-256 대조→원자적 승격, 견적 작업/버전/관계 후보, append-only 검토 결정, `수정본/추가공사/다른 현장/보류` 검토함, 작업별 회계 유효 버전 0..1 제약
- **보존·복구**: 완료된 배치 checkpoint 기준으로 가져오기·관계 확정을 동결하고 DB가 참조하는 Blob을 함께 백업. 내부 `storageKey` 기반 백업 manifest와 휴대용 `packageRelativePath` 기반 내보내기를 분리하며, 원본·출처·추출 이력·관계 후보·확정/번복 결정·스키마 버전·감사 이력을 다른 물리 대상과 빈 환경에 복원해 체크섬·참조 ID·회계 유효 버전을 검증
- **보안 기준**: 허용 형식·크기 제한, path traversal·symlink 차단, 매크로/실행 파일 비실행, 원문·추출 결과의 민감정보 로그 금지, 관리 저장소 로컬 ACL과 백업 보호
- **범위 제한**: 첫 파일럿은 로컬 설정으로 지정한 읽기 전용 소스의 `견적서/2026` `.xlsx`로 제한. 실제 절대경로와 업체명은 Git 추적 파일에 기록하지 않는다. `.xls`·PDF·범용 스키마 편집기·벡터 DB·멀티테넌시는 후속 단계에서 필요를 검증한 뒤 결정
- **필수 회귀**: 동일 바이트/다른 출처, 같은 이름/다른 바이트, 복사 중 변경·중단, 수정본 3개/유효 버전 1개, 추가공사 별도 반영, 제안 거절·결정 번복, 빈 환경 체크섬·참조 무결성 복원
- **관련 파일**: `estimate-service`, `frontend`, 신규 파일 저장/가져오기 모듈, `docs/ERD.md`, `docs/API_SPEC.md`
- **예상 규모**: L (원본 카탈로그 → 관계 검토 → Assistant 연동으로 분할)
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
- **배경**: 실사용 UI 동선과 금액 집계 신뢰성을 먼저 보강한 뒤, 추측 대신 실데이터에서 다음 요구사항 도출 — USB의 실제 현장 자료 1개를 원본 보존·관계 검토부터 거래처→현장→견적(공내역서 파싱)→매입→세금계산서→보증보험까지 실제로 입력
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
| 2026-09-20 | 세금계산서 입금 확인의 필수 요청 본문·오류 처리 PR #53 CI/SHA 검증 후 merge 완료 — 매입·세금계산서 수정/삭제와 Kafka 신뢰성 P0 유지 |
| 2026-09-20 | 사용자 결정: Kafka 신뢰성 우선, 확정 견적 삭제 금지, 입금 확인된 세금계산서 수정·삭제 금지. Kafka를 소비자 보호/outbox/순서 안전 3단계로 분할 |
| 2026-09-20 | Kafka 소비자 보호 Phase 1 PR #54 CI/SHA 검증 후 merge 완료 — outbox/매입 순서 안전 P0 유지 |
| 2026-09-23 | 제품 비전 v2 및 ADR-017 PR #56 병합 — 건설 실사용을 첫 도메인으로 유지하고 P1에 원본 보존·가져오기·관계 검토 기반 등록 |
| 2026-09-24 | 세금계산서 금액 검증·수정/삭제 UI까지 완료해 실사용 UI 라이프사이클 P0 종료 — 다음은 DB 마이그레이션 체계 |
