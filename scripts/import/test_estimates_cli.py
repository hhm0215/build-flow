"""견적 적재 CLI 테스트: 로컬 목(mock) Gateway(http.server)만 사용한다. 실제 스택에는 쓰지 않는다."""

from __future__ import annotations

import contextlib
import datetime as dt
import io
import json
import os
import shutil
import sys
import tempfile
import threading
import unittest
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import estimates  # noqa: E402
import import_common  # noqa: E402
from fixture_books import sample_cells, xls_bytes, xlsx_bytes  # noqa: E402

ACCESS = "test-access-token-value-0001"
ROTATED = "test-access-token-value-0002"
REFRESH = "test-refresh-token-value-0001"
ITEMS = [
    {"no": 1, "name": "합성 자재", "spec": "EA", "qty": 2, "price": 1500, "amount": 3000},
    {"no": 2, "name": "합성 시공", "amount": 5000},
]


class FakeGateway:
    """필요한 엔드포인트만 흉내 내는 메모리 서버."""

    def __init__(self, expired_first: bool = False):
        self.requests: list[tuple[str, str, object, str]] = []
        self.clients: list[dict] = []
        self.sites: list[dict] = []
        self.estimates: list[dict] = []
        self.valid_tokens = {ROTATED} if expired_first else {ACCESS}
        gateway = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):  # 조용히
                pass

            def _reply(self, status, data=None, error=None):
                body = json.dumps({"success": error is None, "data": data, "error": error}).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def _handle(self, method):
                length = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(length)) if length else None
                auth = self.headers.get("Authorization", "")
                parsed = urllib.parse.urlparse(self.path)
                gateway.requests.append((method, parsed.path, body, auth))
                if parsed.path == "/api/v1/auth/refresh":
                    if body == {"refreshToken": REFRESH}:
                        return self._reply(200, {"accessToken": ROTATED, "refreshToken": "rotated-refresh"})
                    return self._reply(401, error="bad refresh")
                if auth.removeprefix("Bearer ") not in gateway.valid_tokens:
                    return self._reply(401, error="unauthorized")
                return gateway.route(self, method, parsed, body)

            def do_GET(self):
                self._handle("GET")

            def do_POST(self):
                self._handle("POST")

            def do_PATCH(self):
                self._handle("PATCH")

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.base_url = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def route(self, handler, method, parsed, body):
        path = parsed.path
        if path == "/api/v1/clients" and method == "GET":
            return handler._reply(200, self.clients)
        if path == "/api/v1/clients" and method == "POST":
            row = {"id": 100 + len(self.clients), "companyName": body["companyName"], "archivedAt": None}
            self.clients.append(row)
            return handler._reply(201, row)
        if path == "/api/v1/sites" and method == "GET":
            return handler._reply(200, self.sites)
        if path == "/api/v1/sites" and method == "POST":
            client = next((c for c in self.clients if c["id"] == body.get("clientId")), None)
            row = {"id": 200 + len(self.sites), "siteName": body["siteName"], "client": client,
                   "memo": body.get("memo"), "startDate": body.get("startDate"), "endDate": body.get("endDate"),
                   "status": body.get("status") or "IN_PROGRESS", "archivedAt": None}
            self.sites.append(row)
            return handler._reply(201, row)
        if path.startswith("/api/v1/sites/"):
            parts = path.split("/")
            row = next((s for s in self.sites if s["id"] == int(parts[4])), None)
            if row is None:
                return handler._reply(404, error="not found")
            if method == "PATCH" and parts[-1] == "status":
                if row.get("status") == "BIDDING" and body["status"] != "IN_PROGRESS":
                    return handler._reply(400, error="transition not allowed")
                row["status"] = body["status"]
            return handler._reply(200, row)
        if path == "/api/v1/estimates" and method == "GET":
            site_id = int(urllib.parse.parse_qs(parsed.query)["siteId"][0])
            return handler._reply(200, [e for e in self.estimates if e["siteId"] == site_id])
        if path == "/api/v1/estimates" and method == "POST":
            total = sum(float(i["quantity"]) * float(i["unitPrice"]) for i in body["items"])
            row = {"id": 300 + len(self.estimates), "status": "DRAFT", "totalAmount": total, **body}
            self.estimates.append(row)
            return handler._reply(201, row)
        if path.startswith("/api/v1/estimates/"):
            parts = path.split("/")
            row = next(e for e in self.estimates if e["id"] == int(parts[4]))
            if method == "PATCH" and parts[-1] == "confirm":
                row["status"] = "CONFIRMED"
            return handler._reply(200, row)
        return handler._reply(404, error="not found")

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *exc):
        self.server.shutdown()
        self.server.server_close()

    def posts(self, path):
        return [r for r in self.requests if r[0] == "POST" and r[1] == path]


