"""매출 세금계산서 목록 가져오기 테스트. 합성 목록(업체·품목·금액 모두 가짜)과 로컬 목 Gateway만 쓴다."""

from __future__ import annotations

import datetime as dt
import json
import os
import shutil
import subprocess
import sys
import unittest
import urllib.parse
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import estimates  # noqa: E402
import import_common  # noqa: E402
import taxes  # noqa: E402
import test_estimates_cli as cli_base  # noqa: E402
from estimate_sheet import SheetFormatError  # noqa: E402
from fixture_books import xls_bytes, xlsx_bytes  # noqa: E402
from tax_sheet import parse_tax_list  # noqa: E402
from test_estimates_cli import ACCESS, FakeGateway  # noqa: E402

BROWSER_APPLY = Path(__file__).resolve().parent / "browser_apply.js"
NODE = shutil.which("node")

NEW_HEADER = ["작성일자", "상호", "합계금액", "공급가액", "세액", "품목명"]
OLD_HEADER = ["작성일자", "상호", "대표자명", "합계금액", "공급가액", "품목일자", "품목명"]


def tax_cells(rows, *, header=None, summary_supply=None, summary_tax=None, header_row=5,
              summary=True):
    """rows: (작성일자, 상호, 공급가액, 세액, 품목명). 요약 값은 홈택스처럼 쉼표 글자."""
    header = header or NEW_HEADER
    cells = {(0, 0): "사업자 등록번호", (0, 1): "000-00-00000", (0, 2): "상호", (0, 3): "합성공급자",
             (4, 0): "매출 전자(수정) 세금계산서 목록조회"}
    supply_sum = sum(Decimal(str(r[2])) for r in rows)
    tax_sum = sum(Decimal(str(r[3])) for r in rows)
    if summary:
        cells.update({(2, 0): "총 합계금액", (2, 1): f"{supply_sum + tax_sum:,}", (2, 2): "총 공급가액",
                      (2, 3): f"{(summary_supply if summary_supply is not None else supply_sum):,}",
                      (2, 4): "총 세액", (2, 5): f"{(summary_tax if summary_tax is not None else tax_sum):,}"})
    for c, label in enumerate(header):
        cells[(header_row, c)] = label
    for offset, (date, name, supply, tax, item) in enumerate(rows):
        values = {"작성일자": date, "상호": name, "대표자명": "합성대표", "합계금액": supply + tax,
                  "공급가액": supply, "세액": tax, "품목일자": date, "품목명": item}
        for c, label in enumerate(header):
            if values.get(label) is not None:
                cells[(header_row + 1 + offset, c)] = values[label]
    return cells


ROWS = [
    ("2025-03-02", "(주)합성건설", 100000, 10000, "합성현장A 철골 공사"),
    ("2025-03-05", "합성상사", 50000, 5000, "합성현장B 자재"),
]


