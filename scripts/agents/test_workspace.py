"""Synthetic fixtures only."""

import unittest

from workspace import needs_attention, parse_lock, update_lock

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

    def test_protected_branch_only_flags_tracked_changes(self):
        self.assertFalse(needs_attention(state(branch="develop", untracked=3)))
        self.assertTrue(needs_attention(state(branch="develop", tracked=1)))


if __name__ == "__main__":
    unittest.main()