class CliBase(unittest.TestCase):
    """임시 현장 폴더·적재 기록·CLI 실행 도우미(테스트 없음)."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="bf-import-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.site_dir = self.tmp / "2025" / "합성현장A"
        self.site_dir.mkdir(parents=True)
        self.ledger = self.tmp / "ledger" / "import-ledger.jsonl"

    def write(self, name, data, mtime=None):
        path = self.site_dir / name
        path.write_bytes(data)
        if mtime:
            stamp = dt.datetime.combine(mtime, dt.time(12, 0)).timestamp()
            os.utime(path, (stamp, stamp))
        return path

    def run_cli(self, *args, env=None):
        out, err = io.StringIO(), io.StringIO()
        environ = {k: v for k, v in os.environ.items()
                   if k not in (import_common.ACCESS_ENV, import_common.REFRESH_ENV)}
        environ.update(env or {})
        with mock.patch.dict(os.environ, environ, clear=True), \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = estimates.main([*map(str, args), "--ledger", str(self.ledger)])
        return code, out.getvalue(), err.getvalue()

    def ledger_rows(self):
        if not self.ledger.exists():
            return []
        return [json.loads(line) for line in self.ledger.read_text(encoding="utf-8").splitlines() if line]


class CliTest(CliBase):
    def test_dry_run_does_not_call_api(self):
        self.write("견적1.xlsx", xlsx_bytes(sample_cells(ITEMS, 8000)))
        factory = mock.Mock(side_effect=AssertionError("dry-run에서 API 생성 금지"))
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = estimates.main([str(self.site_dir), "--ledger", str(self.ledger)], api_factory=factory)
        self.assertEqual(code, 0)
        factory.assert_not_called()
        self.assertIn("미리보기만", out.getvalue())
        self.assertFalse(self.ledger.exists())

    def test_dry_run_with_server_sends_nothing(self):
        self.write("견적1.xlsx", xlsx_bytes(sample_cells(ITEMS, 8000)))
        with FakeGateway() as gateway:
            code, out, _ = self.run_cli(self.site_dir, "--base-url", gateway.base_url,
                                        env={import_common.ACCESS_ENV: ACCESS})
        self.assertEqual(code, 0)
        self.assertEqual(gateway.requests, [])

    def test_apply_creates_client_site_estimate_and_is_idempotent(self):
        mtime = dt.date(2025, 6, 7)
        self.write("견적1.xlsx", xlsx_bytes(sample_cells(ITEMS, 8000, year="2025년")), mtime)
        self.write("견적2.xls", xls_bytes(sample_cells(ITEMS, 8000, date=dt.date(2025, 7, 1))))
        with FakeGateway() as gateway:
            env = {import_common.ACCESS_ENV: ACCESS}
            code, out, err = self.run_cli(self.site_dir, "--apply", "--base-url", gateway.base_url, env=env)
            self.assertEqual(code, 0, out + err)
            self.assertEqual(len(gateway.posts("/api/v1/clients")), 1)
            self.assertEqual(len(gateway.posts("/api/v1/sites")), 1)  # 같은 현장은 재사용
            self.assertEqual(len(gateway.posts("/api/v1/estimates")), 2)
            site = gateway.sites[0]
            self.assertEqual(site["siteName"], "합성현장A")
            self.assertEqual(site["client"]["companyName"], "합성거래처")
            self.assertIsNone(site["startDate"])  # 시작·종료일은 추정하지 않음
            self.assertIsNone(site["endDate"])
            self.assertEqual(site["memo"], "견적서 표기 연도 2025")
            first = gateway.estimates[0]
            self.assertEqual(first["estimateDate"], "2025-06-07")
            self.assertEqual(first["items"][1], {"itemName": "합성 시공", "unit": "식", "quantity": "1",
                                                 "unitPrice": "5000"})
            self.assertTrue(all(e["status"] == "DRAFT" for e in gateway.estimates))  # 기본은 확정 안 함
            rows = self.ledger_rows()
            self.assertEqual(len(rows), 2)
            by_name = {r["fileName"]: r for r in rows}
            self.assertEqual(by_name["견적1.xlsx"]["dateSource"], "file-mtime")
            self.assertTrue(by_name["견적2.xls"]["dateSource"].startswith("file-cell"))
            self.assertEqual(len(by_name["견적1.xlsx"]["sha256"]), 64)

            before = len(gateway.requests)
            code, out, _ = self.run_cli(self.site_dir, "--apply", "--base-url", gateway.base_url, env=env)
            self.assertEqual(code, 0)
            self.assertIn("이미 적재", out)
            self.assertEqual(len(gateway.posts("/api/v1/estimates")), 2)
            self.assertFalse(any(r[0] == "POST" for r in gateway.requests[before:]))

    def test_existing_site_memo_not_overwritten_and_marker_recovery(self):
        self.write("견적1.xlsx", xlsx_bytes(sample_cells(ITEMS, 8000, year="2025년")))
        with FakeGateway() as gateway:
            gateway.sites.append({"id": 9, "siteName": "합성현장A", "client": None, "memo": "사용자 메모",
                                  "archivedAt": None})
            env = {import_common.ACCESS_ENV: ACCESS}
            self.assertEqual(self.run_cli(self.site_dir, "--apply", "--base-url", gateway.base_url, env=env)[0], 0)
            self.assertEqual(gateway.posts("/api/v1/sites"), [])
            self.assertEqual(gateway.sites[0]["memo"], "사용자 메모")
            # 적재 기록이 사라져도 견적 메모의 표식으로 중복 생성을 막는다
            self.ledger.unlink()
            self.assertEqual(self.run_cli(self.site_dir, "--apply", "--base-url", gateway.base_url, env=env)[0], 0)
            self.assertEqual(len(gateway.posts("/api/v1/estimates")), 1)
            self.assertEqual(len(self.ledger_rows()), 1)

    def test_apply_refused_when_any_error(self):
        self.write("좋음.xlsx", xlsx_bytes(sample_cells(ITEMS, 8000)))
        self.write("불일치.xlsx", xlsx_bytes(sample_cells(ITEMS, 9000)))
        with FakeGateway() as gateway:
            code, out, err = self.run_cli(self.site_dir, "--apply", "--base-url", gateway.base_url,
                                          env={import_common.ACCESS_ENV: ACCESS})
        self.assertEqual(code, 1)
        self.assertIn("ERROR", out)
        self.assertIn("거부", err)
        self.assertEqual(gateway.requests, [])

    def test_confirm_latest_only(self):
        self.write("견적1.xlsx", xlsx_bytes(sample_cells(ITEMS, 8000)), dt.date(2025, 1, 1))
        self.write("견적2.xlsx", xlsx_bytes(sample_cells(ITEMS[:1], 3000)), dt.date(2025, 2, 1))
        with FakeGateway() as gateway:
            code, out, err = self.run_cli(self.site_dir, "--apply", "--confirm-latest", "--base-url",
                                          gateway.base_url, env={import_common.ACCESS_ENV: ACCESS})
            self.assertEqual(code, 0, out + err)
            statuses = {e["title"]: e["status"] for e in gateway.estimates}
        self.assertEqual(statuses, {"견적1": "DRAFT", "견적2": "CONFIRMED"})

    def test_refresh_rotation_and_tokens_never_printed(self):
        self.write("견적1.xlsx", xlsx_bytes(sample_cells(ITEMS, 8000)))
        with FakeGateway(expired_first=True) as gateway:
            code, out, err = self.run_cli(self.site_dir, "--apply", "--base-url", gateway.base_url,
                                          env={import_common.ACCESS_ENV: ACCESS, import_common.REFRESH_ENV: REFRESH})
            self.assertEqual(code, 0, out + err)
            self.assertEqual(len(gateway.posts("/api/v1/auth/refresh")), 1)
            self.assertEqual(len(gateway.posts("/api/v1/estimates")), 1)
        ledger_text = self.ledger.read_text(encoding="utf-8")
        for secret in (ACCESS, ROTATED, REFRESH, "rotated-refresh"):
            self.assertNotIn(secret, out + err)
            self.assertNotIn(secret, ledger_text)

    def test_unauthorized_without_refresh_stops(self):
        self.write("견적1.xlsx", xlsx_bytes(sample_cells(ITEMS, 8000)))
        with FakeGateway(expired_first=True) as gateway:
            code, out, err = self.run_cli(self.site_dir, "--apply", "--base-url", gateway.base_url,
                                          env={import_common.ACCESS_ENV: ACCESS})
        self.assertEqual(code, 1)
        self.assertIn("인증 실패", err)
        self.assertNotIn(ACCESS, out + err)

    def test_apply_requires_token_env(self):
        self.write("견적1.xlsx", xlsx_bytes(sample_cells(ITEMS, 8000)))
        code, _, err = self.run_cli(self.site_dir, "--apply", "--base-url", "http://127.0.0.1:9")
        self.assertEqual(code, 2)
        self.assertIn(import_common.ACCESS_ENV, err)

    def test_non_local_base_url_rejected(self):
        self.write("견적1.xlsx", xlsx_bytes(sample_cells(ITEMS, 8000)))
        for url in ("https://example.org", "http://10.0.0.5:3000", "http://localhost:3000/api"):
            with self.subTest(url=url):
                code, _, err = self.run_cli(self.site_dir, "--base-url", url)
                self.assertEqual(code, 2)

    def test_ledger_inside_repo_rejected(self):
        with self.assertRaises(import_common.ImportSetupError):
            import_common.Ledger(import_common.REPO_ROOT / "import-ledger.jsonl")

    def test_folder_year_mismatch_warns_and_hidden_skipped(self):
        self.write("견적1.xlsx", xlsx_bytes(sample_cells(ITEMS, 8000, year="2024년")))
        self.write("~$견적1.xlsx", b"lock")
        code, out, _ = self.run_cli(self.site_dir)
        self.assertEqual(code, 0)
        self.assertIn("폴더 연도(2025)와 시트 표기 연도(2024)가 다름", out)
        self.assertIn("숨김·임시 파일 1개", out)

    def test_source_files_unchanged(self):
        path = self.write("견적1.xlsx", xlsx_bytes(sample_cells(ITEMS, 8000)), dt.date(2025, 1, 1))
        before = (path.read_bytes(), path.stat().st_mtime)
        with FakeGateway() as gateway:
            self.run_cli(self.site_dir, "--apply", "--base-url", gateway.base_url,
                         env={import_common.ACCESS_ENV: ACCESS})
        self.assertEqual((path.read_bytes(), path.stat().st_mtime), before)
        self.assertEqual(sorted(p.name for p in self.site_dir.iterdir()), ["견적1.xlsx"])

    def test_duplicate_copy_loaded_once(self):
        data = xlsx_bytes(sample_cells(ITEMS, 8000))
        self.write("견적1.xlsx", data)
        self.write("견적1 - 복사본.xlsx", data)
        with FakeGateway() as gateway:
            code, out, err = self.run_cli(self.site_dir, "--apply", "--base-url", gateway.base_url,
                                          env={import_common.ACCESS_ENV: ACCESS})
            self.assertEqual(code, 0, out + err)
            self.assertEqual(len(gateway.posts("/api/v1/estimates")), 1)


if __name__ == "__main__":
    unittest.main()
