# 실데이터 파일럿 선행 런타임 안정화

- **시작일**: 2026-09-15
- **BACKLOG 항목**: P0 — 실데이터 파일럿 선행 런타임 안정화
- **예상 규모**: S~M
- **상태**: DONE

## 목표

실데이터 파일럿 전에 전체 Docker 경로에서 notification·warranty·dashboard를 포함한 핵심 서비스가 올바른 의존성과 영속성으로 동작하도록 정렬한다. 정적 검증, 빌드/테스트, 가능한 범위의 풀 스택 스모크 검증이 통과하면 완료로 본다.

## 배경 / 동기

2026-07-16 풀 도커 전환 작업은 구현·스모크·회고가 남아 있지만 작업 전 계획 문서가 없었다. 2026-09-15 재진입 점검에서 다음 누락을 발견했다.

- notification-service가 Docker 환경에서 MySQL 호스트·인증정보를 받지 않고 MySQL 준비도 기다리지 않는다.
- 보증보험 PDF는 컨테이너 로컬 경로에만 저장되어 컨테이너 재생성 시 유실될 수 있다.
- site-service AI 대시보드는 Docker 환경에서 호스트 Ollama 주소를 받지 않는다.
- 앱 Compose가 `buildflow-net`을 외부 네트워크로 덮어써 깨끗한 환경에서 전체 스택 단일 기동이 실패할 수 있다.
- 로컬/Docker Bun은 1.3 계열이지만 `packageManager`는 1.2.8이다.
- 프론트 Docker build context에 로컬 `node_modules`와 `dist`가 포함되어 전송량이 약 267MB까지 증가한다.
- 트레이싱 의존성이 있는 8개 앱이 Docker에서도 기본 `localhost:9411`로 span을 보내 Zipkin 컨테이너에 도달하지 못한다.
- IDE가 생성한 서비스별 `bin/`이 미추적 파일로 반복 노출된다.
- 2026-07-16 회고의 재발 방지 규칙이 CLAUDE.md에 아직 반영되지 않았다.

이 문서는 과거 작업 전에 작성된 것처럼 소급하지 않고, 현재 시점의 검증·보완 사이클을 기록한다.

## 접근법

1. `docker-compose.app.yml`에서 notification-service에 다른 JPA 서비스와 동일한 DB 환경 및 MySQL health 의존성을 추가한다.
2. `/app/uploads/warranties`를 named volume에 연결하고 `UPLOAD_DIR`을 컨테이너 절대 경로로 명시한다.
3. site-service에 호스트 Ollama URL을 주입한다.
4. 결합 Compose가 `buildflow-net`을 직접 생성·관리하도록 external override를 제거한다.
5. `packageManager`를 Docker/로컬과 같은 Bun 1.3 계열로 정렬한다.
6. 프론트 `.dockerignore`로 로컬 의존성과 빌드 결과물을 build context에서 제외한다.
7. 트레이싱을 사용하는 앱의 Zipkin endpoint를 Docker 서비스 DNS로 지정한다.
8. `.gitignore`에 서비스별 `bin/` 산출물을 제외하는 규칙을 추가한다. 기존 미추적 산출물은 삭제하지 않는다.
9. CLAUDE.md에 새 서비스 Docker 편입 3종, 프론트-백엔드 계약/MSW 동기화, 파일럿 풀 도커 검증 규칙을 반영한다.
10. 기존 사용자 변경인 `docs/DECISIONS.md` 포매팅은 수정하거나 이번 구현 커밋에 섞지 않는다.

## 산출물 체크리스트

- [x] notification-service DB 환경 및 MySQL health dependency
- [x] warranty 업로드 named volume
- [x] site-service Ollama URL
- [x] 전체 스택 Compose network 자동 생성
- [x] Bun 1.3 packageManager 선언
- [x] 프론트 Docker build context 최소화
- [x] Docker 앱 8개 Zipkin endpoint 정렬
- [x] 서비스별 `bin/` ignore 규칙
- [x] CLAUDE.md 재발 방지 규칙 반영
- [x] Compose 정적 검증
- [x] 백엔드 테스트 및 프론트 lint/test/build
- [x] 풀 도커 런타임 스모크 또는 불가 사유·대체 검증 기록
- [x] 자동 코드 리뷰 CRITICAL/HIGH 0

