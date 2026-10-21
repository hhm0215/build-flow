"""Codex ↔ Claude Code 공통 작업 공간: 세션이 갑자기 끊겨도 다음 도구가 이어받을 수 있게 한다.

    python -I -X utf8 scripts/agents/workspace.py status
    python -I -X utf8 scripts/agents/workspace.py checkpoint --tool claude --step "구현 중" --next "테스트"
    python -I -X utf8 scripts/agents/workspace.py checkpoint --tool claude --auto

status     잠금(develop과 열린 PR 브랜치), 열린 PR, 이 PC의 모든 작업 폴더(미커밋·미푸시), 양쪽 마지막 세션 시각.
checkpoint 현재 기능 브랜치를 HANDOFF 잠금과 함께 커밋·푸시하고 드래프트 PR이 없으면 연다.
           --auto 는 그 도구 접두어(claude/·codex/) 브랜치의 작업 폴더 중 변경·미푸시가 있는 곳만 처리한다.

develop·main에는 커밋하지 않고 force·--no-verify를 쓰지 않는다. .gitignore 대상은 올리지 않는다.
세션 원문은 출력하지 않는다(시각만).
"""
import argparse
import json
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

KST = timezone(timedelta(hours=9))
PROTECTED = {"develop", "main", "master", "HEAD"}
TOOL_PREFIX = {"claude": "claude/", "codex": "codex/"}
LOCK_FIELDS = ("상태", "도구", "갱신", "브랜치", "PR", "현재 단계", "다음 첫 행동")
HANDOFF = ".claude/HANDOFF.md"


