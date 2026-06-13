# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 개요

건설·시공 소규모 업체(1~10인)를 위한 현장 업무 관리 MSA 플랫폼.
현장별 문서(공내역서, 견적서, 세금계산서, 하자보증보험)를 묶어서 관리하고,
AI가 요약 대시보드를 생성하여 마진/손익을 한눈에 파악.

1인 개발 + 실사용(혜민 관리, 아버지 열람). MSA 구조로 백엔드 포트폴리오 겸용.

## 빌드 & 실행

### 인프라 (Docker)

```bash
docker compose up -d                    # MySQL, Redis, Kafka, Ollama, Zipkin
docker compose down                     # 종료 (docker stop 금지)

# 전체 컨테이너 기동 (앱 포함)
docker compose -f docker-compose.yml -f docker-compose.app.yml up -d
```

### Gradle (멀티프로젝트)

```bash
# 루트에서 전체 빌드
./gradlew build

# 단일 서비스 빌드/실행/테스트
./gradlew :auth-service:bootRun
./gradlew :estimate-service:test
./gradlew :site-service:bootJar

# 서비스 디렉토리에서 직접 실행도 가능
cd estimate-service && ./gradlew bootRun
```

**기동 순서 필수**: Eureka(8761) → Config(8888) → Gateway(8080) → 나머지 서비스

### 프론트엔드

```bash
cd frontend
bun install             # 패키지 설치 (bun 사용)
bun run dev             # Vite dev server
bun run build           # TypeScript + Vite 프로덕션 빌드
bun run lint            # ESLint (max-warnings: 0)
bun run preview         # 빌드 결과 미리보기
```

## 아키텍처

모노레포, 마이크로서비스 6개 + 인프라 서비스 3개.

- Java 17, Spring Boot 3.3.4, Spring Cloud 2023.0.3, Gradle (Groovy)
- React 18 + TypeScript + Vite + Ant Design (dark theme) + Zustand + TanStack Query
- 서비스 간 통신: 동기 = OpenFeign, 비동기 = Kafka (KRaft)
- Database per Service — MySQL 1인스턴스, 서비스별 스키마 분리

### 서비스 포트 & DB 스키마

| 서비스 | 포트 | DB 스키마 | 비고 |
|--------|------|-----------|------|
| eureka-server | 8761 | - | 서비스 디스커버리 |
| config-server | 8888 | - | 중앙 설정 관리 |
| gateway-server | 8080 | - | API Gateway, JWT 검증 |
| auth-service | 8081 | buildflow_auth | JWT + Redis 블랙리스트 |
| estimate-service | 8082 | buildflow_estimate | 견적서 CRUD + 공내역서 AI 파싱 |
| site-service | 8083 | buildflow_site | 현장 관리 + 손익 + AI 대시보드 |
| purchase-service | 8084 | buildflow_purchase | 매입 관리 |
| tax-service | 8085 | buildflow_tax | 세금계산서 + 미수금 |
| notification-service | 8086 | - | Kafka 구독 알림 + 하자보증보험 OCR |
| frontend | 3000 | - | Nginx (프로덕션), Vite (개발) |

### 인프라 포트

| 서비스 | 포트 |
|--------|------|
| MySQL 8.0 | 3306 |
| Redis 7.2 | 6379 |
| Kafka | 9094 (외부), 9092 (내부) |
| Ollama | 11434 |
| Zipkin | 9411 |

### Gateway 라우팅

```
/api/v1/auth/**       → auth-service (인증 필터 없음)
/api/v1/estimates/**  → estimate-service (AuthorizationHeaderFilter)
/api/v1/sites/**      → site-service (AuthorizationHeaderFilter)
/api/v1/purchases/**  → purchase-service (AuthorizationHeaderFilter)
/api/v1/taxes/**      → tax-service (AuthorizationHeaderFilter)
```

### Kafka 이벤트 흐름

핵심 흐름만 요약 — 전체 토픽 목록은 `docs/ARCHITECTURE.md` "2.2 비동기 통신" 참조.

- `estimate.parsed` / `estimate.deleted`: estimate-service → site-service (손익 재계산)
- `purchase.registered` / `purchase.updated` / `purchase.deleted`: purchase-service → site-service
- `tax.registered` / `tax.payment.confirmed`: tax-service → site-service (미수금 갱신)
- notification-service: 모든 토픽 구독 → 인앱 알림

### 현장 문서 라이프사이클

```
수주: 공내역서 수신 → AI 파싱 → 견적서 작성 → 현장 생성
시공: 자재 매입 → 세금계산서 → 추가공사 견적서
정산: 미수금 확인 → 입금 처리 → 손익 확정
완료: 하자보증보험 등록(PDF+OCR) → 현장 마감
```