## 리스크 / 모르는 것

- Docker Desktop이 꺼져 있으면 런타임 검증을 위해 사용자의 로컬 앱 실행 권한이 필요할 수 있다.
- named volume 도입 후 기존 컨테이너 내부에만 있던 업로드 파일이 있다면 자동 이관되지 않는다. 현재 컨테이너 상태를 먼저 확인하고 데이터가 있으면 보존 절차를 별도로 세운다.
- `.env` 값은 출력하거나 문서에 기록하지 않는다.
- RBAC, Kafka 멱등성/재시도, Flyway는 별도 범위이며 이번 안정화에 섞지 않는다.

## 테스트 / 검증

- `docker compose -f docker-compose.yml -f docker-compose.app.yml config --quiet`
- notification-service 최종 Compose 설정에 DB/UPLOAD_DIR/volume/MySQL dependency가 포함되는지 확인
- site-service 최종 Compose 설정에 `OLLAMA_URL`이 포함되는지 확인
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew test --no-daemon`
- `cd frontend && bun run lint && bun run test && bun run build`
- Docker 사용 가능 시 전체 스택 기동 후 health, 로그인, notification, warranty, dashboard, chat 경로 스모크
- 변경 diff 정적 리뷰 및 `git diff --check`

## 결과 (작업 후 기록)

- notification-service에 MySQL 환경·health 의존성·`/app/uploads/warranties` named volume을 추가했다. 이전 중지 컨테이너의 업로드 경로는 별도 임시 백업 후 확인했으며 보존할 파일은 없었다.
- site-service Ollama 주소를 `host.docker.internal:11434`로 정렬했고 AI summary 실제 호출이 HTTP 200(19.08초)으로 완료됐다.
- `buildflow-net`의 `external` override를 제거해 결합 Compose가 네트워크를 직접 관리하게 했다.
- 런타임 로그에서 트레이싱 앱 8개가 `localhost:9411`로 span을 보내는 누락을 추가 발견해 Docker 서비스 DNS endpoint로 정렬했다. 이후 Zipkin `/api/v2/services`에서 8개 앱 모두 수집됨을 확인했다.
- Bun을 packageManager·Docker·CI 모두 1.3.11로 고정했다. 프론트 `.dockerignore` 추가로 build context가 약 266.69MB에서 5.63kB로 감소했다. 첫 push CI의 Node 20/deprecation annotation을 따라 공식 최신 major인 `checkout@v7`, `setup-java@v6`으로 갱신했다.
- 서비스별 IDE `bin/` 산출물을 삭제하지 않고 ignore 처리했으며, 2026-07-16 회고의 재발 방지 규칙 3개를 CLAUDE.md에 반영했다.
- 정적 검증: 결합 Compose config 정상, 앱 16개 구성, notification volume/DB, site Ollama, Zipkin endpoint 8개, managed network를 확인했다.
- 테스트: 백엔드 전체 Gradle 테스트 29개, 프론트 lint·Vitest 7개·프로덕션 build 모두 통과했다.
- 런타임: 16개 컨테이너 running, Eureka·Config·Gateway healthy, frontend/notification/warranty/dashboard API 모두 HTTP 200, notification Hikari/MySQL 시작 및 named volume 실제 마운트 확인.
- 첫 전체 이미지 빌드에서 Docker Hub의 Bun metadata 조회가 한 차례 timeout 되었으나 재시도 시 동일 태그·멀티아키텍처 manifest와 빌드가 정상 완료됐다.
- 비차단 후속: Config Client의 기본/프로필 import 중복으로 Docker 시작 시 `localhost:8888` 경고가 남고, 프론트 단일 JS chunk가 1.36MB라 Vite 경고가 발생한다. 각각 BACKLOG P2로 분리했다.
