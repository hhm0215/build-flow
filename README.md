# BuildFlow

건설·시공 소규모 업체를 위한 현장 업무 관리 플랫폼입니다. 현장별 견적, 매입, 세금계산서, 하자보증보험을 묶어 관리하고 손익·마진·미수금을 집계하며, 로컬 Ollama 기반 AI 요약과 챗봇을 제공합니다.

## 구성

```text
React :3000
    │
Gateway :8080 ── Redis
    │
    ├─ auth :8081
    ├─ estimate :8082 ─┐
    ├─ site :8083      │
    ├─ purchase :8084  ├─ Kafka ─ notification :8086
    ├─ tax :8085       │
    └─ chat :8087 ─────┘

Eureka :8761 · Config :8888 · MySQL :3306 · Zipkin :9411 · Ollama :11434
```

| 영역 | 기술 |
|------|------|
| 백엔드 | Java 17, Spring Boot 3.3.4, Spring Cloud 2023.0.3, Gradle 8.10 |
| 데이터 | MySQL 8, Redis 7.2, Kafka(KRaft), JPA/Hibernate |
| 프론트엔드 | React 18, TypeScript, Vite, Ant Design, Zustand, TanStack Query |
| AI | Ollama `qwen2.5:7b` |
| 실행 | Docker Compose, Bun 1.3.11 |

## Windows에서 바로 시작

가장 재현성이 높은 경로는 앱까지 모두 Docker로 실행하는 방식입니다.

### 1. 준비

- Git for Windows
- Docker Desktop (WSL 2 backend)
- Ollama for Windows와 모델: `ollama pull qwen2.5:7b`

JDK와 Bun은 Docker 전체 실행에는 필요하지 않습니다. 백엔드나 프론트엔드를 호스트에서 직접 개발할 때만 Java 17과 Bun 1.3.11을 설치합니다.

### 2. clone 및 기동

PowerShell에서 실행합니다.

```powershell
git clone https://github.com/hhm0215/build-flow.git
Set-Location build-flow
git switch develop

Set-ExecutionPolicy -Scope Process Bypass
.\scripts\buildflow.ps1 check
.\scripts\buildflow.ps1 up
```

`check`는 필요한 도구와 Compose 설정을 검사하고 `.env`가 없으면 DB 비밀번호와 JWT secret을 강한 무작위값으로 로컬에 생성합니다. 값은 화면에 출력하지 않으며 기존 `.env`도 덮어쓰지 않습니다. `up`은 이미지를 빌드한 뒤 전체 스택을 기동합니다.

### 3. 접속 확인

| 대상 | 주소 |
|------|------|
| 프론트엔드 | http://localhost:3000 |
| Gateway | http://localhost:8080 |
| Eureka | http://localhost:8761 |
| Zipkin | http://localhost:9411 |

```powershell
.\scripts\buildflow.ps1 status
```

새 데이터베이스에는 사용자가 없습니다. 현재 구현은 이메일 기반 가입 API를 사용하므로 최초 1회 대화형 명령으로 관리자 계정을 생성합니다. 비밀번호는 PowerShell 기록이나 저장소에 남지 않습니다.

```powershell
.\scripts\create-admin.ps1
```

> 공개 가입을 제거하고 단일 관리자 `아이디/비밀번호` 로그인으로 바꾸는 작업은 다음 인증 마이그레이션 범위입니다. 현재 계약은 이메일/비밀번호이므로 문서도 현재 동작을 그대로 설명합니다.

### 4. 종료

```powershell
.\scripts\buildflow.ps1 down
```

데이터는 named volume에 남습니다. 볼륨까지 지우는 `down -v`는 데이터 삭제 작업이므로 사용하지 않습니다.

자세한 설치, 로컬 분리 실행, 문제 해결은 [Windows 셋업 가이드](docs/WINDOWS_SETUP.md)를 참고하세요.

## 로컬 개발

### 백엔드

루트 Gradle wrapper를 사용합니다. 서비스 디렉터리에서 별도 Gradle을 실행하지 않습니다.

```powershell
# 인프라만 실행
docker compose up -d

# 전체 테스트
.\gradlew.bat test --no-daemon

# 필수 순서: Eureka → Config → Gateway → 업무 서비스
.\scripts\boot-service.ps1 eureka-server
.\scripts\boot-service.ps1 config-server
.\scripts\boot-service.ps1 gateway-server
.\scripts\boot-service.ps1 auth-service
```

각 명령은 별도 PowerShell 창에서 실행합니다. 스크립트가 매번 `.env`를 로드하고 Docker 인프라의 호스트 주소를 설정하므로 비밀값을 명령줄에 넣지 않습니다.

### 프론트엔드

패키지 매니저는 Bun만 사용합니다. `npm`과 `yarn`은 사용하지 않습니다.

```powershell
Set-Location frontend
bun install --frozen-lockfile
bun run lint
bun run test
bun run build
bun run dev
```

기본 `bun run dev`는 MSW 목업 모드입니다. 로컬 Gateway의 실제 API를 연결하려면 다음처럼 실행합니다.

```powershell
$env:MSW_DISABLED = "true"
bun run dev
```

## 주요 기능

- JWT 인증과 Redis 토큰 무효화
- 현장·거래처·견적·매입·세금계산서 CRUD
- Kafka 이벤트 기반 현장 손익 집계
- 보증보험 PDF 업로드, OCR, 만료 알림
- 현장별 매출·매입·마진·미수금 대시보드
- Ollama function calling 기반 챗봇과 SSE 응답

## 프로젝트 문서

| 파일 | 역할 |
|------|------|
| `AGENTS.md`, `CLAUDE.md` | 개발 규칙과 작업 흐름 |
| `.claude/BACKLOG.md` | 다음 작업 우선순위의 단일 진실원 |
| `.claude/PROGRESS.md` | 완료 이력과 다음 세션 진입점 |
| `docs/ARCHITECTURE.md` | 시스템 구조 |
| `docs/API_SPEC.md` | API 명세 |
| `docs/ERD.md` | 데이터 모델 |
| `docs/DECISIONS.md` | 아키텍처 결정 기록 |

## 핵심 규칙

- 프론트엔드는 Bun만 사용합니다.
- Entity를 API에서 직접 반환하지 않습니다.
- 서비스 간 DB 직접 접근을 금지합니다.
- 환경변수와 비밀값을 코드에 하드코딩하지 않습니다.
- 전체 스택 종료에는 `docker compose down`을 사용합니다.
- 실제 개발 시작 전 `AGENTS.md`, `CLAUDE.md`, `.claude/BACKLOG.md`, `.claude/PROGRESS.md`를 확인합니다.
