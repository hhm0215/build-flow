#!/bin/bash
# PostToolUse hook: 편집된 파일 종류에 따라 자동 검증
#   .java                → ./gradlew :<service>:compileJava
#   frontend/**/*.ts(x)  → frontend에서 bunx tsc --noEmit
# 실패 시 마지막 20줄을 stderr로 보여주고 exit 1 → Claude가 즉시 에러 감지

INPUT=$(cat)

# jq 우선, 없으면 sed fallback
if command -v jq >/dev/null 2>&1; then
  FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty')
else
  FILE_PATH=$(echo "$INPUT" | sed -n 's/.*"file_path"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)
fi

[[ -z "$FILE_PATH" ]] && exit 0

cd "$CLAUDE_PROJECT_DIR" 2>/dev/null || exit 0

# === Java 검증 ===
if [[ "$FILE_PATH" == *.java ]]; then
  SERVICE=$(echo "$FILE_PATH" | grep -oE '(auth|estimate|site|purchase|tax|notification)-service|(eureka|config|gateway)-server')
  [[ -z "$SERVICE" ]] && exit 0

  # gradle 도구 선택: ./gradlew 우선, 없으면 gradle, 둘 다 없으면 skip
  if [ -x ./gradlew ]; then
    GRADLE_CMD="./gradlew"
  elif command -v gradle >/dev/null 2>&1; then
    GRADLE_CMD="gradle"
  else
    # gradle 미설치 — hook을 실패시키지 않고 silent skip
    exit 0
  fi

  OUTPUT=$($GRADLE_CMD :"$SERVICE":compileJava --quiet 2>&1)
  STATUS=$?
  if [ $STATUS -ne 0 ]; then
    echo "[$SERVICE compileJava FAILED]" >&2
    echo "$OUTPUT" | tail -20 >&2
    exit 1
  fi
  exit 0
fi

# === Frontend TypeScript 검증 ===
if [[ "$FILE_PATH" == */frontend/*.ts || "$FILE_PATH" == */frontend/*.tsx ]]; then
  if [ ! -d frontend/node_modules ]; then
    # 의존성 미설치 시 검증 스킵 (false negative 방지)
    exit 0
  fi
  cd frontend || exit 0

  OUTPUT=$(bunx tsc --noEmit 2>&1)
  STATUS=$?
  if [ $STATUS -ne 0 ]; then
    echo "[frontend tsc --noEmit FAILED]" >&2
    echo "$OUTPUT" | tail -20 >&2
    exit 1
  fi
  exit 0
fi

# 그 외 파일(.yml, .md, .json 등): 검증 없음
exit 0