### 손익 계산

```
마진 = SUM(견적서 금액) - SUM(매입 금액)
마진율 = 마진 / SUM(견적서 금액) × 100
미수금 = SUM(매출 세금계산서) - SUM(입금 확인 금액)
```

## 코드 컨벤션

### Java / Spring Boot

- 패키지: `com.buildflow.{service}.{domain}`
- DTO 분리 필수 — Entity 직접 반환 금지. XxxRequest/XxxResponse 사용
- JPA 중심 — MyBatis 금지
- 예외: GlobalExceptionHandler + 커스텀 예외
- API 응답: `ApiResponse<T>` 래퍼 (`{ success, data, error }`)
- API 경로: `/api/v1/{resource}`
- Lombok: @Getter, @Builder, @NoArgsConstructor(access = PROTECTED). **@Data 금지**
- 생성자 주입 — @RequiredArgsConstructor. @Autowired 금지

### Kafka

- 토픽: `{domain}.{event}` (estimate.parsed, purchase.registered)
- 메시지: `{ eventId, eventType, timestamp, payload }`
- Consumer: `{service}-group`
- eventId 기반 멱등성

### Redis

- 키: `{service}:{domain}:{id}`
- TTL 기본 5분, 분산락 TTL 30초

### React / TypeScript

- 함수형 + Hooks, Ant Design 우선
- axios 인스턴스 + JWT 인터셉터
- PascalCase.tsx (컴포넌트), camelCase.ts (유틸)
- 상태: Zustand (클라이언트), TanStack Query (서버)
- 애니메이션: Motion (framer-motion v11+)
- 아이콘: Lucide React
- API 모킹: MSW (frontend/src/mocks/)

## Git

- 브랜치: main ← develop
- 커밋: feat: / fix: / refactor: / docs: / chore:
- 항상 작업이 끝난 이후 커밋 메시지 추천 및 작업 단위별 커밋 타이밍 추천

### Git 규칙

- **IMPORTANT**: git 작업(브랜치 생성/삭제, 커밋, 푸시, 체크아웃) 실행 전 반드시 사용자 확인
  - "~할까?" = 질문 → 설명 후 대기
  - "~해줘" = 지시 → 실행
- **IMPORTANT**: force 옵션 절대 금지
  - `git add -f` 금지 — `.gitignore`가 거부하면 그 파일은 커밋 대상이 아님
  - `git push --force` 금지
  - `--no-verify` 금지
  - **git이 거부/경고하면 멈추고 사용자에게 보고. force로 우회하지 않는다**
- PR 생성: Claude가 `gh pr create`로 자동 생성 (사전 검증 체크리스트 통과 시 — "## 개발 워크플로우" 6단계)
- PR 머지: Claude가 `gh pr merge --merge`로 자동 (SHA 자동 검증 안전망 통과 시 — "## 개발 워크플로우" 7단계). 2026-06-13 ADR-011 v2로 결정 번복됨
- PR 본문은 텍스트로만 제공. 형식 고정: `## 변경 사항` / `## 상세` 두 섹션만 사용. 개요·테스트·참고 같은 추가 섹션 즉흥 도입 금지
- main 직접 push 금지. PR merge(Create a merge commit)로만 진행. Rebase and Merge 금지
- main 브랜치는 GitHub 보호 룰로 force-push/delete 차단됨 + PR 경로 강제
- `gh pr close`는 settings.local.json deny 유지 (실수로 PR 닫는 것 방지)

## 개발 워크플로우

모든 작업은 아래 사이클을 따른다. 각 단계는 산출물(문서)을 남긴다.

1. **백로그 확인** — `.claude/BACKLOG.md`에서 다음 항목 선택
2. **계획 작성** — `.claude/plans/YYYY-MM-DD-{slug}.md` 작성 (템플릿은 `.claude/plans/README.md`), 사용자 합의 후 구현 진입
3. **구현 + 커밋** — 작업 단위로 커밋, 사용자 사전 승인 규칙 준수
4. **결과 반영** (작업 종료 시 필수):
   - 계획 문서 "결과" 섹션 채움
   - BACKLOG.md에서 완료 항목 제거
   - PROGRESS.md "완료된 작업"에 한 줄 추가 + "다음 세션 진입점" git 상태 갱신
