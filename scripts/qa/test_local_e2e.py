"""local_e2e.py 단위 테스트 — Docker·네트워크 없이 실행한다.

python -I -X utf8 -m unittest discover -s scripts/qa -p "test_*.py"
"""

import json
import subprocess
import tempfile
import unittest
from decimal import Decimal
from pathlib import Path

import local_e2e as e2e


class HostCheckTests(unittest.TestCase):
    def test_allows_loopback_gateway_origins(self):
        self.assertEqual(e2e.check_local_target("http://127.0.0.1:8080"), "http://127.0.0.1:8080")
        self.assertEqual(e2e.check_local_target("http://LOCALHOST:8080/"), "http://localhost:8080")
        self.assertEqual(e2e.check_local_target("http://[::1]:8080"), "http://[::1]:8080")

    def test_rejects_remote_and_lookalike_hosts(self):
        for url in ("https://buildflow.example.org", "http://10.0.0.5:8080", "http://192.168.0.2:8080",
                    "http://127.0.0.2:8080", "http://localhost.evil.com:8080", "http://0.0.0.0:8080",
                    "http://127.0.0.1.nip.io:8080", "ftp://127.0.0.1"):
            with self.subTest(url=url), self.assertRaises(e2e.SetupError):
                e2e.check_local_target(url)

    def test_rejects_userinfo_path_and_query(self):
        for url in ("http://user:pw@127.0.0.1:8080", "http://127.0.0.1:8080/api",
                    "http://127.0.0.1:8080?x=1", "http://127.0.0.1:99999"):
            with self.subTest(url=url), self.assertRaises(e2e.SetupError):
                e2e.check_local_target(url)