class TaxSheetTest(unittest.TestCase):
    def test_parses_new_and_old_layouts_xls_and_xlsx(self):
        for header in (NEW_HEADER, OLD_HEADER):
            for maker in (xlsx_bytes, xls_bytes):
                with self.subTest(header=len(header), maker=maker.__name__):
                    parsed = parse_tax_list(maker(tax_cells(ROWS, header=header), "sheet1"))
                    self.assertEqual(parsed.errors, [])
                    self.assertEqual(len(parsed.rows), 2)
                    first = parsed.rows[0]
                    self.assertEqual(first.row, 7)
                    self.assertEqual(first.issue_date, dt.date(2025, 3, 2))
                    self.assertEqual((first.supply, first.tax, first.total),
                                     (Decimal(100000), Decimal(10000), Decimal(110000)))
                    self.assertEqual(first.item_name, "합성현장A 철골 공사")
                    self.assertEqual(parsed.summary["supply"], Decimal(150000))

    def test_columns_mapped_by_label_not_position(self):
        shuffled = ["품목명", "세액", "공급가액", "상호", "합계금액", "작성일자"]
        parsed = parse_tax_list(xlsx_bytes(tax_cells(ROWS, header=shuffled, header_row=8)))
        self.assertEqual(parsed.errors, [])
        self.assertEqual(parsed.header_row, 9)
        self.assertEqual(parsed.rows[1].counterparty, "합성상사")
        self.assertEqual(parsed.rows[1].supply, Decimal(50000))

    def test_summary_mismatch_is_error(self):
        parsed = parse_tax_list(xlsx_bytes(tax_cells(ROWS, summary_supply=999)))
        self.assertTrue(any("총 공급가액" in e for e in parsed.errors))
        parsed = parse_tax_list(xlsx_bytes(tax_cells(ROWS, summary_tax=1)))
        self.assertTrue(any("총 세액" in e for e in parsed.errors))
        parsed = parse_tax_list(xlsx_bytes(tax_cells(ROWS, summary=False)))
        self.assertTrue(any("찾을 수 없음" in e for e in parsed.errors))

    def test_amount_rules(self):
        rows = [("2025-01-01", "합성A", 1000, 0, "영세"),            # 10% 아님 → 경고만
                ("2025-01-02", "합성B", 1000.123, 100, "소수"),       # 소수 3자리 → ERROR
                ("2025-01-03", "합성C", 10 ** 13, 10 ** 12, "큼")]    # 14자리 → ERROR
        parsed = parse_tax_list(xlsx_bytes(tax_cells(rows)))
        self.assertTrue(parsed.rows[0].warnings and not parsed.rows[0].errors)
        self.assertTrue(any("소수 2자리" in e for e in parsed.rows[1].errors))
        self.assertTrue(any("13자리" in e for e in parsed.rows[2].errors))

    def test_total_must_equal_supply_plus_tax(self):
        cells = tax_cells(ROWS)
        cells[(6, 2)] = 1  # 합계금액 칸을 틀리게
        parsed = parse_tax_list(xlsx_bytes(cells))
        self.assertTrue(any("합계금액 ≠" in e for e in parsed.rows[0].errors))

    def test_not_a_tax_list(self):
        with self.assertRaises(SheetFormatError):
            parse_tax_list(xlsx_bytes({(0, 0): "견 적 서", (5, 1): "품명"}))


class TaxGateway(FakeGateway):
    """세금계산서 엔드포인트를 더한 목 Gateway."""

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.taxes: list[dict] = []

    def route(self, handler, method, parsed, body):
        if parsed.path == "/api/v1/taxes" and method == "GET":
            query = urllib.parse.parse_qs(parsed.query)
            rows = [t for t in self.taxes if t["type"] == query.get("type", [t["type"]])[0]]
            return handler._reply(200, rows)
        if parsed.path == "/api/v1/taxes" and method == "POST":
            row = {"id": 500 + len(self.taxes), "paymentConfirmed": False, **body}
            self.taxes.append(row)
            return handler._reply(201, row)
        return super().route(handler, method, parsed, body)


class TaxCliBase(cli_base.CliBase):
    def run_taxes(self, *args):
        import contextlib
        import io
        from unittest import mock
        out, err = io.StringIO(), io.StringIO()
        environ = {k: v for k, v in os.environ.items()
                   if k not in (import_common.ACCESS_ENV, import_common.REFRESH_ENV)}
        with mock.patch.dict(os.environ, environ, clear=True), \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = taxes.main([*map(str, args), "--ledger", str(self.ledger)])
        return code, out.getvalue(), err.getvalue()

    def emit(self, plan_path, *extra, base_url="http://127.0.0.1:3000"):
        return self.run_taxes(self.site_dir, "--emit-plan", plan_path, "--base-url", base_url, *extra)