5. **회고** — 실패/허비/예상과 다름이 있었으면 `.claude/RETROSPECTIVE.md`에 1건 작성. **재발 방지 규칙을 CLAUDE.md / 메모리 / hooks 중 어디에 박았는지 반드시 명시**
6. **PR 생성** (develop ↔ main 차이가 PR 가치 있을 때):
   - `git fetch && git log origin/main..origin/develop`로 머지 대상 커밋 목록 확보
   - 로컬 `develop` SHA = `origin/develop` SHA 확인 (push 누락 검증 — PR #19 사고 가드레일)
   - 사용자에게 머지될 커밋 목록 요약 + "PR 생성 진행?" 텍스트 확인 1회
   - `gh pr create --base main --head develop --title "..." --body "..."` (본문은 ##변경 사항/##상세 두 섹션 고정)
   - 반환된 PR URL을 사용자에게 전달

7. **PR 자동 머지** (Commits SHA 검증 통과 시):
   - 머지 직전 `gh pr view <num> --json commits --jq '.commits[].oid' | sort` ↔ `git log --format='%H' origin/main..origin/develop | sort` 비교
   - SHA 일치 시 `gh pr merge <num> --merge` (Create a merge commit 방식)
   - **SHA 불일치 시 머지 중단** + 사용자에게 보고 (PR 생성 후 develop에 추가 push가 들어간 경우 — PR 본문 갱신 후 재검증 또는 PR 닫고 새로 생성)
   - 머지 성공 후 `git fetch origin`으로 로컬 origin/main 최신화

8. **결과 반영 (PR 머지 후)**:
   - PROGRESS.md "다음 세션 진입점"의 git 상태 → main 새 SHA, develop ↔ main 동기화 반영
   - 다음 BACKLOG 항목으로 진입 또는 세션 종료

- **IMPORTANT**: BACKLOG.md에 없는 작업은 먼저 BACKLOG.md에 추가 후 진행
- **IMPORTANT**: 4단계 "결과 반영"을 누락하면 다음 세션이 stale 상태로 시작
- **IMPORTANT**: RETROSPECTIVE에 회고만 적고 재발 방지 규칙을 어디에도 박지 않으면 같은 실패가 반복됨
- **IMPORTANT**: 6단계 PR 생성 시 `git fetch` 후 push 누락 검증을 건너뛰면 PR #19 사고 재발 위험
- **IMPORTANT**: 7단계 SHA 검증 안전망을 건너뛰면 의도와 다른 변경이 main에 머지됨. 활성 PR 있는 동안 develop에 추가 push 시 항상 본문 갱신 + 재검증

## 진행 상황 관리 (BACKLOG / PROGRESS / RETROSPECTIVE)

세 문서는 **역할이 분리**되어 있고 양방향 동기가 의무다. 한 곳에만 쓰면 drift.

### 각 문서 단일 책임
- `.claude/BACKLOG.md` — **다음 작업 우선순위 단일 진실원**. P0/P1/P2 그룹. 글로벌 번호 없음, 헤딩만
- `.claude/PROGRESS.md` — **완료 이력 + 현재 git 상태 + 다음 세션 진입점**. "다음 작업" 목록은 BACKLOG 참조로만 유지, 직접 쓰지 않음
- `.claude/RETROSPECTIVE.md` — **실패/사고 회고 + 재발 방지 규칙 + 반영 위치**

### 갱신 의무
- **IMPORTANT**: 세션 시작 시 `BACKLOG.md` + `PROGRESS.md`를 먼저 읽고 현재 상태 파악
- **IMPORTANT**: 작업 단위가 끝나 커밋을 만들 때 다음을 같은 작업 흐름에서 갱신
  - BACKLOG: 완료 항목 제거 (이동 아닌 제거)
  - PROGRESS "완료된 작업": 신규 항목 한 줄 추가
  - PROGRESS "다음 세션 진입점": git 상태 + PR 상태 현재 시점으로 정정
  - 회고가 필요한 경우 RETROSPECTIVE에 1건 추가, 재발 방지 규칙을 박은 위치 명시
- **IMPORTANT**: 외부 상태(PR 머지, `origin/main ↔ origin/develop`)는 PROGRESS만 믿지 말고 `git fetch` 후 실측으로 검증
- **IMPORTANT**: "다음 작업"을 PROGRESS에 직접 적지 않는다. drift 원천. BACKLOG에만 적고 PROGRESS는 참조
- 세션 종료 직전에도 위 갱신 한 번 더 — 다음 세션 stale 방지

## 주의사항

- **IMPORTANT**: 환경변수 하드코딩 절대 금지
- **IMPORTANT**: 서비스 간 직접 DB 접근 금지 → OpenFeign 또는 Kafka
- **IMPORTANT**: Entity에 @Data 금지
- **IMPORTANT**: 기동 순서 Eureka → Config → Gateway → 나머지
- docker compose down 사용 (docker stop 금지)

## compact 시 보존

- 현재 작업 서비스명 + 작업 내용
- 수정 파일 목록
- 실패 테스트/에러
- Kafka 이벤트 변경 사항
- 라이프사이클 중 현재 구현 단계