class ExpectedValueTests(unittest.TestCase):
    def test_split_payment_states(self):
        self.assertEqual(e2e.expected_payment_state(1100000, []), (Decimal(0), Decimal(1100000), "UNPAID"))
        self.assertEqual(e2e.expected_payment_state(1100000, [(300000, False), (500000, False)]),
                         (Decimal(800000), Decimal(300000), "PARTIAL"))
        self.assertEqual(e2e.expected_payment_state(1100000, [(300000, True), (500000, False)]),
                         (Decimal(500000), Decimal(600000), "PARTIAL"))
        self.assertEqual(e2e.expected_payment_state("1100000.00", [(300000, True), (500000, False), ("600000", False)]),
                         (Decimal(1100000), Decimal(0), "PAID"))
        self.assertEqual(e2e.expected_payment_state(1000, [(1000, True)])[2], "UNPAID")

    def test_outstanding(self):
        self.assertEqual(e2e.expected_outstanding([(1100000, 500000)]),
                         {"totalSalesAmount": Decimal(1100000), "confirmedAmount": Decimal(500000),
                          "outstandingAmount": Decimal(600000), "unpaidCount": 1})
        self.assertEqual(e2e.expected_outstanding([(1100000, 1100000)])["unpaidCount"], 0)

    def test_profit_and_margin_rate(self):
        self.assertEqual(e2e.expected_profit(1500000, 200000),
                         {"totalEstimateAmount": Decimal(1500000), "totalPurchaseAmount": Decimal(200000),
                          "margin": Decimal(1300000), "marginRate": Decimal("86.67")})
        self.assertEqual(e2e.margin_rate(0, 100), Decimal(0))
        self.assertEqual(e2e.margin_rate(1, 100000), Decimal("-999.99"))  # 서버 하한과 같다
        self.assertEqual(e2e.margin_rate(3, 2), Decimal("33.33"))

    def test_revision_total_counts_only_confirmed(self):
        estimates = [
            {"status": "SUPERSEDED", "totalAmount": 1000000},
            {"status": "CONFIRMED", "totalAmount": Decimal("1200000.00")},
            {"status": "CONFIRMED", "totalAmount": 300000},
            {"status": "DRAFT", "totalAmount": 1000},
        ]
        self.assertEqual(e2e.active_estimate_total(estimates), Decimal(1500000))

    def test_dashboard_excludes_bidding(self):
        stats = {"siteProfits": [
            {"status": "BIDDING", "estimateAmount": 500000, "purchaseAmount": 0},
            {"status": "IN_PROGRESS", "estimateAmount": 1000, "purchaseAmount": 400},
            {"status": "COMPLETED", "estimateAmount": None, "purchaseAmount": 10},
        ]}
        self.assertEqual(e2e.dashboard_totals_excluding_bidding(stats), (Decimal(1000), Decimal(410)))

    def test_evidence_summary(self):
        summary = e2e.expected_evidence_summary(
            [(100000, 7), (30000, 7), (20000, None), (5000, 99)], [(7, 130000, 143000), (8, 10, 11)])
        self.assertEqual(summary["purchaseCount"], 4)
        self.assertEqual(summary["linkedPurchaseCount"], 2)
        self.assertEqual(summary["linkedPurchaseAmount"], Decimal(130000))
        self.assertEqual(summary["unlinkedPurchaseCount"], 2)
        self.assertEqual(summary["unlinkedPurchaseAmount"], Decimal(25000))
        self.assertEqual(summary["brokenLinkCount"], 1)
        self.assertEqual(summary["invoiceWithoutPurchaseCount"], 1)
        self.assertEqual(summary["supplyDifference"], Decimal(130010) - Decimal(130000))
        self.assertEqual(summary["totalDifference"], Decimal(143011) - Decimal(130000))

    def test_diff_fields_ignores_decimal_scale(self):
        self.assertEqual(e2e.diff_fields({"a": Decimal("10.00"), "b": "X"}, {"a": Decimal(10), "b": "X"}), [])
        self.assertEqual(len(e2e.diff_fields({"a": 1}, {"a": Decimal(2), "b": None, "c": 3})), 2)

    def test_poll_converges_and_times_out(self):
        values = iter([1, 2, 3])
        now = [0.0]
        ok, value = e2e.poll(lambda: next(values), lambda v: v == 3, timeout=10,
                             clock=lambda: now[0], sleep=lambda s: now.__setitem__(0, now[0] + s))
        self.assertEqual((ok, value), (True, 3))
        now[0] = 0.0
        ok, value = e2e.poll(lambda: 0, lambda v: False, timeout=3,
                             clock=lambda: now[0], sleep=lambda s: now.__setitem__(0, now[0] + s))
        self.assertFalse(ok)
        self.assertGreaterEqual(now[0], 3)


class CredentialTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / ".local" / "qa-admin.env"

    def test_generated_credentials_are_random_and_valid(self):
        first, second = e2e.generate_credentials(), e2e.generate_credentials()
        self.assertNotEqual(first.password, second.password)
        self.assertRegex(first.login_id, r"^qa-admin-[0-9a-f]{8}$")
        self.assertGreaterEqual(len(first.password), 9)
        self.assertNotIn(first.password, repr(first))

    def test_bootstrap_success_saves_file_and_payload_is_single_json_line(self):
        calls = []

        def runner(command, **kwargs):
            calls.append((command, kwargs))
            return subprocess.CompletedProcess(command, 0)

        creds, source = e2e.obtain_credentials(self.path, True, runner=runner)
        self.assertIn("부트스트랩", source)
        command, kwargs = calls[0]
        self.assertEqual(command[:4], ["docker", "exec", "-i", "buildflow-auth"])
        self.assertIn("--app.admin-bootstrap.enabled=true", command)
        self.assertIn("--spring.main.web-application-type=none", command)
        self.assertNotIn(creds.password, " ".join(command))
        self.assertEqual(kwargs["stdout"], subprocess.DEVNULL)
        line = kwargs["input"].decode("utf-8")
        self.assertTrue(line.endswith("\n") and line.count("\n") == 1)
        self.assertEqual(json.loads(line)["loginId"], creds.login_id)
        self.assertEqual(e2e.parse_credential_file(self.path.read_text(encoding="utf-8")), creds)

    def test_existing_file_is_reused_without_bootstrap(self):
        saved = e2e.Credentials("qa-admin-abc", "x" * 32)
        e2e.write_credential_file(self.path, saved)
        creds, source = e2e.obtain_credentials(self.path, True, runner=self.fail_runner)
        self.assertEqual(creds, saved)
        self.assertIn("재사용", source)

    def test_bootstrap_failure_removes_file_and_falls_back_to_prompt(self):
        prompted = e2e.Credentials("family-admin", "y" * 12)
        creds, source = e2e.obtain_credentials(
            self.path, True, runner=lambda c, **k: subprocess.CompletedProcess(c, 1), prompt=lambda: prompted)
        self.assertEqual(creds, prompted)
        self.assertIn("저장 안 함", source)
        self.assertFalse(self.path.exists())

    def test_bootstrap_failure_without_prompt_is_setup_error(self):
        with self.assertRaises(e2e.SetupError):
            e2e.obtain_credentials(self.path, True, runner=lambda c, **k: subprocess.CompletedProcess(c, 1))
        self.assertFalse(self.path.exists())

    def test_invalid_file_is_rejected(self):
        with self.assertRaises(e2e.SetupError):
            e2e.parse_credential_file("BUILDFLOW_QA_LOGIN_ID=a b\nBUILDFLOW_QA_PASSWORD=short\n")

    @staticmethod
    def fail_runner(*args, **kwargs):
        raise AssertionError("bootstrap must not run")


class FakeGateway:
    """경로별 (status, body) 응답을 돌려주는 HTTP 스텁. 호출 기록을 남긴다."""

    def __init__(self, routes):
        self.routes = routes
        self.calls = []

    def __call__(self, method, url, headers, body):
        path = url.split("://", 1)[1].split("/", 1)[1]
        self.calls.append((method, "/" + path, dict(headers), body))
        handler = self.routes.get((method, "/" + path))
        if handler is None:
            return 404, b'{"success":false,"error":"not found"}'
        status, payload = handler(headers) if callable(handler) else handler
        return status, json.dumps(payload).encode("utf-8")


def ok(data):
    return 200, {"success": True, "data": data}


class ApiTests(unittest.TestCase):
    def test_login_sets_bearer_and_logout_clears(self):
        gateway = FakeGateway({
            ("POST", "/api/v1/auth/login"): ok({"accessToken": "tok", "refreshToken": "r", "tokenType": "Bearer",
                                                "expiresIn": 1800000}),
            ("GET", "/api/v1/sites"): lambda h: ok([]) if h.get("Authorization") == "Bearer tok" else (401, {}),
            ("POST", "/api/v1/auth/logout"): ok(None),
        })
        api = e2e.Api("http://127.0.0.1:8080", gateway)
        api.login(e2e.Credentials("qa-admin-1", "p" * 12))
        self.assertNotIn("Authorization", gateway.calls[0][2])
        self.assertEqual(api.call("GET", "/api/v1/sites").status, 200)
        api.logout()
        self.assertIsNone(api.token)

    def test_login_failure_is_setup_error(self):
        api = e2e.Api("http://127.0.0.1:8080", FakeGateway({
            ("POST", "/api/v1/auth/login"): (401, {"success": False, "error": "아이디 또는 비밀번호"})}))
        with self.assertRaises(e2e.SetupError):
            api.login(e2e.Credentials("qa-admin-1", "p" * 12))

    def test_decimal_parsing_and_unexpected_status(self):
        api = e2e.Api("http://127.0.0.1:8080", FakeGateway({
            ("GET", "/x"): ok({"amount": 1100000.50}),
            ("GET", "/y"): (409, {"success": False, "error": "충돌"}),
        }))
        self.assertEqual(api.ok("GET", "/x")["amount"], Decimal("1100000.50"))
        with self.assertRaisesRegex(e2e.ApiError, "409"):
            api.ok("GET", "/y")
        self.assertEqual(e2e.parse_body(b"<html>"), {"error": "JSON 아님"})


