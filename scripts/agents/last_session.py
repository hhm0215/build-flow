"""Summarize where the latest Codex or Claude Code session for this repo stopped.

Read-only: session logs are never modified. Output goes to stdout only; do not
commit it, because session text can contain secrets or business data.

    python -I -X utf8 scripts/agents/last_session.py codex   [--messages 8]
    python -I -X utf8 scripts/agents/last_session.py claude  [--messages 8]
"""
import argparse
import json
import sys
from datetime import datetime
from pathlib import Path

REPO_MARKER = "build-flow"
TAIL_BYTES = 4 * 1024 * 1024


def latest(paths):
    paths = [p for p in paths if p.is_file()]
    return max(paths, key=lambda p: p.stat().st_mtime) if paths else None


def tail_lines(path):
    size = path.stat().st_size
    with path.open("rb") as f:
        f.seek(max(0, size - TAIL_BYTES))
        data = f.read().decode("utf-8", errors="replace")
    lines = data.splitlines()
    return lines[1:] if size > TAIL_BYTES else lines


def codex_session():
    root = Path.home() / ".codex" / "sessions"
    candidates = []
    for p in root.rglob("rollout-*.jsonl"):
        with p.open("rb") as f:
            head = f.read(8192).decode("utf-8", errors="replace")
        if REPO_MARKER in head:
            candidates.append(p)
    return latest(candidates)


def claude_session():
    root = Path.home() / ".claude" / "projects"
    return latest([p for d in root.glob(f"*{REPO_MARKER}*") for p in d.glob("*.jsonl")])


def codex_events(lines):
    for line in lines:
        try:
            d = json.loads(line)
        except ValueError:
            continue
        p = d.get("payload") or {}
        ts = d.get("timestamp", "")[:19]
        if p.get("type") == "message" and p.get("role") == "assistant":
            text = "".join(c.get("text", "") for c in p.get("content", []) if isinstance(c, dict))
            if text.strip():
                yield ts, "assistant", text
        elif p.get("type") == "message" and p.get("role") == "user":
            text = "".join(c.get("text", "") for c in p.get("content", []) if isinstance(c, dict))
            if text.strip() and not text.lstrip().startswith("<"):
                yield ts, "user", text
        elif p.get("type") in ("function_call", "custom_tool_call"):
            yield ts, "tool", str(p.get("arguments") or p.get("input") or "")[:200]


def claude_events(lines):
    for line in lines:
        try:
            d = json.loads(line)
        except ValueError:
            continue
        ts = d.get("timestamp", "")[:19]
        msg = d.get("message") or {}
        content = msg.get("content")
        if d.get("type") == "user" and isinstance(content, str):
            yield ts, "user", content
        elif d.get("type") == "assistant" and isinstance(content, list):
            for c in content:
                if c.get("type") == "text" and c.get("text", "").strip():
                    yield ts, "assistant", c["text"]
                elif c.get("type") == "tool_use":
                    yield ts, "tool", f"{c.get('name')}: {json.dumps(c.get('input'), ensure_ascii=False)[:200]}"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("tool", choices=["codex", "claude"])
    parser.add_argument("--messages", type=int, default=8)
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")

    path = codex_session() if args.tool == "codex" else claude_session()
    if path is None:
        print(f"{args.tool}: 이 저장소의 세션 로그를 찾지 못했습니다.")
        return 1
    events = list((codex_events if args.tool == "codex" else claude_events)(tail_lines(path)))
    modified = datetime.fromtimestamp(path.stat().st_mtime).strftime("%Y-%m-%d %H:%M")
    print(f"# {args.tool} 마지막 세션: {path.name} (마지막 기록 {modified})")
    users = [e for e in events if e[1] == "user"]
    if users:
        print(f"\n## 마지막 사용자 지시 [{users[-1][0]}]\n{users[-1][2][:800]}")
    print(f"\n## 마지막 에이전트 메시지 {args.messages}개")
    for ts, _, text in [e for e in events if e[1] == "assistant"][-args.messages:]:
        print(f"- [{ts}] {text.strip()[:600]}")
    print("\n## 마지막 도구 호출 5개")
    for ts, _, text in [e for e in events if e[1] == "tool"][-5:]:
        print(f"- [{ts}] {text}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
