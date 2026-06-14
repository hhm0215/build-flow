# BuildFlow Windows 환경 셋업 가이드

> 이 문서는 macOS에서 작업하던 BuildFlow를 Windows 머신에서 이어 작업할 때 필요한 셋업과 알려진 이슈 대응을 정리한다.
> Claude Code 하네스(`.claude/`)와 설계 문서(`docs/`, `CLAUDE.md`)가 git 추적되도록 전환된 이후 기준.

---

## 1. 사전 준비 (Windows 머신)

### 1-1. 필수
- **Claude Code for Windows** 설치
- **Git Bash** 설치 (Git for Windows 패키지에 포함) — 또는 **WSL2**
  - 이유: `.claude/hooks/*.sh` 가 bash 스크립트. Windows cmd / PowerShell 만으로는 동작 안 함
- **Git** 설치 (Git for Windows에 포함)

### 1-2. 빌드/실행 시 추가로 필요
- Java 17 (Temurin, Corretto 등)
- Bun (frontend)
- Docker Desktop for Windows (인프라 컨테이너)
- Ollama (Windows 네이티브 또는 docker-compose)

---

## 2. 저장소 가져오기

```bash
git clone https://github.com/hhm0215/build-flow.git
cd build-flow
git checkout develop

# 환경변수 파일 생성 (.env는 git 추적 외)
copy .env.example .env       # Windows cmd / PowerShell
# 또는 Git Bash: cp .env.example .env
# .env 열어서 DB_PASSWORD / JWT_SECRET 값 채우기
```

> 하네스와 문서는 `develop` 브랜치에 있다. `main`은 다음 번 PR 머지 시점에 따라잡힘.

---

## 3. `.claude/settings.local.json` 새로 작성

이 파일은 **머신별로 권한이 다를 수 있어 git 추적 제외** 대상. clone 직후엔 존재하지 않으니 직접 만든다.

### 3-1. 기본 템플릿 (macOS 것 그대로 옮겨도 동작함)

`build-flow/.claude/settings.local.json` 위치에 다음 내용으로 생성:

```json
{
  "attribution": {
    "commit": "",
    "pr": ""
  },
  "permissions": {
    "allow": [
      "Bash(./gradlew:*)",
      "Bash(gradle:*)",

      "Bash(bun install:*)",
      "Bash(bun add:*)",
      "Bash(bun run:*)",
      "Bash(bunx:*)",

      "Bash(docker compose:*)",
      "Bash(docker ps:*)",
      "Bash(docker logs:*)",
      "Bash(docker exec:*)",
      "Bash(docker inspect:*)",
      "Bash(docker images:*)",
      "Bash(docker network:*)",

      "Bash(git status:*)",
      "Bash(git log:*)",
      "Bash(git diff:*)",
      "Bash(git branch:*)",
      "Bash(git checkout:*)",
      "Bash(git stash:*)",
      "Bash(git merge:*)",
      "Bash(git add:*)",
      "Bash(git commit:*)",
      "Bash(git push:*)",
      "Bash(git pull:*)",
      "Bash(git fetch:*)",
      "Bash(git restore:*)",
      "Bash(git show:*)",
      "Bash(git remote:*)",
      "Bash(git ls-files:*)",

      "Bash(gh pr create:*)",
      "Bash(gh pr view:*)",
      "Bash(gh pr list:*)",
      "Bash(gh pr status:*)",
      "Bash(gh pr diff:*)",
      "Bash(gh pr checks:*)",
      "Bash(gh pr merge:*)",
      "Bash(gh pr edit:*)",
      "Bash(gh api repos/*/pulls*)",
      "Bash(gh api repos/*/branches/*/protection*)",
      "Bash(gh auth status:*)",

      "Bash(curl localhost:*)",
      "Bash(curl http://localhost:*)",
      "Bash(curl -s localhost:*)",
      "Bash(curl -s http://localhost:*)",

      "Bash(ls:*)",
      "Bash(find:*)",
      "Bash(xargs cat:*)",
      "Bash(java -version)",
      "Bash(java --version)",

      "Skill(design-review)"
    ],
    "deny": [
      "Bash(gh pr close:*)",
      "Bash(gh pr edit * --state*)",
      "Bash(gh repo delete:*)",
      "Bash(gh api -X DELETE*)",
      "Bash(git push --force:*)",
      "Bash(git push -f:*)",
      "Bash(git push * --no-verify*)",
      "Bash(git commit * --no-verify*)"
    ]
  },
  "hooks": {
    "SessionStart": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "bash \"$CLAUDE_PROJECT_DIR\"/.claude/hooks/session-start.sh",
            "timeout": 10
          }
        ]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "Edit|Write",
        "hooks": [
          {
            "type": "command",
            "command": "bash \"$CLAUDE_PROJECT_DIR\"/.claude/hooks/post-edit-compile.sh",
            "timeout": 60,
            "statusMessage": "Compiling..."
          }
        ]
      }
    ]
  }
}
```

### 3-2. Windows 특이사항
- 경로 패턴(`./gradlew`, `bun`, `docker` 등)은 그대로 동작. Git Bash가 PATH 매핑을 처리함
- `brew list:*`, `sdk list:*` 같은 macOS 전용 명령은 제외하고 추가
- Java/Gradle을 chocolatey나 scoop으로 설치했다면 그쪽 명령은 별도로 권한 추가

