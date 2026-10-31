"""Synthetic fixtures only."""

import sys
import unittest
from datetime import date, datetime
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import self_audit
from self_audit import (KST, check_lock, check_score, classify_pr, latest_score, recorded_vps_sha,
                        undeployed_paths)

PLAN = """# 계획

## 2026-10-28 재채점: 57/100점 (추정, ±8점)

## 2026-10-12 기준선: 40/100점 (추정, ±10점)
"""

PROGRESS = """# 진행

## 완료된 작업 — 채팅 정확도를 main `898de04`로 VPS 반영 (2026-10-28)
## 완료된 작업 — 손익 재계산 API를 main `98a9dd3`로 VPS 반영 (2026-10-28)
"""

HANDOFF = """| 항목 | 값 |
|---|---|
| 상태 | {state} |
| 도구 | Claude Code |
| 갱신 | {updated} |
"""


class ScoreTest(unittest.TestCase):
    def test_가장_최근_채점_절을_고른다(self):
        self.assertEqual(latest_score(PLAN), (date(2026, 10, 28), 57))

    def test_7일_이내면_PASS_넘으면_WARN(self):
        self.assertEqual(check_score(PLAN, date(2026, 11, 4))[0], "PASS")
        status, message = check_score(PLAN, date(2026, 11, 5))
        self.assertEqual(status, "WARN")
        self.assertIn("재채점", message)

    def test_채점_절이_없으면_FAIL(self):
        self.assertEqual(check_score("# 빈 계획\n", date(2026, 10, 28))[0], "FAIL")


class ReleaseTest(unittest.TestCase):
    def test_가장_위의_VPS_반영_SHA를_읽는다(self):
        self.assertEqual(recorded_vps_sha(PROGRESS), "898de04")
        self.assertIsNone(recorded_vps_sha("## 완료된 작업 — 문서 정리\n"))

    def test_문서만_바뀐_경로는_미배포로_세지_않는다(self):
        paths = [".claude/PROGRESS.md", "docs/PILOT_QA.md", "AGENTS.md", "chat-service/src/A.java",
                 "scripts/vps/root/buildflow-release", "scripts/agents/self_audit.py", "scripts/qa/local_e2e.py",
                 "scripts/db/test-profit-reconcile-docker.py", ".github/workflows/ci.yml", "scripts/ci/x.py",
                 "scripts/create-admin.ps1", ""]
        self.assertEqual(undeployed_paths(paths), ["chat-service/src/A.java", "scripts/vps/root/buildflow-release"])


class PrTest(unittest.TestCase):
    NOW = datetime(2026, 10, 28, 22, 0, tzinfo=KST)

    def pr(self, conclusion=None, updated="2026-10-28T12:00:00Z"):
        checks = [{"name": "백엔드", "conclusion": conclusion}] if conclusion else []
        return {"number": 9, "title": "합성 PR", "updatedAt": updated, "statusCheckRollup": checks}

    def test_실패_체크는_FAIL(self):
        status, message = classify_pr(self.pr("FAILURE"), self.NOW)
        self.assertEqual(status, "FAIL")
        self.assertIn("백엔드", message)

    def test_오래된_PR은_WARN_진행중은_PASS(self):
        self.assertEqual(classify_pr(self.pr(updated="2026-10-25T00:00:00Z"), self.NOW)[0], "WARN")
        self.assertEqual(classify_pr(self.pr("SUCCESS"), self.NOW)[0], "PASS")


class LockTest(unittest.TestCase):
    NOW = datetime(2026, 10, 28, 22, 0, tzinfo=KST)

    def test_2시간_넘은_ACTIVE는_WARN(self):
        status, _ = check_lock(HANDOFF.format(state="ACTIVE", updated="2026-10-28 19:00 KST"), self.NOW)
        self.assertEqual(status, "WARN")

    def test_IDLE이나_최근_ACTIVE는_PASS(self):
        self.assertEqual(check_lock(HANDOFF.format(state="IDLE", updated="2026-10-20 09:00 KST"), self.NOW)[0], "PASS")
        self.assertEqual(check_lock(HANDOFF.format(state="ACTIVE", updated="2026-10-28 21:30 KST"), self.NOW)[0], "PASS")

    def test_시각을_못_읽으면_WARN(self):
        self.assertEqual(check_lock(HANDOFF.format(state="ACTIVE", updated="모름"), self.NOW)[0], "WARN")


class VpsTest(unittest.TestCase):
    STATUS = """HEAD {head}
NAME                     STATUS
buildflow-auth           Up 3 minutes
buildflow-chat           {chat}
공개 / → 200
무인증 API → {unauth}
"""
    SHA = "8" * 40

    def run_vps(self, head=None, chat="Up 3 minutes", unauth="401"):
        out = self.STATUS.format(head=head or self.SHA, chat=chat, unauth=unauth)
        env = {"BUILDFLOW_VPS_SSH": "deploy@example.invalid", "BUILDFLOW_VPS_KEY": "~/.ssh/example"}
        with patch.dict(self_audit.os.environ, env), patch.object(self_audit, "run", return_value=out):
            return self_audit.check_vps(self.SHA)

    def test_정상이면_모두_PASS(self):
        self.assertTrue(all(status == "PASS" for status, _ in self.run_vps()))

    def test_HEAD_불일치_중단_컨테이너_401_아님은_FAIL(self):
        statuses = [s for s, _ in self.run_vps(head="1" * 40, chat="Exited (1) 2 minutes ago", unauth="200")]
        self.assertEqual(statuses.count("FAIL"), 3)

    def test_실행_중인_컨테이너가_없으면_FAIL(self):
        out = f"HEAD {self.SHA}\nNAME      STATUS\n공개 / → 000\n무인증 API → 000\n"
        env = {"BUILDFLOW_VPS_SSH": "deploy@example.invalid", "BUILDFLOW_VPS_KEY": "~/.ssh/example"}
        with patch.dict(self_audit.os.environ, env), patch.object(self_audit, "run", return_value=out):
            result = self_audit.check_vps(self.SHA)
        self.assertIn(("FAIL", "실행 중인 buildflow 컨테이너 0개"), result)

    def test_접속_정보가_없으면_실행하지_않고_UNVERIFIED(self):
        with patch.dict(self_audit.os.environ, {}, clear=True), patch.object(self_audit, "run") as run:
            result = self_audit.check_vps(self.SHA)
        run.assert_not_called()
        self.assertEqual(result[0][0], "UNVERIFIED")


class KeyPathTest(unittest.TestCase):
    def test_Git_Bash_경로를_Windows_드라이브로_바꾼다(self):
        with patch.object(self_audit.sys, "platform", "win32"):
            self.assertEqual(Path(self_audit.key_path("/c/Users/x/.ssh/k")), Path("C:/Users/x/.ssh/k"))
        with patch.object(self_audit.sys, "platform", "linux"):
            self.assertEqual(self_audit.key_path("/c/Users/x/.ssh/k"), str(Path("/c/Users/x/.ssh/k")))


if __name__ == "__main__":
    unittest.main()
