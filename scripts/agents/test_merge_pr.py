import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from merge_pr import judge  # noqa: E402


class JudgeTest(unittest.TestCase):
    def test_all_passing(self):
        self.assertEqual(judge([("a", "SUCCESS"), ("b", "SKIPPED"), ("c", "NEUTRAL")]), ("pass", []))

    def test_failure_wins_over_pending(self):
        # 2026-11-01 #291: 실패 1개 + 대기 다수 상태에서 병합된 사고를 막는다
        verdict, problems = judge([("백엔드", "FAILURE"), ("프론트", "PENDING"), ("x", "SUCCESS")])
        self.assertEqual(verdict, "fail")
        self.assertEqual(problems, ["백엔드=FAILURE"])

    def test_pending_blocks(self):
        self.assertEqual(judge([("a", "SUCCESS"), ("b", "IN_PROGRESS")])[0], "pending")

    def test_cancelled_and_unknown_fail(self):
        self.assertEqual(judge([("a", "CANCELLED")])[0], "fail")
        self.assertEqual(judge([("a", "STARTUP_FAILURE")])[0], "fail")

    def test_no_checks_is_not_pass(self):
        self.assertEqual(judge([])[0], "pending")


if __name__ == "__main__":
    unittest.main()
