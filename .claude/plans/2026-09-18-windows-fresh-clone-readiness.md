# Windows fresh clone 개발 준비

- **시작일**: 2026-09-18
- **BACKLOG 항목**: P0 — Windows fresh clone 개발 준비
- **예상 규모**: M
- **상태**: IN_PROGRESS

## 목표

Windows에서 저장소를 새로 clone한 뒤 PowerShell 중심의 짧고 재현 가능한 절차로 환경을 점검하고 전체 Docker 스택 또는 로컬 개발 모드를 시작할 수 있게 한다. 문서와 실제 포트·도구·명령이 일치하고 기존 빌드 검증이 통과하면 완료로 본다.

## 배경 / 동기

현재 저장소에는 Gradle wrapper와 Bun 1.3.11 고정, 풀 Docker 실행 구성이 이미 있지만 진입 문서가 과거 상태를 설명한다.

- README가 프론트엔드에 금지된 npm 명령과 잘못된 5173 포트를 안내한다.
- Windows 가이드는 존재하는 Gradle wrapper를 없다고 설명하고, 머신별 Claude 권한 파일을 필수 단계처럼 다룬다.
- 새 clone에서는 gitignore 대상인 `.env`를 직접 만들어야 하며, 결합 Compose 명령이 길다.
- `.env.example`의 Ollama URL 예시는 컨테이너 내부에서 잘못 해석될 수 있다.
- GitHub Actions의 `ubuntu-latest` 전환 경고가 있어 러너 이미지를 고정할 필요가 있다.

## 접근법

1. PowerShell 스크립트 하나에 `check`, `up`, `down`, `status` 동작을 제공한다. `.env`는 없을 때만 강한 무작위값으로 생성하며 기존 파일을 덮어쓰지 않는다.
2. 관리자 비밀번호가 명령 기록에 남지 않는 대화형 초기 계정 생성 스크립트를 제공한다.
3. 풀 Docker 모드를 Windows 기본 경로로 문서화하고, JDK/Bun을 쓰는 로컬 분리 실행은 선택 경로로 분리한다.
4. `gradlew.bat`, Bun, 실제 포트 3000, native Ollama와 `host.docker.internal`의 역할을 정확히 기록한다.
5. Windows 배치/PowerShell 파일의 줄바꿈 규칙과 CI runner 버전을 명시적으로 고정한다.
6. 사용자 작업인 `docs/DECISIONS.md`는 수정하거나 커밋 대상에 섞지 않는다.

## 산출물 체크리스트

- [x] Windows PowerShell helper 추가
- [x] 비밀값 자동 생성 및 대화형 관리자 생성 경로
- [x] README fresh clone quick start 정렬
- [x] Windows 셋업 가이드 전면 정비
- [x] `.env.example` Windows/Ollama 안내 정렬
- [x] `.gitattributes` Windows 스크립트 줄바꿈 고정
- [x] CI runner 재현성 고정
- [x] Compose/Gradle/frontend 검증
- [x] 새 clone 시뮬레이션 또는 동등한 tracked-file 검증
- [x] 정적 리뷰 CRITICAL/HIGH 0
- [ ] GitHub Windows runner에서 PowerShell 5.1·`gradlew.bat` 검증

## 리스크 / 모르는 것

- macOS 작업 환경에 PowerShell이 없으면 스크립트 실행 검증 대신 구문/행위 정적 검토와 Windows CI 가능한 범위를 사용한다.
- Docker Desktop과 native Ollama 설치 자체는 시스템 변경이므로 자동화하지 않고 존재 여부와 필요한 모델만 점검한다.
- `.env` 비밀값은 fresh clone마다 로컬에서 생성하고 출력하거나 커밋하지 않는다.
- 관리자 단일 계정/loginId 전환은 별도 인증 기능 작업이며 이번 이관 준비에 섞지 않는다.

## 테스트 / 검증

- `docker compose -f docker-compose.yml -f docker-compose.app.yml config --quiet`
- `./gradlew test --no-daemon`
- `cd frontend && bun install --frozen-lockfile && bun run lint && bun run test && bun run build`
- `git check-attr`로 `.sh`, `gradlew`, `.bat`, `.ps1` 줄바꿈 규칙 확인
- 추적 파일만 복사한 임시 디렉터리에서 `.env` 생성 및 Compose config 확인
- `git diff --check`

## 결과 (작업 후 기록)

- README와 Windows 가이드를 현재 포트·Bun·루트 Gradle wrapper·결합 Compose 기준으로 전면 정렬했다.
- PowerShell helper 3종으로 로컬 비밀값 생성, 전체 스택 수명주기, 서비스별 `.env` 로드, 대화형 관리자 생성을 제공한다. 비밀값은 화면·명령 기록·git에 남기지 않는다.
- 기본 Compose는 native Ollama를 사용하고 선택 profile에서만 컨테이너 Ollama를 띄우며, 모든 host publish를 `127.0.0.1`로 제한했다.
- 루트 Docker context에서 VCS·비밀값·프론트 의존성을 제외해 백엔드 context를 서비스별 약 12~89kB로 줄였다.
- 로컬 검증은 Gradle 전체 테스트, frontend lint·7 tests·build, 결합 Compose, 15개 기본 컨테이너 기동과 HTTP 200, fresh checkout-index Compose 검증이 통과했다.
- Windows native 검증은 새 CI job의 PowerShell 5.1 parser와 `gradlew.bat --version` 결과를 확인한 뒤 완료 처리한다.
