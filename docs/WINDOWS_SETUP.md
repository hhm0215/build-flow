# BuildFlow Windows 개발 환경

Windows 11과 PowerShell 기준입니다. 새 clone에서는 먼저 풀 Docker 모드로 동작을 확인하고, 필요한 서비스만 호스트에서 직접 실행하는 방식으로 전환하는 것을 권장합니다.

## 1. 준비할 도구

### 풀 Docker 실행에 필수

- Git for Windows
- Docker Desktop: WSL 2 backend와 Linux containers 사용
- PowerShell 5.1 이상
- Ollama for Windows

```powershell
git --version
docker version
docker compose version
ollama --version
ollama pull qwen2.5:7b
```

### 호스트에서 직접 개발할 때 추가

- JDK 17: Temurin 17 등
- Bun 1.3.11
- GitHub CLI: PR 확인·생성·머지가 필요할 때

```powershell
java -version
bun --version
gh --version
gh auth login
```

Docker 전체 실행은 이미지 안에서 Java와 Bun 빌드를 수행하므로 호스트 JDK/Bun이 없어도 됩니다.

## 2. 저장소 준비

```powershell
git clone https://github.com/hhm0215/build-flow.git
Set-Location build-flow
git switch develop
git status --short --branch
```

`develop`이 개발 기준 브랜치입니다. `main`은 PR merge를 통해서만 갱신합니다.

처음 한 번은 현재 PowerShell 프로세스에서 로컬 스크립트 실행을 허용합니다. 시스템 전체 실행 정책은 변경하지 않습니다.

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\buildflow.ps1 check
```

이 명령은 다음을 수행합니다.

- Git, Docker, Docker Compose 확인
- Docker Desktop daemon 확인
- `.env`가 없을 때만 DB 비밀번호와 JWT secret을 무작위로 생성
- 결합 Compose 설정 검증
- Ollama와 `qwen2.5:7b` 존재 여부 안내
- 선택 도구인 Java, Bun, GitHub CLI 버전 안내

기존 `.env`는 절대 덮어쓰지 않습니다.

## 3. 환경변수

`.env`는 git에 포함되지 않습니다. `buildflow.ps1 check`가 fresh clone에서 강한 값을 자동 생성하고 화면에 출력하지 않습니다.

```dotenv
DB_ROOT_PASSWORD=<로컬에서 자동 생성>
DB_USERNAME=root
DB_PASSWORD=<DB_ROOT_PASSWORD와 같은 값>
JWT_SECRET=<로컬에서 자동 생성한 64바이트 무작위 값>
```

이 파일은 저장소에 추가하거나 메신저로 전달하지 않습니다. 새 Windows 머신에서는 값을 옮기는 대신 `check`로 새 값을 생성합니다.

이미 MySQL volume을 만든 뒤 DB 비밀번호만 바꾸면 기존 volume의 MySQL 계정과 값이 달라집니다. 이 경우 데이터를 보존한 채 계정 비밀번호를 먼저 변경해야 하며, 단순히 volume을 삭제하면 데이터가 사라집니다.

### Ollama 연결 방식

기본 경로는 Windows native Ollama입니다.

| 실행 조합 | `OLLAMA_URL` |
|----------|--------------|
| 앱 Docker + native Ollama | `http://host.docker.internal:11434` (기본값) |
| 앱 Docker + Compose Ollama | `http://ollama:11434` |
| 앱 로컬 Java + native Ollama | `http://localhost:11434` (기본값) |
| 앱 로컬 Java + Compose Ollama | `http://localhost:11435` |

기본 구성에서는 `.env`에 `OLLAMA_URL`을 쓰지 않습니다. Docker 앱은 `host.docker.internal:11434`를 자동 사용합니다.

Compose Ollama를 선택할 때만 다음처럼 별도 profile과 URL을 사용합니다.

```powershell
$env:OLLAMA_URL = "http://ollama:11434"
docker compose --profile container-ollama -f docker-compose.yml -f docker-compose.app.yml up -d ollama
docker compose --profile container-ollama -f docker-compose.yml -f docker-compose.app.yml exec ollama ollama pull qwen2.5:7b
.\scripts\buildflow.ps1 up
```

같은 PowerShell 세션에서 `up`을 실행해야 `$env:OLLAMA_URL`이 전달됩니다.

로컬 Java를 Compose Ollama에 연결할 때는 해당 서비스 창에서 `$env:OLLAMA_URL = "http://localhost:11435"`를 먼저 설정하면 `boot-service.ps1`이 그 값을 보존합니다.

## 4. 전체 스택 실행

```powershell
.\scripts\buildflow.ps1 up
.\scripts\buildflow.ps1 status
```

첫 빌드는 Docker 이미지 다운로드와 10개 Java 서비스 빌드 때문에 시간이 걸릴 수 있습니다. 기동 순서는 Compose health check가 Eureka → Config → Gateway → 업무 서비스 순으로 제어합니다.

| 대상 | 주소 |
|------|------|
| 프론트엔드 | http://localhost:3000 |
| Gateway | http://localhost:8080 |
| Eureka | http://localhost:8761 |
| Config | http://localhost:8888 |
| Zipkin | http://localhost:9411 |

로그 확인과 종료:

```powershell
.\scripts\buildflow.ps1 logs
.\scripts\buildflow.ps1 down
```

`down`은 named volume을 보존합니다. `docker stop`이나 `docker compose down -v`는 사용하지 않습니다.

## 5. 최초 관리자 생성

