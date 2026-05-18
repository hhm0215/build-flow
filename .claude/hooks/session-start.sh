#!/bin/bash
# SessionStart hook: PROGRESS.md 요약 + 미푸시 커밋 + 워킹트리 상태를 컨텍스트로 주입
# stdout 출력 = 세션 초기 컨텍스트로 모델에 전달됨

cd "$CLAUDE_PROJECT_DIR" 2>/dev/null || exit 0

echo "=== BuildFlow 세션 부트스트랩 ==="
echo ""

# 1) PROGRESS.md — 머리 60줄(현재 브랜치 + 완료 요약) + 꼬리 50줄(다음 작업 + 이슈)
if [ -f .claude/PROGRESS.md ]; then
  TOTAL=$(wc -l < .claude/PROGRESS.md | tr -d ' ')
  echo "--- PROGRESS.md (총 ${TOTAL}줄) ---"
  if [ "$TOTAL" -le 120 ]; then
    cat .claude/PROGRESS.md
  else
    head -60 .claude/PROGRESS.md
    echo ""
    echo "... [중간 생략 — 필요 시 Read 도구로 전체 조회] ..."
    echo ""
    tail -50 .claude/PROGRESS.md
  fi
else
  echo "(PROGRESS.md 없음)"
fi
echo ""

# 2) origin/develop 대비 미푸시 커밋
echo "--- 미푸시 커밋 (git log origin/develop..HEAD) ---"
UNPUSHED=$(git log origin/develop..HEAD --oneline 2>/dev/null)
if [ -z "$UNPUSHED" ]; then
  echo "(없음 — origin/develop와 동일)"
else
  echo "$UNPUSHED"
fi
echo ""

# 3) 워킹트리 상태
echo "--- 워킹트리 상태 (git status -s) ---"
STATUS=$(git status -s 2>/dev/null)
if [ -z "$STATUS" ]; then
  echo "(clean)"
else
  echo "$STATUS"
fi
echo ""

# 4) 현재 브랜치
BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null)
echo "현재 브랜치: ${BRANCH:-unknown}"
echo ""
echo "=== 부트스트랩 완료 ==="

exit 0