class TaxCliTest(TaxCliBase):
    def test_preview_counts_and_no_writes(self):
        path = self.write("매출(1월~3월).xls", xls_bytes(tax_cells(ROWS)))
        before = (path.read_bytes(), path.stat().st_mtime)
        code, out, err = self.run_taxes(self.site_dir)
        self.assertEqual(code, 0, out + err)
        self.assertIn("행 2", out)
        self.assertIn("공급가액 합계 150,000 / 시트 요약 150,000", out)
        self.assertIn("현장 연결은 적용 시", out)
        self.assertIn("ERROR 파일 0", out)
        self.assertFalse(self.ledger.exists())
        self.assertEqual((path.read_bytes(), path.stat().st_mtime), before)

    def test_summary_mismatch_refuses_plan(self):
        self.write("매출.xlsx", xlsx_bytes(tax_cells(ROWS, summary_supply=1)))
        code, out, _ = self.run_taxes(self.site_dir)
        self.assertEqual(code, 1)
        self.assertIn("ERROR: 행 공급가액 합계와 시트 요약 총 공급가액", out)
        plan_path = self.tmp / "plan.json"
        code, _, err = self.emit(plan_path)
        self.assertEqual(code, 1)
        self.assertIn("거부", err)
        self.assertFalse(plan_path.exists())

    def test_plan_rules(self):
        self.write("매출.xlsx", xlsx_bytes(tax_cells(ROWS)))
        inside = import_common.REPO_ROOT / "scripts" / "import" / "tax-plan-should-not-exist.json"
        self.assertEqual(self.emit(inside)[0], 2)
        self.assertFalse(inside.exists())
        self.assertEqual(self.emit(self.tmp / "p.json", base_url="https://example.org")[0], 2)

    def test_emit_plan_format(self):
        self.write("매출.xlsx", xlsx_bytes(tax_cells(ROWS)))
        plan_path = self.tmp / "out" / "plan.json"
        code, out, err = self.emit(plan_path)
        self.assertEqual(code, 0, out + err)
        plan = json.loads(plan_path.read_text(encoding="utf-8"))
        self.assertEqual((plan["format"], plan["version"], plan["kind"]), ("buildflow-tax-import-plan", 1, "tax"))
        entry = plan["entries"][0]
        self.assertEqual(entry["row"], 7)
        self.assertEqual(entry["invoice"], {
            "type": "SALES", "supplyAmount": "100000", "taxAmount": "10000", "counterparty": "(주)합성건설",
            "issueDate": "2025-03-02", "memo": entry["marker"] + " 품목:합성현장A 철골 공사"})
        self.assertTrue(entry["marker"].startswith(taxes.MARKER_PREFIX) and entry["marker"].endswith("행:7]"))
        self.assertNotIn("siteId", entry["invoice"])
        self.assertNotIn(str(self.site_dir), plan_path.read_text(encoding="utf-8"))
        self.assertFalse(self.ledger.exists())

    def test_overlapping_exports_and_copies_deduplicated(self):
        self.write("매출(10월~11월).xlsx", xlsx_bytes(tax_cells(ROWS)))
        later = [ROWS[1], ("2025-03-09", "합성상사", 70000, 7000, "합성현장B 추가")]
        self.write("매출(11월~12월).xlsx", xlsx_bytes(tax_cells(later)))
        self.write("매출(11월~12월) - 복사본.xlsx", xlsx_bytes(tax_cells(later)))
        plan_path = self.tmp / "plan.json"
        code, out, err = self.emit(plan_path)
        self.assertEqual(code, 0, out + err)
        self.assertIn("다른 파일과 같은 내용 1행", out)
        entries = json.loads(plan_path.read_text(encoding="utf-8"))["entries"]
        self.assertEqual(len(entries), 3)

    def test_identical_rows_in_one_file_are_kept(self):
        self.write("매출.xlsx", xlsx_bytes(tax_cells([ROWS[1], ROWS[1]])))
        plan_path = self.tmp / "plan.json"
        self.assertEqual(self.emit(plan_path)[0], 0)
        self.assertEqual(len(json.loads(plan_path.read_text(encoding="utf-8"))["entries"]), 2)

    def test_negative_rows_need_option(self):
        rows = ROWS + [("2025-03-06", "합성상사", -50000, -5000, "합성현장B 자재 수정")]
        self.write("매출.xlsx", xlsx_bytes(tax_cells(rows)))
        plan_path = self.tmp / "plan.json"
        code, out, _ = self.emit(plan_path)
        self.assertEqual(code, 1)
        self.assertIn("수정(음수)", out)
        code, out, err = self.emit(plan_path, "--skip-negative")
        self.assertEqual(code, 0, out + err)
        self.assertEqual(len(json.loads(plan_path.read_text(encoding="utf-8"))["entries"]), 2)

    def result_json(self, base_url, sha, tax_id=501, ok=True):
        return {
            "format": "buildflow-tax-import-result", "version": 1, "kind": "tax", "baseUrl": base_url, "ok": ok,
            "error": None,
            "entries": [{"entry": "1:7", "sha256": sha, "sheet": "sheet1", "row": 7, "fileName": "매출.xlsx",
                         "issueDate": "2025-03-02", "supplyAmount": "100000", "taxAmount": "10000",
                         "taxInvoiceId": tax_id, "siteId": 201, "clientId": 101, "action": "created"}],
            "skipped": [{"entry": "1:8", "row": 8, "reason": "현장 없음"}],
        }

    def test_record_result_idempotent_via_both_clis(self):
        result_path = self.tmp / "result.json"
        result_path.write_text(json.dumps(self.result_json("http://localhost:3000", "c" * 64)), encoding="utf-8")
        code, out, err = self.run_taxes("--record-result", result_path)
        self.assertEqual(code, 0, out + err)
        self.assertIn("현장 없음 1행", out)
        rows = self.ledger_rows()
        self.assertEqual([(r["kind"], r["row"], r["taxInvoiceId"]) for r in rows], [("tax", 7, 501)])
        # estimates.py --record-result도 세금계산서 결과를 같은 기록으로 받는다(중복 없음)
        self.assertEqual(self.run_cli("--record-result", result_path)[0], 0)
        self.assertEqual(len(self.ledger_rows()), 1)
        for bad in (self.result_json("https://example.org", "c" * 64), self.result_json("http://localhost:3000", "x")):
            with self.subTest(bad=str(bad)[:40]):
                result_path.write_text(json.dumps(bad), encoding="utf-8")
                self.assertEqual(self.run_taxes("--record-result", result_path)[0], 2)
        result_path.write_text(json.dumps(self.result_json("http://localhost:3000", "d" * 64, ok=False)),
                               encoding="utf-8")
        self.assertEqual(self.run_taxes("--record-result", result_path)[0], 1)
        self.assertEqual(len(self.ledger_rows()), 2)
        self.assertEqual(self.run_taxes(self.site_dir, "--record-result", result_path)[0], 2)