def run(args, cwd=None, check=True):
    result = subprocess.run(args, cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and result.returncode != 0:
        raise RuntimeError(f"{' '.join(args[:3])} 실패: {result.stderr.strip()[:300]}")
    return result.stdout


def parse_lock(text):
    """HANDOFF 작업 잠금 표를 {필드: 값}으로 읽는다."""
    lock = {}
    for line in text.splitlines():
        m = re.match(r"^\|\s*([^|]+?)\s*\|\s*(.*?)\s*\|\s*$", line)
        if m and m.group(1) in LOCK_FIELDS:
            lock[m.group(1)] = m.group(2)
    return lock


def update_lock(text, values):
    """잠금 표의 해당 필드 값만 바꾼다. 표에 없는 필드는 추가하지 않는다."""
    def replace(line):
        m = re.match(r"^\|\s*([^|]+?)\s*\|", line)
        if m and m.group(1) in values and m.group(1) in LOCK_FIELDS:
            value = values[m.group(1)].replace("|", "/").replace("\n", " ")
            return f"| {m.group(1)} | {value} |"
        return line
    newline = "\r\n" if "\r\n" in text else "\n"
    return newline.join(replace(line) for line in text.split(newline))


def now_kst():
    return datetime.now(KST).strftime("%Y-%m-%d %H:%M KST")


def worktrees(repo):
    items, current = [], {}
    for line in run(["git", "worktree", "list", "--porcelain"], cwd=repo).splitlines():
        if line.startswith("worktree "):
            current = {"path": line[9:]}
            items.append(current)
        elif line.startswith("branch "):
            current["branch"] = line[7:].removeprefix("refs/heads/")
    return items


def worktree_state(path, branch):
    porcelain = run(["git", "status", "--porcelain"], cwd=path, check=False).splitlines()
    tracked = sum(1 for l in porcelain if not l.startswith("??"))
    untracked = len(porcelain) - tracked
    upstream = run(["git", "rev-parse", "--abbrev-ref", "@{u}"], cwd=path, check=False).strip()
    if branch and upstream != f"origin/{branch}":
        upstream = ""  # origin/develop 추적 등은 자기 브랜치가 원격에 없다는 뜻
    ahead = behind = None
    if upstream:
        counts = run(["git", "rev-list", "--left-right", "--count", "@{u}...HEAD"], cwd=path, check=False).split()
        if len(counts) == 2:
            behind, ahead = int(counts[0]), int(counts[1])
    last = run(["git", "log", "-1", "--format=%ci %s"], cwd=path, check=False).strip()
    ahead_of_develop = run(["git", "rev-list", "--count", "origin/develop..HEAD"], cwd=path, check=False).strip()
    return {"tracked": tracked, "untracked": untracked, "upstream": upstream,
            "ahead": ahead, "behind": behind, "last": last, "branch": branch,
            "ahead_of_develop": int(ahead_of_develop) if ahead_of_develop.isdigit() else 0}


def needs_attention(state, has_pr=True):
    """다른 도구가 볼 수 없는 작업이 남아 있으면 True(미커밋·미푸시, 또는 도구 브랜치인데 열린 PR 없음)."""
    if not state["branch"] or state["branch"] in PROTECTED:
        return state["tracked"] > 0
    tool_branch = state["branch"].startswith(tuple(TOOL_PREFIX.values()))
    return (state["tracked"] > 0 or not state["upstream"] or (state["ahead"] or 0) > 0
            or (tool_branch and not has_pr and state.get("ahead_of_develop", 0) > 0))


def open_prs(repo):
    try:
        return json.loads(run(["gh", "pr", "list", "--state", "open", "--json",
                               "number,title,headRefName,isDraft,updatedAt"], cwd=repo))
    except (RuntimeError, ValueError, FileNotFoundError):
        return []


def session_times():
    try:
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        from last_session import claude_session, codex_session
    except ImportError:
        return {}
    times = {}
    for name, finder in (("codex", codex_session), ("claude", claude_session)):
        path = finder()
        if path:
            times[name] = datetime.fromtimestamp(path.stat().st_mtime, KST).strftime("%Y-%m-%d %H:%M")
    return times


def status(repo):
    run(["git", "fetch", "-q", "origin", "--prune"], cwd=repo, check=False)
    develop = run(["git", "rev-parse", "--short", "origin/develop"], cwd=repo, check=False).strip()
    print(f"# 공통 작업 공간 상태 ({now_kst()}, origin/develop {develop})")
    print("\n## 작업 잠금")
    prs = open_prs(repo)
    sources = [("origin/develop", "origin/develop")] + [
        (f"PR #{p['number']} {p['headRefName']}", f"origin/{p['headRefName']}")
        for p in prs if p["headRefName"] not in PROTECTED]
    for label, ref in sources:
        text = run(["git", "show", f"{ref}:{HANDOFF}"], cwd=repo, check=False)
        lock = parse_lock(text)
        if lock:
            print(f"- [{label}] {lock.get('상태', '?')} · {lock.get('도구', '?')} · {lock.get('갱신', '?')}")
            print(f"    단계: {lock.get('현재 단계', '-')[:160]}")
            print(f"    다음: {lock.get('다음 첫 행동', '-')[:160]}")
    print("\n## 열린 PR")
    for p in prs:
        print(f"- #{p['number']} {'DRAFT ' if p['isDraft'] else ''}{p['headRefName']} — {p['title'][:70]} ({p['updatedAt'][:16]})")
    if not prs:
        print("- 없음")
    print("\n## 이 PC의 작업 폴더 (⚠ = 다른 도구가 아직 못 보는 작업)")
    pr_heads = {p["headRefName"] for p in prs}
    for wt in worktrees(repo):
        state = worktree_state(wt["path"], wt.get("branch"))
        has_pr = state["branch"] in pr_heads
        flag = "⚠" if needs_attention(state, has_pr) else " "
        push = ("원격 브랜치 없음(미푸시)" if not state["upstream"]
                else f"미푸시 {state['ahead']} / 뒤처짐 {state['behind']}")
        if state["branch"] and state["branch"] not in PROTECTED and not has_pr and state["ahead_of_develop"]:
            push += f" · 열린 PR 없음(develop보다 {state['ahead_of_develop']}커밋 앞섬)"
        print(f"{flag} {state['branch'] or '(detached)'} @ {wt['path']}")
        print(f"    변경 {state['tracked']} · 새 파일 {state['untracked']} · {push} · 마지막 {state['last'][:60]}")
    print("\n## 마지막 세션 기록")
    for name, when in session_times().items():
        print(f"- {name}: {when}")
    print("\n이어받기: ⚠ 폴더의 브랜치가 상대 도구 것이면 그 도구가 끝났는지(세션 시각·잠금) 확인 후 그 폴더에서 이어서 작업하고 checkpoint로 올린다.")


def ensure_draft_pr(path, branch, step, next_action):
    existing = json.loads(run(["gh", "pr", "list", "--head", branch, "--state", "open", "--json", "number,url"], cwd=path))
    if existing:
        return existing[0]["url"]
    body = (f"## 변경 사항\n- (진행 중 체크포인트) {step}\n\n## 상세\n- 다음: {next_action or '-'}\n"
            "- 도구 간 인계용 드래프트다. 완료 시 제목·본문을 정리하고 준비 완료로 전환한다.\n")
    return run(["gh", "pr", "create", "--draft", "--base", "develop", "--head", branch,
                "--title", f"wip: {step}"[:120], "--body", body], cwd=path).strip()


def checkpoint_one(path, branch, tool, step, next_action, message, trailers, lock_state):
    if not branch or branch in PROTECTED:
        raise RuntimeError(f"{branch or 'detached'} 브랜치에는 체크포인트하지 않는다(기능 브랜치에서 실행)")
    handoff = Path(path) / HANDOFF
    if handoff.exists():
        values = {"상태": lock_state, "도구": "Claude Code" if tool == "claude" else "Codex",
                  "갱신": now_kst(), "브랜치": f"`{branch}` (체크포인트, 드래프트 PR로 노출)",
                  "PR": f"`{branch}`의 드래프트 PR (`gh pr list --head {branch}`)"}
        if step:
            values["현재 단계"] = step
        if next_action:
            values["다음 첫 행동"] = next_action
        original = handoff.read_text(encoding="utf-8")
        handoff.write_text(update_lock(original, values), encoding="utf-8", newline="")
    run(["git", "add", "-A"], cwd=path)
    staged = run(["git", "diff", "--cached", "--name-only"], cwd=path).split()
    if staged:
        text = f"wip({tool}): {message or step or '체크포인트'}"
        if trailers:
            text += "\n\n" + "\n".join(trailers)
        run(["git", "commit", "-q", "-m", text], cwd=path)
    run(["git", "push", "-q", "-u", "origin", branch], cwd=path)
    url = ensure_draft_pr(path, branch, step or message or "체크포인트", next_action)
    print(f"✓ {branch}: 커밋 {len(staged)}개 파일, 푸시, PR {url}")


def checkpoint(repo, args):
    if args.auto:
        prefix = TOOL_PREFIX[args.tool]
        done = 0
        for wt in worktrees(repo):
            branch = wt.get("branch") or ""
            if not branch.startswith(prefix):
                continue
            state = worktree_state(wt["path"], branch)
            if needs_attention(state) or state["untracked"]:
                checkpoint_one(wt["path"], branch, args.tool, args.step, args.next, "자동 체크포인트",
                               args.trailer, "ACTIVE")
                done += 1
        if not done:
            print(f"{prefix}* 작업 폴더에 올릴 변경 없음")
        return
    path = run(["git", "rev-parse", "--show-toplevel"]).strip()
    branch = run(["git", "branch", "--show-current"], cwd=path).strip()
    checkpoint_one(path, branch, args.tool, args.step, args.next, args.message, args.trailer, args.lock)


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("status")
    cp = sub.add_parser("checkpoint")
    cp.add_argument("--tool", choices=["claude", "codex"], required=True)
    cp.add_argument("--step", default="")
    cp.add_argument("--next", default="")
    cp.add_argument("--message", default="")
    cp.add_argument("--trailer", action="append", default=[])
    cp.add_argument("--lock", choices=["ACTIVE", "IDLE"], default="ACTIVE")
    cp.add_argument("--auto", action="store_true")
    args = parser.parse_args()
    repo = run(["git", "rev-parse", "--show-toplevel"]).strip()
    try:
        status(repo) if args.command == "status" else checkpoint(repo, args)
    except RuntimeError as error:
        print(f"중단: {error}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
