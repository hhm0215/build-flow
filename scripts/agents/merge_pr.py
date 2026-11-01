#!/usr/bin/env python3
"""PR을 CI 전부 통과·커밋 SHA 일치일 때만 병합한다(CLAUDE.md 개발 워크플로우 7단계 안전망).

사용:
  python -I -X utf8 scripts/agents/merge_pr.py <PR 번호> [--wait]

- 모든 체크가 끝났고 결과가 성공·건너뜀·중립뿐일 때만 진행한다. 실패·취소·대기 중이 하나라도 있으면 멈춘다.
- PR 커밋 목록과 `origin/<base>..origin/<head>`가 같아야 한다.
- 병합은 `gh pr merge --merge`(병합 커밋)만 쓴다. 우회 옵션은 없다.
- `--wait`는 체크가 모두 끝날 때까지 기다린다(실패해도 끝까지 기다린 뒤 판정).
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time

PASSING = {"SUCCESS", "SKIPPED", "NEUTRAL"}
PENDING = {"PENDING", "QUEUED", "IN_PROGRESS", "EXPECTED", "WAITING", "REQUESTED"}


def run(args: list[str]) -> str:
    return subprocess.run(args, check=True, capture_output=True, text=True, encoding="utf-8").stdout


def check_states(pr: int) -> list[tuple[str, str]]:
    # gh pr checks는 실패 체크가 있으면 0이 아닌 코드로 끝나므로 check=False로 읽는다
    out = subprocess.run(["gh", "pr", "checks", str(pr), "--json", "name,state"], capture_output=True,
                         text=True, encoding="utf-8").stdout
    return [(c["name"], c["state"].upper()) for c in json.loads(out or "[]")]


def judge(states: list[tuple[str, str]]) -> tuple[str, list[str]]:
    """('pass' | 'pending' | 'fail', 문제 체크 이름들)."""
    if not states:
        return "pending", ["체크 없음"]
    failing = [f"{n}={s}" for n, s in states if s not in PASSING and s not in PENDING]
    if failing:
        return "fail", failing
    waiting = [f"{n}={s}" for n, s in states if s in PENDING]
    if waiting:
        return "pending", waiting
    return "pass", []


def same_commits(pr: int) -> tuple[bool, str]:
    info = json.loads(run(["gh", "pr", "view", str(pr), "--json", "baseRefName,headRefName,commits"]))
    run(["git", "fetch", "-q", "origin"])
    pr_shas = sorted(c["oid"] for c in info["commits"])
    log = run(["git", "log", "--format=%H", f"origin/{info['baseRefName']}..origin/{info['headRefName']}"])
    local = sorted(line for line in log.split() if line)
    return pr_shas == local, f"PR {len(pr_shas)}개 / 범위 {len(local)}개"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("pr", type=int)
    parser.add_argument("--wait", action="store_true", help="체크가 모두 끝날 때까지 기다림")
    parser.add_argument("--interval", type=int, default=30)
    args = parser.parse_args()

    while True:
        verdict, problems = judge(check_states(args.pr))
        if verdict != "pending" or not args.wait:
            break
        time.sleep(args.interval)
    if verdict != "pass":
        print(f"병합 중단: CI {'실패' if verdict == 'fail' else '미완료'} — {', '.join(problems)}")
        return 1
    ok, detail = same_commits(args.pr)
    if not ok:
        print(f"병합 중단: 커밋 SHA 불일치({detail})")
        return 1
    print(run(["gh", "pr", "merge", str(args.pr), "--merge"]).strip() or f"PR #{args.pr} 병합")
    return 0


if __name__ == "__main__":
    sys.exit(main())