def instant_poll(fetch, done, **kwargs):
    value = fetch()
    return done(value), value


class ScenarioTests(unittest.TestCase):
    def reconcile_routes(self, site_id, matched=True, orphan=0):
        totals = {"totalEstimateAmount": 1500000.00, "totalPurchaseAmount": 200000.00,
                  "margin": 1300000.00, "marginRate": 86.67}

        def reconcile_body(apply):
            return ok({"siteId": site_id, "applyRequested": apply, "applied": False, "matched": matched,
                       "before": totals, "source": totals, "estimateEventsToMark": 0, "purchaseEventsToMark": 0,
                       "purchaseProjectionsToRewrite": 0, "orphanEstimateEvents": orphan})

        return {
            ("POST", f"/api/v1/sites/{site_id}/profit/reconcile"): reconcile_body(False),
            ("POST", f"/api/v1/sites/{site_id}/profit/reconcile?apply=true"): reconcile_body(True),
            ("GET", f"/api/v1/sites/{site_id}/profit"): ok({"siteId": site_id, **totals}),
            ("POST", f"/api/v1/sites/{e2e.MISSING_ID}/profit/reconcile"):
                lambda h: (404, {"success": False}) if "Authorization" in h else (401, {}),
            ("GET", "/api/v1/sites"): lambda h: ok([]) if "Authorization" in h else (401, {}),
        }

    def scenarios(self, routes):
        api = e2e.Api("http://127.0.0.1:8080", FakeGateway(routes))
        api.token = "tok"
        rec = e2e.Recorder()
        return e2e.Scenarios(api, rec, "[QA-주간-test]", poller=instant_poll), rec

    def test_reconcile_scenario_passes_when_converged(self):
        s, rec = self.scenarios(self.reconcile_routes(41))
        s.revision_site_id = 41
        s.revision_expected = e2e.expected_profit(1500000, 200000)
        s.reconcile()
        self.assertEqual(rec.failed, 0, rec.render())
        self.assertEqual(len(rec.checks), 7)

    def test_reconcile_scenario_reports_mismatch(self):
        s, rec = self.scenarios(self.reconcile_routes(41, matched=False, orphan=1))
        s.revision_site_id = 41
        s.revision_expected = e2e.expected_profit(1500000, 200000)
        s.reconcile()
        failed = [c.name for c in rec.checks if not c.ok]
        self.assertIn("dry-run 일치(matched)", failed)
        self.assertIn("dry-run 플래그·건수", failed)

    def test_reconcile_skipped_without_s4(self):
        s, rec = self.scenarios({})
        s.reconcile()
        self.assertEqual(rec.failed, 1)

    def test_gateway_auth_checks_restore_token(self):
        s, rec = self.scenarios(self.reconcile_routes(41))
        s.gateway_auth()
        self.assertEqual(rec.failed, 0, rec.render())
        self.assertEqual(s.api.token, "tok")

    def test_run_all_records_interrupted_scenario_and_continues(self):
        s, rec = self.scenarios(self.reconcile_routes(41))
        s.run_all()
        interrupted = [c for c in rec.checks if c.name == "시나리오 중단"]
        self.assertEqual(len(interrupted), 4)  # S1~S4는 생성 경로가 스텁에 없어 중단으로 기록
        self.assertIn("합계:", rec.render())

    def test_prefix_format(self):
        import datetime as dt
        self.assertEqual(e2e.make_prefix(dt.datetime(2026, 10, 28, 9, 5, 7)), "[QA-주간-20261028-090507]")


class MainTests(unittest.TestCase):
    def test_remote_target_is_rejected_before_any_bootstrap(self):
        self.assertEqual(e2e.main(["--base-url", "https://example.org"]), 2)


if __name__ == "__main__":
    unittest.main()
