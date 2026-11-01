"""BuildFlow 자가 점검: 진행 기록·릴리스·작업 상태의 기계적 불일치를 읽기 전용으로 찾는다.

    python -I -X utf8 scripts/agents/self_audit.py            # Git·GitHub만 확인
    python -I -X utf8 scripts/agents/self_audit.py --vps      # 배포 래퍼 status로 VPS HEAD도 대조

점검 결과는 PASS / WARN / FAIL / UNVERIFIED 한 줄씩 출력한다. 이 스크립트는 아무것도 수정·커밋·배포하지 않는다.
판단이 필요한 점검(기록 누락, BACKLOG↔PROGRESS 어긋남 해석, 재채점 점수)은 `.claude/agents/self-auditor.md`가 맡는다.
종료 코드: FAIL이 있으면 1, 아니면 0(WARN은 보고만).
"""
import argparse
import json
import os
import re
import subprocess
import sys
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

KST = timezone(timedelta(hours=9))
SCORE_STALE_DAYS = 7
LOCK_STALE_HOURS = 2
PR_STALE_DAYS = 2
# 호스트·키 경로는 저장소에 두지 않는다(로컬 환경변수).
VPS_HOST_ENV = "BUILDFLOW_VPS_SSH"   # 예: deploy계정@서버주소
VPS_KEY_ENV = "BUILDFLOW_VPS_KEY"    # 예: ~/.ssh/<키 파일>
# 문서·기록·에이전트/QA·CI·DB 운영 스크립트(수동 실행)만 바뀐 커밋은 VPS에 배포하지 않아도 된다.
# scripts/vps/(배포 래퍼)·서비스·compose·frontend 변경은 배포 대상이다.
# create-admin.ps1은 로컬 Windows 스택에서만 쓰는 관리자 생성 도구라 VPS 배포 대상이 아니다.
DOC_ONLY = re.compile(r"^(\.claude/|\.github/|docs/|scripts/agents/|scripts/qa/|scripts/ci/|scripts/db/|scripts/create-admin\.ps1$|AGENTS\.md$|CLAUDE\.md$|README)")


def run(args, cwd=None, timeout=60):
    result = subprocess.run(args, cwd=cwd, capture_output=True, text=True, encoding="utf-8",
                            errors="replace", timeout=timeout)
    if result.returncode != 0:
        raise RuntimeError(f"{' '.join(args[:3])} 실패: {result.stderr.strip()[:200]}")
    return result.stdout


def latest_score(plan_text):
    """DELIVERY_PLAN의 가장 최근 채점 절 (날짜, 점수). 없으면 (None, None)."""
    best = (None, None)
    for m in re.finditer(r"^## (\d{4}-\d{2}-\d{2}) (?:재채점|기준선): (\d+)/100", plan_text, re.M):
        day = date.fromisoformat(m.group(1))
        if best[0] is None or day > best[0]:
            best = (day, int(m.group(2)))
    return best


def check_score(plan_text, today):
    day, score = latest_score(plan_text)
    if day is None:
        return "FAIL", "DELIVERY_PLAN에 'YYYY-MM-DD 재채점|기준선: N/100' 절이 없음"
    age = (today - day).days
    if age > SCORE_STALE_DAYS:
        return "WARN", f"진행률 {score}/100 마지막 채점 {day} ({age}일 전) — 재채점 필요"
    return "PASS", f"진행률 {score}/100 (채점 {day}, {age}일 전)"


def recorded_vps_sha(progress_text):
    """PROGRESS 완료 기록 중 가장 위(최신)의 'main `sha`…VPS 반영' 짧은 SHA."""
    for line in progress_text.splitlines():
        if line.startswith("## ") and "VPS" in line and "반영" in line:
            m = re.search(r"main `([0-9a-f]{7,40})`", line)
            if m:
                return m.group(1)
    return None


def undeployed_paths(changed_paths):
    return sorted(p for p in changed_paths if p and not DOC_ONLY.match(p))


def check_release(repo, progress_text):
    recorded = recorded_vps_sha(progress_text)
    if not recorded:
        return "WARN", "PROGRESS에 'main `SHA`로 VPS 반영' 기록이 없음", None
    try:
        full = run(["git", "rev-parse", recorded], cwd=repo).strip()
        changed = run(["git", "diff", "--name-only", f"{full}..origin/main"], cwd=repo).splitlines()
    except RuntimeError as e:
        return "UNVERIFIED", f"기록된 VPS SHA {recorded} 대조 실패: {e}", None
    code = undeployed_paths(changed)
    if code:
        sample = ", ".join(code[:3]) + (" 외" if len(code) > 3 else "")
        return "WARN", f"main에 VPS 미반영 코드 변경 {len(code)}개 파일 (기록 {recorded}; {sample})", full
    return "PASS", f"main의 코드 변경이 모두 VPS 기록 {recorded}에 반영됨", full


