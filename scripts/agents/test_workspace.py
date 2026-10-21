"""Synthetic fixtures only."""

import unittest
import sys
import subprocess
from argparse import Namespace
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import workspace
from workspace import explicit_paths, needs_attention, parse_lock, update_lock

HANDOFF = """# 인계

| 항목 | 값 |
|---|---|
| 상태 | IDLE |
| 도구 | Codex |
| 갱신 | 2026-10-21 08:34 KST |
| 브랜치 | 없음 |
| PR | #99 draft |
| 현재 단계 | 이전 단계 |
| 다음 첫 행동 | 이전 다음 |

## 공통 사용자 지시

- 지시는 그대로 | 남아야 한다
"""


def state(**overrides):
    base = {"tracked": 0, "untracked": 0, "upstream": "origin/x", "ahead": 0, "behind": 0, "last": "", "branch": "claude/x"}
    base.update(overrides)
    return base


class LockTableTests(unittest.TestCase):
    def test_parse_reads_only_lock_fields(self):
        lock = parse_lock(HANDOFF)
        self.assertEqual(lock["상태"], "IDLE")
        self.assertEqual(lock["다음 첫 행동"], "이전 다음")
        self.assertNotIn("항목", lock)

    def test_update_changes_values_and_keeps_rest(self):
        updated = update_lock(HANDOFF, {"상태": "ACTIVE", "현재 단계": "새 단계 | 파이프 포함"})
        lock = parse_lock(updated)
        self.assertEqual(lock["상태"], "ACTIVE")
        self.assertEqual(lock["현재 단계"], "새 단계 / 파이프 포함")
        self.assertEqual(lock["도구"], "Codex")
        self.assertIn("- 지시는 그대로 | 남아야 한다", updated)

    def test_update_preserves_crlf(self):
        crlf = HANDOFF.replace("\n", "\r\n")
        self.assertIn("\r\n| 상태 | ACTIVE |\r\n", update_lock(crlf, {"상태": "ACTIVE"}))


class AttentionTests(unittest.TestCase):
    def test_clean_pushed_branch_is_fine(self):
        self.assertFalse(needs_attention(state()))

    def test_uncommitted_unpushed_or_no_upstream_needs_attention(self):
        self.assertTrue(needs_attention(state(tracked=2)))
        self.assertTrue(needs_attention(state(ahead=1)))
        self.assertTrue(needs_attention(state(upstream="", ahead=None)))

    def test_pushed_tool_branch_without_pr_needs_attention(self):
        self.assertTrue(needs_attention(state(branch="codex/x", ahead_of_develop=1), has_pr=False))
        self.assertFalse(needs_attention(state(branch="codex/x", ahead_of_develop=1), has_pr=True))
        self.assertFalse(needs_attention(state(branch="codex/x", ahead_of_develop=0), has_pr=False))

    def test_protected_branch_flags_untracked_without_staging_it(self):
        self.assertTrue(needs_attention(state(branch="develop", untracked=3)))
        self.assertTrue(needs_attention(state(branch="develop", tracked=1)))

    def test_tool_branch_flags_untracked(self):
        self.assertTrue(needs_attention(state(untracked=1)))


class ExplicitPathTests(unittest.TestCase):
    def test_requires_files_and_rejects_directory_glob_or_parent(self):
        with TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "src").mkdir()
            (root / "src" / "app.py").write_text("pass", encoding="utf-8")
            self.assertEqual(explicit_paths(root, ["src/app.py"]), [":(literal)src/app.py"])
            for paths in ([], ["."], ["src"], ["../other"], ["*.py"]):
                with self.subTest(paths=paths), self.assertRaises(RuntimeError):
                    explicit_paths(root, paths)

    def test_rejects_symlink_outside_worktree(self):
        with TemporaryDirectory() as temp, TemporaryDirectory() as elsewhere:
            root = Path(temp)
            target = Path(elsewhere) / "secret.txt"
            target.write_text("secret", encoding="utf-8")
            link = root / "link.txt"
            try:
                link.symlink_to(target)
            except OSError:
                self.skipTest("symlink creation unavailable")
            with self.assertRaises(RuntimeError):
                explicit_paths(root, ["link.txt"])