---

## 4. 첫 세션 시작

Claude Code를 BuildFlow 디렉토리에서 시작하면 SessionStart hook이 자동 실행되어 다음이 컨텍스트에 주입됨:
- `.claude/PROGRESS.md` 머리/꼬리
- `origin/develop` 대비 미푸시 커밋 목록
- 워킹트리 상태 (`git status -s`)
- 현재 브랜치

→ "PROGRESS.md 읽어줘" 같은 수동 명령 불필요. 곧바로 이어서 작업 가능.

---

## 5. 알려진 이슈 & 대응

### 5-1. `gradlew` 미설치
- **증상**: `.claude/hooks/post-edit-compile.sh`가 Java 편집 후 graceful skip(컴파일 검증 비활성)
- **원인**: 프로젝트에 Gradle Wrapper 가 없음 (BACKLOG.md P2에 등록됨)
- **대응**: 다음 중 택1
  ```bash
  # A. 시스템 gradle 설치 후 wrapper 생성
  choco install gradle           # 또는 scoop install gradle
  gradle wrapper --gradle-version 8.10

  # B. 그대로 두고 hook 검증 없이 진행 (에디터/IDE가 컴파일 처리)
  ```

### 5-2. 쉘 스크립트 줄바꿈(CRLF) 이슈
- **증상**: `bash: '\r': command not found` 류 에러로 hook 동작 실패
- **원인**: Windows에서 git이 자동으로 LF→CRLF 변환
- **대응**: 이 PR에서 `.gitattributes`에 `*.sh text eol=lf`, `gradlew text eol=lf` 추가하여 예방. 이미 clone 했다면:
  ```bash
  git config --global core.autocrlf false
  # 또는 해당 파일만 강제 LF로 다시 체크아웃
  git rm --cached -r .
  git reset --hard
  ```

### 5-3. IDE 설정 (`.idea/`)
- git 추적 제외(.gitignore). Windows에서 IntelliJ 처음 열면 SDK/실행 설정 다시 잡아줘야 함
- 정상 동작. 한 번만 셋업하면 됨

### 5-4. Claude Code 메모리(`~/.claude/projects/...`)
- 이 영역은 git이 아닌 **글로벌 사용자 영역**이라 Windows에 자동으로 따라오지 않음
- **그러나 핵심 규칙은 모두 git 추적 파일에 박혀 있어 메모리 없이도 동일 워크플로우 작동**:
  - `CLAUDE.md` — 코드/git/워크플로우 규칙 (5+1+1+1 = 8단계 사이클)
  - `.claude/BACKLOG.md` — 다음 작업 우선순위 단일 진실원
  - `.claude/PROGRESS.md` — 완료 이력 + git 상태 + 다음 세션 진입점
  - `.claude/RETROSPECTIVE.md` — 실패/사고 회고 + 재발 방지 규칙
  - `docs/DECISIONS.md` — ADR (워크플로우 시스템, PR 자동화 등)
- 메모리는 보조 노트 — 머신 간 동기화 안 해도 됨
- 굳이 옮기려면 macOS의 `~/.claude/projects/-Users-...-BuildFlow/memory/` 폴더를 Windows의 `%USERPROFILE%\.claude\projects\` 아래 적절한 프로젝트 디렉토리로 수동 복사 (디렉토리명은 프로젝트 절대경로의 슬래시를 하이픈으로 치환한 형태)

### 5-5. Docker Desktop + Ollama
- Ollama Windows 네이티브 설치 추천 (`ollama pull qwen2.5:7b`)
- 또는 docker-compose의 Ollama 컨테이너 그대로 사용 (Docker Desktop 필요)

---

## 6. 작업 흐름 (요약)

```bash
# 1) 최신 develop 받기
git pull origin develop

# 2) Claude Code 세션 시작
#    → SessionStart hook이 자동으로 컨텍스트 복원

# 3) 작업 후 커밋 (Claude는 사전 승인 후 실행)
git add <files>
git commit -m "feat(service): ..."

# 4) push 시점에 묶어서
git push origin develop

# 5) 워크플로우 6~8단계는 Claude가 자동 수행
#    6단계: gh pr create — PR 자동 생성 (push 누락 검증 후)
#    7단계: gh pr merge --merge — Commits SHA 자동 검증 후 자동 머지
#    8단계: PROGRESS "다음 세션 진입점" main 동기화 반영
#    PR 본문 형식: ##변경 사항 / ##상세 두 섹션 고정
```

---

## 7. 트러블슈팅 빠른 체크

| 증상 | 확인 |
|------|------|
| hook이 안 도는 것 같음 | `bash .claude/hooks/session-start.sh` 수동 실행해 출력 확인 |
| `bash: command not found` | Git Bash가 PATH에 있는지, Claude Code가 그걸 호출하는지 |
| `\r: command not found` | 위 5-2 CRLF 이슈 |
| 권한 프롬프트가 자주 뜸 | `.claude/settings.local.json`의 allow 목록 보강 |
| PROGRESS.md 안 주입됨 | `.claude/settings.local.json`의 SessionStart hook 등록 확인 |