def check_develop_ahead(repo):
    try:
        changed = run(["git", "diff", "--name-only", "origin/main...origin/develop"], cwd=repo).splitlines()
    except RuntimeError as e:
        return "UNVERIFIED", str(e)
    code = undeployed_paths(changed)
    if code:
        return "WARN", f"develop에 main 미릴리스 코드 변경 {len(code)}개 파일 — 릴리스 PR 검토"
    if changed:
        return "PASS", f"develop↔main 차이는 문서 {len(changed)}개뿐"
    return "PASS", "develop과 main 내용 동일"


def classify_pr(pr, now):
    """열린 PR 1건 → (상태, 설명)."""
    checks = pr.get("statusCheckRollup") or []
    failed = [c.get("name") or c.get("context") for c in checks
              if (c.get("conclusion") or c.get("state") or "").upper() in ("FAILURE", "ERROR", "TIMED_OUT", "CANCELLED")]
    updated = datetime.fromisoformat(pr["updatedAt"].replace("Z", "+00:00"))
    label = f"#{pr['number']} {pr['title'][:40]}"
    if failed:
        return "FAIL", f"{label} — 실패 체크: {', '.join(failed[:3])}"
    if now - updated > timedelta(days=PR_STALE_DAYS):
        return "WARN", f"{label} — {(now - updated).days}일간 갱신 없음"
    return "PASS", f"{label} — 진행 중"


def check_prs(repo, now):
    try:
        prs = json.loads(run(["gh", "pr", "list", "--state", "open", "--json",
                              "number,title,updatedAt,statusCheckRollup"], cwd=repo))
    except (RuntimeError, ValueError, OSError) as e:
        return [("UNVERIFIED", f"열린 PR 조회 실패: {str(e)[:120]}")]
    if not prs:
        return [("PASS", "열린 PR 없음")]
    return [classify_pr(pr, now) for pr in prs]


def check_lock(handoff_text, now):
    fields = {}
    for line in handoff_text.splitlines():
        m = re.match(r"^\|\s*([^|]+?)\s*\|\s*(.*?)\s*\|\s*$", line)
        if m:
            fields.setdefault(m.group(1), m.group(2))
    state, updated = fields.get("상태"), fields.get("갱신", "")
    m = re.match(r"(\d{4}-\d{2}-\d{2} \d{2}:\d{2})", updated)
    if not m:
        return "WARN", f"HANDOFF 잠금 갱신 시각을 읽을 수 없음: {updated!r}"
    at = datetime.strptime(m.group(1), "%Y-%m-%d %H:%M").replace(tzinfo=KST)
    hours = (now - at).total_seconds() / 3600
    if state == "ACTIVE" and hours > LOCK_STALE_HOURS:
        return "WARN", f"HANDOFF {fields.get('도구')} ACTIVE가 {hours:.1f}시간 갱신 없음 — 종료 시 IDLE 전환 누락 가능"
    return "PASS", f"HANDOFF {state} ({fields.get('도구')}, {hours:.1f}시간 전)"


def check_worktrees(repo):
    try:
        merged = set(run(["git", "branch", "--merged", "origin/develop", "--format=%(refname:short)"],
                         cwd=repo).split())
        porcelain = run(["git", "worktree", "list", "--porcelain"], cwd=repo)
    except RuntimeError as e:
        return "UNVERIFIED", str(e)
    leftover = []
    path = None
    first = True  # 첫 항목은 메인 체크아웃이라 정리 대상이 아니다.
    for line in porcelain.splitlines():
        if line.startswith("worktree "):
            path, is_main, first = line[9:], first, False
        elif line.startswith("branch refs/heads/"):
            branch = line[18:]
            if branch in merged and branch not in ("develop", "main") and not is_main:
                leftover.append(branch)
    if leftover:
        return "WARN", (f"develop에 병합된 브랜치의 작업 폴더 {len(leftover)}개 — 정리 후보(다른 도구 소유면 그 도구가 정리): "
                        f"{', '.join(sorted(leftover)[:6])}{' 외' if len(leftover) > 6 else ''}")
    return "PASS", "병합 후 남은 작업 폴더 없음"