@unittest.skipUnless(NODE, "node 없음")
class TaxBrowserApplyTest(TaxCliBase):
    def run_node(self, plan_path, origin, token=ACCESS):
        runner = self.tmp / "tax-runner.js"
        runner.write_text(
            "const fs = require('fs');\n"
            "const m = require(process.argv[2]);\n"
            "const plan = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));\n"
            "const storage = { getItem: (k) => k === 'buildflow-auth' ? JSON.stringify({ state: { accessToken: process.env.BF_T } }) : null };\n"
            "m.applyTaxPlan(plan, { origin: process.argv[4], storage }).then((r) => fs.writeFileSync(process.argv[5], JSON.stringify(r)));\n",
            encoding="utf-8")
        out_path = self.tmp / "tax-result.json"
        env = {k: v for k, v in os.environ.items() if k not in (import_common.ACCESS_ENV, import_common.REFRESH_ENV)}
        env["BF_T"] = token
        subprocess.run([NODE, str(runner), str(BROWSER_APPLY), str(plan_path), origin, str(out_path)],
                       check=True, env=env, timeout=60, capture_output=True)
        return out_path, json.loads(out_path.read_text(encoding="utf-8"))

    def seed(self, gateway):
        gateway.clients += [
            {"id": 1, "companyName": "합성건설 주식회사", "archivedAt": None},   # (주)합성건설과 같은 거래처
            {"id": 2, "companyName": "합성상사", "archivedAt": None},
            {"id": 3, "companyName": "합성 상사", "archivedAt": None},          # 정규화하면 2와 같음 → 후보 여럿
            {"id": 4, "companyName": "합성공업", "archivedAt": None},
            {"id": 5, "companyName": "합성보관", "archivedAt": "2025-01-01T00:00:00"},
        ]
        client = {c["id"]: c for c in gateway.clients}
        gateway.sites += [
            {"id": 11, "siteName": "합성현장A", "client": client[1], "archivedAt": None},
            {"id": 12, "siteName": "합성현장Z", "client": client[1], "archivedAt": None},
            {"id": 13, "siteName": "합성현장C", "client": client[1], "archivedAt": "2025-01-01T00:00:00"},
            {"id": 41, "siteName": "합성 현장 D", "client": client[4], "archivedAt": None},
            {"id": 42, "siteName": "합성현장D-2", "client": client[4], "archivedAt": None},
            {"id": 43, "siteName": "합성현장A", "client": client[4], "archivedAt": None},  # 다른 거래처 현장은 후보 아님
        ]

    def test_matching_rules_and_rerun_safety(self):
        rows = [
            ("2025-03-02", "(주)합성건설", 100000, 10000, "합성현장A 철골 공사"),   # 1개 → 연결
            ("2025-03-03", "㈜ 합성건설", 30000, 3000, "합성현장C 보수"),          # 보관 현장뿐 → 현장 없음
            ("2025-03-04", "합성상사", 50000, 5000, "합성현장B 자재"),             # 거래처 둘 → 후보 여럿
            ("2025-03-05", "합성공업", 20000, 2000, "합성현장D-2 설치"),           # D·D-2 둘 다 포함 → 후보 여럿
            ("2025-03-06", "합성미등록", 10000, 1000, "합성현장A"),               # 거래처 없음
            ("2025-03-07", "합성보관", 10000, 1000, "합성현장A"),                 # 보관 거래처 → 거래처 없음
        ]
        self.write("매출.xlsx", xlsx_bytes(tax_cells(rows)))
        plan_path = self.tmp / "plan.json"
        with TaxGateway() as gateway:
            self.seed(gateway)
            code, out, err = self.emit(plan_path, base_url=gateway.base_url)
            self.assertEqual(code, 0, out + err)
            self.assertEqual(gateway.requests, [])
            result_path, result = self.run_node(plan_path, gateway.base_url)
            self.assertTrue(result["ok"], result)
            self.assertEqual(len(gateway.posts("/api/v1/taxes")), 1)
            created = gateway.taxes[0]
            self.assertEqual((created["siteId"], created["type"], created["supplyAmount"], created["taxAmount"],
                              created["counterparty"], created["issueDate"]),
                             (11, "SALES", "100000", "10000", "(주)합성건설", "2025-03-02"))
            self.assertIn("품목:합성현장A 철골 공사", created["memo"])
            self.assertEqual([(e["row"], e["action"], e["siteId"]) for e in result["entries"]], [(7, "created", 11)])
            reasons = {s["row"]: s["reason"] for s in result["skipped"]}
            self.assertEqual(reasons, {8: "현장 없음", 9: "후보 여럿", 10: "후보 여럿", 11: "거래처 없음", 12: "거래처 없음"})
            many = {s["row"]: s for s in result["skipped"] if s["reason"] == "후보 여럿"}
            self.assertEqual((many[9]["of"], sorted(many[9]["candidates"])), ("거래처", [2, 3]))
            self.assertEqual((many[10]["of"], sorted(many[10]["candidates"])), ("현장", [41, 42]))
            self.assertFalse(any("confirm-payment" in r[1] or "/payments" in r[1] for r in gateway.requests))
            self.assertNotIn(ACCESS, result_path.read_text(encoding="utf-8"))

            # 기록 전 같은 계획을 다시 돌려도 표식으로 연결만 한다
            _, again = self.run_node(plan_path, gateway.base_url)
            self.assertTrue(again["ok"], again)
            self.assertEqual([(e["row"], e["action"]) for e in again["entries"]], [(7, "linked")])
            self.assertEqual(len(gateway.posts("/api/v1/taxes")), 1)

            self.assertEqual(self.run_taxes("--record-result", result_path)[0], 0)
            code, out, _ = self.emit(self.tmp / "plan2.json", base_url=gateway.base_url)
            self.assertEqual(code, 0)
            self.assertIn("이미 적재 1행", out)
            plan2 = json.loads((self.tmp / "plan2.json").read_text(encoding="utf-8"))
            self.assertEqual([e["row"] for e in plan2["entries"]], [8, 9, 10, 11, 12])

    def test_same_content_from_other_file_or_manual_entry_not_duplicated(self):
        self.write("매출.xlsx", xlsx_bytes(tax_cells(ROWS[:1])))
        plan_path = self.tmp / "plan.json"
        with TaxGateway() as gateway:
            self.seed(gateway)
            gateway.taxes.append({"id": 900, "type": "SALES", "siteId": 11, "supplyAmount": 100000.0,
                                  "taxAmount": 10000.0, "counterparty": "합성건설(주)", "issueDate": "2025-03-02",
                                  "memo": "직접 입력"})
            self.assertEqual(self.emit(plan_path, base_url=gateway.base_url)[0], 0)
            _, result = self.run_node(plan_path, gateway.base_url)
            self.assertTrue(result["ok"], result)
            self.assertEqual(gateway.posts("/api/v1/taxes"), [])
            self.assertEqual([s["reason"] for s in result["skipped"]], ["같은 내용 이미 있음"])

    def test_identical_rows_of_same_file_across_runs_both_created(self):
        self.write("매출.xlsx", xlsx_bytes(tax_cells([ROWS[0], ROWS[0]])))
        plan_path = self.tmp / "plan.json"
        with TaxGateway() as gateway:
            self.seed(gateway)
            self.assertEqual(self.emit(plan_path, base_url=gateway.base_url)[0], 0)
            plan = json.loads(plan_path.read_text(encoding="utf-8"))
            first_only = {**plan, "entries": plan["entries"][:1]}
            plan_path.write_text(json.dumps(first_only, ensure_ascii=False), encoding="utf-8")
            result_path, result = self.run_node(plan_path, gateway.base_url)
            self.assertTrue(result["ok"], result)
            self.assertEqual(self.run_taxes("--record-result", result_path)[0], 0)
            # 둘째 실행: 같은 파일의 앞 행(같은 내용)은 '같은 내용'으로 치지 않는다
            self.assertEqual(self.emit(plan_path, base_url=gateway.base_url)[0], 0)
            _, again = self.run_node(plan_path, gateway.base_url)
            self.assertTrue(again["ok"], again)
            self.assertEqual([(e["row"], e["action"]) for e in again["entries"]], [(8, "created")])
            self.assertEqual(len(gateway.posts("/api/v1/taxes")), 2)

    def test_refuses_estimate_plan_mismatched_origin_and_bad_token(self):
        self.write("매출.xlsx", xlsx_bytes(tax_cells(ROWS)))
        plan_path = self.tmp / "plan.json"
        with TaxGateway() as gateway:
            self.seed(gateway)
            self.assertEqual(self.emit(plan_path, base_url="http://localhost:3999")[0], 0)
            _, result = self.run_node(plan_path, gateway.base_url)
            self.assertFalse(result["ok"])
            self.assertIn("다릅니다", result["error"])
            self.assertEqual(gateway.requests, [])

            self.assertEqual(self.emit(plan_path, base_url=gateway.base_url)[0], 0)
            _, result = self.run_node(plan_path, gateway.base_url, token="wrong-token-value")
            self.assertFalse(result["ok"])
            self.assertIn("인증 만료", result["error"])
            self.assertEqual(gateway.posts("/api/v1/taxes"), [])
            self.assertNotIn("wrong-token-value", json.dumps(result))

            estimate_plan = self.tmp / "estimate-plan.json"
            estimate_plan.write_text(json.dumps({"format": estimates.PLAN_FORMAT, "version": 1,
                                                 "baseUrl": gateway.base_url, "entries": []}), encoding="utf-8")
            _, result = self.run_node(estimate_plan, gateway.base_url)
            self.assertFalse(result["ok"])
            self.assertIn("계획 형식이 아닙니다", result["error"])

    def test_company_key(self):
        samples = ["(주)합성건설", "㈜ 합성건설", "합성건설 주식회사", "합성건설(주)", "（주）합성건설", "합성 건설"]
        script = ("const m = require(process.argv[1]); const s = JSON.parse(process.argv[2]);"
                  "process.stdout.write(JSON.stringify(s.map(m.companyKey)))")
        completed = subprocess.run([NODE, "-e", script, str(BROWSER_APPLY), json.dumps(samples)],
                                   check=True, capture_output=True, timeout=60)
        self.assertEqual(set(json.loads(completed.stdout.decode("utf-8"))), {"합성건설"})


if __name__ == "__main__":
    unittest.main()