fresh DB에는 계정 seed가 없고 현재 프론트엔드에는 가입 화면이 없습니다. 전체 스택이 올라온 뒤 대화형 스크립트로 최초 관리자 계정을 한 번 생성합니다. 비밀번호 입력은 마스킹되며 명령 기록에 남지 않습니다.

```powershell
.\scripts\create-admin.ps1
```

그다음 http://localhost:3000 에서 같은 이메일과 비밀번호로 로그인합니다.

현재 공개 가입과 이메일 로그인은 임시 현행 계약입니다. 단일 관리자만 유지하고 `아이디/비밀번호` 로그인으로 전환하는 작업은 별도 인증 마이그레이션으로 추적합니다.

## 6. 호스트 개발 모드

### 백엔드

인프라만 Docker로 실행합니다.

```powershell
docker compose up -d
.\gradlew.bat --version
.\gradlew.bat test --no-daemon
```

서비스는 각각 별도 PowerShell 창에서 실행합니다. 전용 스크립트가 매번 `.env`를 현재 프로세스에 로드하고 Docker 인프라의 호스트 주소(MySQL/Redis localhost, Kafka localhost:9094)를 설정합니다. 비밀값은 명령 기록에 들어가지 않습니다.

`notification-service`의 스캔 PDF OCR fallback은 Windows native Tesseract 경로 설정이 추가로 필요합니다. OCR을 개발하지 않는 동안에는 이 서비스만 Docker로 유지하는 것을 권장합니다.

```powershell
.\scripts\boot-service.ps1 eureka-server
.\scripts\boot-service.ps1 config-server
.\scripts\boot-service.ps1 gateway-server
.\scripts\boot-service.ps1 auth-service
.\scripts\boot-service.ps1 estimate-service
.\scripts\boot-service.ps1 site-service
.\scripts\boot-service.ps1 purchase-service
.\scripts\boot-service.ps1 tax-service
.\scripts\boot-service.ps1 notification-service
.\scripts\boot-service.ps1 chat-service
```

필수 순서는 Eureka → Config → Gateway → 나머지입니다.

### 프론트엔드

```powershell
Set-Location frontend
bun install --frozen-lockfile
bun run lint
bun run test
bun run build
bun run dev
```

`bun run dev`는 기본적으로 MSW 목업을 사용합니다. 실제 Gateway에 연결하려면 다음처럼 실행합니다.

```powershell
$env:MSW_DISABLED = "true"
bun run dev
```

프론트엔드 패키지에는 `npm`과 `yarn`을 사용하지 않습니다.

## 7. 작업 컨텍스트 복원

새 머신에서 작업을 시작할 때 아래 순서로 읽으면 현재 흐름을 복원할 수 있습니다.

1. `AGENTS.md` — 강제 코드·도구 규칙
2. `CLAUDE.md` — 상세 개발 및 Git/PR 워크플로우
3. `.claude/BACKLOG.md` — 다음 작업 우선순위의 단일 진실원
4. `.claude/PROGRESS.md` — 완료 이력과 현재 진입점
5. 작업 대상에 연결된 `.claude/plans/*.md`

`.claude/settings.local.json`과 사용자 홈의 에이전트 설정은 머신별 파일이므로 clone에 포함되지 않습니다. 없어도 빌드와 개발은 가능하며, 사용하는 도구에서 필요한 권한만 새 머신에 설정합니다. `.claude/hooks/*.sh`를 사용하는 경우 Git Bash 또는 WSL에서 실행합니다.

PR 작업을 이어갈 때는 문서의 SHA만 믿지 말고 원격을 다시 확인합니다.

```powershell
git fetch --prune origin
git status --short --branch
git log --oneline --decorate -5
gh pr list
```

## 8. 문제 해결

### Docker Desktop에 연결할 수 없음

Docker Desktop을 실행하고 Linux containers 모드인지 확인한 뒤 다시 검사합니다.

```powershell
docker info
.\scripts\buildflow.ps1 check
```

### 포트가 이미 사용 중

```powershell
Get-NetTCPConnection -State Listen |
  Where-Object LocalPort -In 3000,3306,6379,8080,8081,8082,8083,8084,8085,8086,8087,8761,8888,9094,9411,11434,11435
```

기존 서비스를 확인해 정상 종료한 뒤 다시 실행합니다. 데이터 보존 여부를 모른 채 프로세스나 컨테이너를 강제 삭제하지 않습니다.

### shell script에서 `\r` 오류

저장소의 `.gitattributes`가 `*.sh`와 `gradlew`를 LF로 고정합니다. 로컬 변경을 지우는 `git reset --hard`는 사용하지 말고 속성과 현재 상태를 먼저 확인합니다.

```powershell
git check-attr eol -- .claude/hooks/session-start.sh gradlew gradlew.bat scripts/buildflow.ps1
git status --short
```

### AI 요청만 실패

```powershell
ollama list
Invoke-RestMethod http://localhost:11434/api/tags
docker compose -f docker-compose.yml -f docker-compose.app.yml logs --tail 100 estimate-service site-service chat-service
```

native Ollama에 `qwen2.5:7b`가 있고, Docker 앱의 `OLLAMA_URL`이 `host.docker.internal:11434`인지 확인합니다.

## 9. 로컬 서버 공개 전 주의

기본 Compose는 Windows 개발 PC의 loopback에서만 접근하도록 구성합니다. LAN 사용자에게 공개하는 구성은 단순 포트 개방으로 만들지 않습니다. Gateway만 외부에 노출하고 업무 서비스·MySQL·Redis·Kafka는 내부 네트워크에 유지하는 배포 override와 단일 관리자 인증 전환을 먼저 완료해야 합니다.