def key_path(value):
    """Git Bash가 넘긴 /c/Users/... 형식도 Windows 파이썬이 열 수 있게 바꾼다."""
    m = re.match(r"^/([a-zA-Z])/(.*)$", value)
    if m and sys.platform == "win32":
        value = f"{m.group(1).upper()}:/{m.group(2)}"
    return str(Path(value).expanduser())


def check_vps(expected_full_sha):
    host, key = os.environ.get(VPS_HOST_ENV), os.environ.get(VPS_KEY_ENV)
    if not host or not key:
        return [("UNVERIFIED", f"VPS 점검 생략 — 환경변수 {VPS_HOST_ENV}·{VPS_KEY_ENV} 미설정")]
    try:
        out = run(["ssh", "-i", key_path(key), "-o", "BatchMode=yes", "-o", "ConnectTimeout=15",
                   host, "sudo", "buildflow-release", "status"], timeout=90)
    except (RuntimeError, OSError, subprocess.TimeoutExpired) as e:
        return [("UNVERIFIED", f"VPS status 실패: {str(e)[:120]}")]
    results = []
    m = re.search(r"^HEAD ([0-9a-f]{40})", out, re.M)
    head = m.group(1) if m else None
    if not head:
        results.append(("UNVERIFIED", "VPS HEAD를 읽지 못함"))
    elif expected_full_sha and head != expected_full_sha:
        results.append(("FAIL", f"VPS HEAD {head[:7]} ≠ PROGRESS 기록 {expected_full_sha[:7]}"))
    else:
        results.append(("PASS", f"VPS HEAD {head[:7]} = PROGRESS 기록"))
    rows = [line for line in out.splitlines() if line.startswith("buildflow-")]
    down = [line.split()[0] for line in rows if " Up " not in f" {line} "]
    if not rows:
        # compose ps는 실행 중인 컨테이너만 보여 주므로 목록이 비면 전부 멈춘 것이다.
        results.append(("FAIL", "실행 중인 buildflow 컨테이너 0개"))
    elif down:
        results.append(("FAIL", f"Up이 아닌 컨테이너: {', '.join(down)}"))
    else:
        results.append(("PASS", f"VPS 컨테이너 {len(rows)}개 모두 Up"))
    for probe, want in (("공개 /", "200"), ("무인증 API", "401")):
        m = re.search(re.escape(probe) + r" → (\d{3})", out)
        if not m:
            results.append(("UNVERIFIED", f"{probe} 결과 없음"))
        else:
            results.append(("PASS" if m.group(1) == want else "FAIL", f"{probe} {m.group(1)} (기대 {want})"))
    return results


def main(argv=None):
    parser = argparse.ArgumentParser(description="BuildFlow 자가 점검(읽기 전용)")
    parser.add_argument("--repo", default=".")
    parser.add_argument("--vps", action="store_true", help="배포 래퍼 status로 VPS HEAD·health 대조")
    parser.add_argument("--no-fetch", action="store_true")
    args = parser.parse_args(argv)
    repo = args.repo
    now = datetime.now(KST)

    results = []
    if not args.no_fetch:
        try:
            run(["git", "fetch", "-q", "origin"], cwd=repo, timeout=120)
        except (RuntimeError, subprocess.TimeoutExpired) as e:
            results.append(("UNVERIFIED", f"git fetch 실패 — 로컬 origin 기준: {str(e)[:100]}"))

    def show(ref, path):
        return run(["git", "show", f"{ref}:{path}"], cwd=repo)

    plan, progress, handoff = (show("origin/develop", p) for p in
                               ("docs/DELIVERY_PLAN.md", ".claude/PROGRESS.md", ".claude/HANDOFF.md"))
    results.append(check_score(plan, now.date()))
    status, message, full = check_release(repo, progress)
    results.append((status, message))
    results.append(check_develop_ahead(repo))
    results.extend(check_prs(repo, now))
    results.append(check_lock(handoff, now))
    results.append(check_worktrees(repo))
    if args.vps:
        results.extend(check_vps(full))

    print(f"BuildFlow 자가 점검 {now:%Y-%m-%d %H:%M KST}")
    for status, message in results:
        print(f"- {status}: {message}")
    counts = {s: sum(1 for r in results if r[0] == s) for s in ("PASS", "WARN", "FAIL", "UNVERIFIED")}
    print("요약: " + " ".join(f"{k} {v}" for k, v in counts.items()))
    return 1 if counts["FAIL"] else 0


if __name__ == "__main__":
    sys.exit(main())