class CheckpointSafetyTests(unittest.TestCase):
    def test_other_tool_active_lock_is_not_overwritten(self):
        with TemporaryDirectory() as temp:
            root = Path(temp)
            (root / ".claude").mkdir()
            handoff = root / ".claude" / "HANDOFF.md"
            active = HANDOFF.replace("| 상태 | IDLE |", "| 상태 | ACTIVE |")
            active = active.replace("| 도구 | Codex |", "| 도구 | Claude Code |")
            handoff.write_text(active, encoding="utf-8")
            with patch.object(workspace, "run", return_value=""):
                with self.assertRaisesRegex(RuntimeError, "다른 도구의 ACTIVE"):
                    workspace.checkpoint_one(root, "codex/example", "codex", "테스트", "다음", "메시지", [],
                                             "ACTIVE", ["src/app.py"])
            self.assertEqual(handoff.read_text(encoding="utf-8"), active)

    def test_checkpoint_stages_only_selected_file_and_handoff(self):
        with TemporaryDirectory() as temp:
            root = Path(temp)
            (root / ".claude").mkdir()
            (root / ".claude" / "HANDOFF.md").write_text(HANDOFF, encoding="utf-8")
            (root / "src").mkdir()
            (root / "src" / "app.py").write_text("pass", encoding="utf-8")
            (root / "output").mkdir()
            (root / "output" / "private.txt").write_text("private", encoding="utf-8")

            calls = []

            def fake_run(args, cwd=None, check=True):
                calls.append(args)
                if args[:4] == ["git", "diff", "--cached", "--name-only"]:
                    return "" if len([c for c in calls if c[:2] == ["git", "add"]]) == 0 else ".claude/HANDOFF.md\nsrc/app.py\n"
                return ""

            with patch.object(workspace, "run", side_effect=fake_run), patch.object(
                    workspace, "ensure_draft_pr", return_value="https://example.test/pr"), patch("builtins.print"):
                workspace.checkpoint_one(root, "codex/example", "codex", "테스트", "다음", "메시지", [],
                                         "ACTIVE", ["src/app.py"])

            add_calls = [c for c in calls if c[:2] == ["git", "add"]]
            self.assertEqual(add_calls, [["git", "add", "-A", "--", ":(literal).claude/HANDOFF.md",
                                          ":(literal)src/app.py"]])
            self.assertNotIn("output/private.txt", str(calls))

    def test_auto_does_not_commit_or_push(self):
        args = Namespace(auto=True, tool="claude")
        with patch.object(workspace, "checkpoint_one") as checkpoint:
            workspace.checkpoint(Path("."), args)
        checkpoint.assert_not_called()

    def test_real_git_checkpoint_leaves_unselected_output_untracked(self):
        with TemporaryDirectory() as temp:
            root = Path(temp)

            def git(*args):
                return subprocess.run(["git", *args], cwd=root, check=True, capture_output=True, text=True).stdout

            git("init", "-b", "codex/example")
            git("config", "user.name", "Test")
            git("config", "user.email", "test@example.invalid")
            (root / ".claude").mkdir()
            (root / ".claude" / "HANDOFF.md").write_text(HANDOFF, encoding="utf-8")
            (root / "src").mkdir()
            (root / "src" / "app.py").write_text("old", encoding="utf-8")
            git("add", ".claude/HANDOFF.md", "src/app.py")
            git("commit", "-m", "initial")
            (root / "src" / "app.py").write_text("new", encoding="utf-8")
            (root / "output").mkdir()
            (root / "output" / "private.txt").write_text("private", encoding="utf-8")

            original_run = workspace.run

            def no_network_run(args, cwd=None, check=True):
                if args[:2] == ["git", "push"]:
                    return ""
                return original_run(args, cwd=cwd, check=check)

            with patch.object(workspace, "run", side_effect=no_network_run), patch.object(
                    workspace, "ensure_draft_pr", return_value="https://example.test/pr"), patch("builtins.print"):
                workspace.checkpoint_one(root, "codex/example", "codex", "테스트", "다음", "메시지", [],
                                         "ACTIVE", ["src/app.py"])

            self.assertIn("output/private.txt", git("status", "--porcelain", "-uall"))
            self.assertNotIn("output/private.txt", git("show", "--format=", "--name-only", "HEAD"))


if __name__ == "__main__":
    unittest.main()
