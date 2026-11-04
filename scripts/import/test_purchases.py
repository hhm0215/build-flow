"""매입 세금계산서 목록 → 현장 미배정 매입 가져오기 테스트. 합성 목록(업체·품목·금액 모두 가짜)과 목 Gateway만 쓴다."""

from __future__ import annotations

import contextlib
import io
import json
import os
import shutil
import subprocess
import sys
import unittest
from decimal import Decimal
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import import_common  # noqa: E402
import purchases  # noqa: E402
import taxes  # noqa: E402
import test_estimates_cli as cli_base  # noqa: E402
from fixture_books import xls_bytes, xlsx_bytes  # noqa: E402
from tax_sheet import parse_tax_list  # noqa: E402
from test_estimates_cli import ACCESS, FakeGateway  # noqa: E402
from test_taxes import OLD_HEADER, tax_cells  # noqa: E402

BROWSER_APPLY = Path(__file__).resolve().parent / "browser_apply.js"
NODE = shutil.which("node")

ROWS = [
    ("2025-04-01", "(주)합성철강", 200000, 20000, "합성 각관 외"),
    ("2025-04-03", "합성세무회계", 110000, 11000, "기장 수수료"),
]


class PurchaseGateway(FakeGateway):
    """매입 엔드포인트를 더한 목 Gateway. 서버처럼 siteId 없는 본문은 미배정(null)으로 저장한다."""

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.purchases: list[dict] = []

    def route(self, handler, method, parsed, body):
        if parsed.path == "/api/v1/purchases" and method == "GET":
            return handler._reply(200, list(reversed(self.purchases)))
        if parsed.path == "/api/v1/purchases" and method == "POST":
            row = {"id": 700 + len(self.purchases), "siteId": body.get("siteId"),
                   "totalAmount": float(body["unitPrice"]) * body["quantity"], **body}
            self.purchases.append(row)
            return handler._reply(201, row)
        return super().route(handler, method, parsed, body)


class PurchaseCliBase(cli_base.CliBase):
    def run_purchases(self, *args):
        out, err = io.StringIO(), io.StringIO()
        environ = {k: v for k, v in os.environ.items()
                   if k not in (import_common.ACCESS_ENV, import_common.REFRESH_ENV)}
        with mock.patch.dict(os.environ, environ, clear=True), \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = purchases.main([*map(str, args), "--ledger", str(self.ledger)])
        return code, out.getvalue(), err.getvalue()

    def emit(self, plan_path, *extra, base_url="http://127.0.0.1:3000"):
        return self.run_purchases(self.site_dir, "--emit-plan", plan_path, "--base-url", base_url, *extra)

    def plan_entries(self, plan_path):
        return json.loads(plan_path.read_text(encoding="utf-8"))["entries"]


class PurchaseSheetTest(unittest.TestCase):
    def test_purchase_list_parses_with_tax_sheet(self):
        for header in (None, OLD_HEADER):  # 2024 양식처럼 세액 열이 없어도 합계−공급가액으로 읽는다
            for maker in (xlsx_bytes, xls_bytes):
                with self.subTest(header=bool(header), maker=maker.__name__):
                    parsed = parse_tax_list(maker(tax_cells(ROWS, header=header), "sheet1"))
                    self.assertEqual(parsed.errors, [])
                    self.assertEqual([r.supply for r in parsed.rows], [Decimal(200000), Decimal(110000)])
                    self.assertEqual(parsed.rows[0].tax, Decimal(20000))
                    self.assertEqual(parsed.repeated, [])

    def test_repeated_counterparty_column_recorded(self):
        header = ["작성일자", "상호", "합계금액", "공급가액", "세액", "품목명", "상호"]
        parsed = parse_tax_list(xlsx_bytes(tax_cells(ROWS, header=header)))
        self.assertEqual(parsed.repeated, ["counterparty"])
        self.assertEqual(parsed.columns["counterparty"], 1)


class PurchaseCliTest(PurchaseCliBase):
    def test_preview_counts_basis_and_no_writes(self):
        path = self.write("매입(1월~3월).xls", xls_bytes(tax_cells(ROWS)))
        before = (path.read_bytes(), path.stat().st_mtime)
        code, out, err = self.run_purchases(self.site_dir)
        self.assertEqual(code, 0, out + err)
        self.assertIn("행 2", out)
        self.assertIn("공급가액 합계 310,000 / 시트 요약 310,000", out)
        self.assertIn("세액 합계(저장 안 함) 31,000", out)
        self.assertIn("공급가액 기준", out)
        self.assertIn("분류 힌트(참고만, 모두 미배정으로 올림): 비공사 추정(세무) 1행", out)
        self.assertIn("ERROR 파일 0", out)
        self.assertFalse(self.ledger.exists())
        self.assertEqual((path.read_bytes(), path.stat().st_mtime), before)

    def test_summary_mismatch_refuses_plan(self):
        self.write("매입.xlsx", xlsx_bytes(tax_cells(ROWS, summary_supply=1)))
        code, out, _ = self.run_purchases(self.site_dir)
        self.assertEqual(code, 1)
        self.assertIn("ERROR: 행 공급가액 합계와 시트 요약 총 공급가액", out)
        plan_path = self.tmp / "plan.json"
        code, _, err = self.emit(plan_path)
        self.assertEqual(code, 1)
        self.assertIn("거부", err)
        self.assertFalse(plan_path.exists())

    def test_dto_bounds(self):
        long_item = "합성품목" * 60  # 240자
        rows = [("2025-04-01", "합성A", 10 ** 10, 10 ** 9, "단가 한도 초과"),  # 정수 11자리 → ERROR
                ("2025-04-02", "합성B", 1000.5, 100, "소수"),                  # 소수 1자리는 허용
                ("2025-04-03", "합성C", 5000, 500, long_item),
                ("2025-04-04", "합성D", 3000, 300, None)]
        self.write("매입.xlsx", xlsx_bytes(tax_cells(rows)))
        code, out, _ = self.run_purchases(self.site_dir)
        self.assertEqual(code, 1)
        self.assertIn("ERROR: 7행 공급가액이 매입 단가 한도(정수 10자리)를 넘음", out)
        self.assertNotIn("ERROR: 8행", out)
        self.assertIn("9행 품목명이 200자를 넘어", out)
        self.assertIn("10행 품목명이 비어 있어", out)
        # 한도 초과 행만 빼면 계획이 나온다
        self.write("매입.xlsx", xlsx_bytes(tax_cells(rows[1:])))
        plan_path = self.tmp / "plan.json"
        code, out, err = self.emit(plan_path)
        self.assertEqual(code, 0, out + err)
        bodies = [e["purchase"] for e in self.plan_entries(plan_path)]
        self.assertEqual(bodies[0]["unitPrice"], "1000.5")
        self.assertEqual(len(bodies[1]["itemName"]), 200)
        self.assertIn("품목 전체:" + long_item, bodies[1]["memo"])
        self.assertEqual(bodies[2]["itemName"], purchases.NO_ITEM_NAME)

    def test_repeated_counterparty_header_is_error(self):
        header = ["작성일자", "상호", "합계금액", "공급가액", "세액", "품목명", "상호"]
        self.write("매입.xlsx", xlsx_bytes(tax_cells(ROWS, header=header)))
        code, out, _ = self.run_purchases(self.site_dir)
        self.assertEqual(code, 1)
        self.assertIn("'상호' 열이 둘 이상", out)

    def test_plan_rules(self):
        self.write("매입.xlsx", xlsx_bytes(tax_cells(ROWS)))
        inside = import_common.REPO_ROOT / "scripts" / "import" / "purchase-plan-should-not-exist.json"
        self.assertEqual(self.emit(inside)[0], 2)
        self.assertFalse(inside.exists())
        self.assertEqual(self.emit(self.tmp / "p.json", base_url="https://example.org")[0], 2)

    def test_emit_plan_body_matches_dto_without_site_or_tax(self):
        self.write("매입.xlsx", xlsx_bytes(tax_cells(ROWS)))
        plan_path = self.tmp / "out" / "plan.json"
        code, out, err = self.emit(plan_path)
        self.assertEqual(code, 0, out + err)
        plan = json.loads(plan_path.read_text(encoding="utf-8"))
        self.assertEqual((plan["format"], plan["version"], plan["kind"], plan["amountBasis"]),
                         ("buildflow-purchase-import-plan", 1, "purchase", "supply"))
        entry = plan["entries"][0]
        self.assertEqual(entry["row"], 7)
        self.assertEqual(entry["purchase"], {
            "itemName": "합성 각관 외", "quantity": 1, "unitPrice": "200000", "supplier": "(주)합성철강",
            "purchaseDate": "2025-04-01", "memo": entry["marker"]})
        self.assertTrue(entry["marker"].startswith("[가져오기 매입 sha256:") and entry["marker"].endswith(" 행:7]"))
        self.assertEqual(entry["taxAmount"], "20000")  # 계획 정보에만, 본문에는 없음
        self.assertNotIn("20000", [str(v) for v in entry["purchase"].values()])
        self.assertNotIn("220000", json.dumps(entry["purchase"]))  # 합계금액도 넣지 않음
        self.assertEqual((entry["hint"], plan["entries"][1]["hint"]), (None, "비공사 추정(세무)"))
        self.assertNotIn(str(self.site_dir), plan_path.read_text(encoding="utf-8"))
        self.assertFalse(self.ledger.exists())

    def test_overlapping_exports_and_copies_deduplicated(self):
        self.write("매입(1월~2월).xlsx", xlsx_bytes(tax_cells(ROWS)))
        later = [ROWS[1], ("2025-04-09", "합성상사", 70000, 7000, "합성 볼트")]
        self.write("매입(2월~3월).xlsx", xlsx_bytes(tax_cells(later)))
        self.write("매입(2월~3월) - 복사본.xlsx", xlsx_bytes(tax_cells(later)))
        plan_path = self.tmp / "plan.json"
        code, out, err = self.emit(plan_path)
        self.assertEqual(code, 0, out + err)
        self.assertIn("다른 파일과 같은 내용 1행", out)
        self.assertEqual(len(self.plan_entries(plan_path)), 3)

    def test_negative_rows_blocked_unless_skipped(self):
        rows = ROWS + [("2025-04-06", "(주)합성철강", -50000, -5000, "합성 각관 반품")]
        self.write("매입.xlsx", xlsx_bytes(tax_cells(rows)))
        plan_path = self.tmp / "plan.json"
        code, out, _ = self.emit(plan_path)
        self.assertEqual(code, 1)
        self.assertIn("수정(음수)", out)
        self.assertFalse(plan_path.exists())
        code, out, err = self.emit(plan_path, "--skip-negative")
        self.assertEqual(code, 0, out + err)
        self.assertIn("수정(음수) 세금계산서 1행(--skip-negative)", out)
        self.assertEqual([e["row"] for e in self.plan_entries(plan_path)], [7, 8])

    def result_json(self, base_url, sha, purchase_id=701, ok=True):
        return {
            "format": "buildflow-purchase-import-result", "version": 1, "kind": "purchase", "baseUrl": base_url,
            "ok": ok, "error": None,
            "entries": [{"entry": "1:7", "sha256": sha, "sheet": "sheet1", "row": 7, "fileName": "매입.xlsx",
                         "issueDate": "2025-04-01", "supplyAmount": "200000", "taxAmount": "20000",
                         "purchaseId": purchase_id, "siteId": None, "action": "created"}],
            "skipped": [],
        }

    def test_record_result_idempotent_via_both_clis(self):
        import estimates
        result_path = self.tmp / "result.json"
        result_path.write_text(json.dumps(self.result_json("http://localhost:3000", "c" * 64)), encoding="utf-8")
        code, out, err = self.run_purchases("--record-result", result_path)
        self.assertEqual(code, 0, out + err)
        rows = self.ledger_rows()
        self.assertEqual([(r["kind"], r["row"], r["purchaseId"], r["siteId"], r["amountBasis"]) for r in rows],
                         [("purchase", 7, 701, None, "supply")])
        self.assertEqual(self.run_purchases("--record-result", result_path)[0], 0)
        self.assertEqual(self.run_cli("--record-result", result_path)[0], 0)  # estimates.py도 같은 기록으로
        self.assertEqual(len(self.ledger_rows()), 1)
        for bad in (self.result_json("https://example.org", "c" * 64), self.result_json("http://localhost:3000", "x"),
                    {**self.result_json("http://localhost:3000", "c" * 64), "format": taxes.RESULT_FORMAT}):
            with self.subTest(bad=str(bad)[:60]):
                result_path.write_text(json.dumps(bad), encoding="utf-8")
                self.assertEqual(self.run_purchases("--record-result", result_path)[0], 2)
        self.assertEqual(self.run_purchases(self.site_dir, "--record-result", result_path)[0], 2)
        self.assertTrue(estimates)

    def test_tax_ledger_rows_do_not_skip_purchase_rows(self):
        path = self.write("매입.xlsx", xlsx_bytes(tax_cells(ROWS)))
        sha = import_common.read_source(path).sha256
        ledger = import_common.Ledger(self.ledger)
        ledger.append({"kind": "tax", "baseUrl": "http://127.0.0.1:3000", "sha256": sha, "sheet": "sheet1",
                       "row": 7, "taxInvoiceId": 1, "siteId": 1})
        plan_path = self.tmp / "plan.json"
        self.assertEqual(self.emit(plan_path)[0], 0)
        self.assertEqual(len(self.plan_entries(plan_path)), 2)


@unittest.skipUnless(NODE, "node 없음")
class PurchaseBrowserApplyTest(PurchaseCliBase):
    def run_node(self, plan_path, origin, token=ACCESS):
        runner = self.tmp / "purchase-runner.js"
        runner.write_text(
            "const fs = require('fs');\n"
            "const m = require(process.argv[2]);\n"
            "const plan = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));\n"
            "const storage = { getItem: (k) => k === 'buildflow-auth' ? JSON.stringify({ state: { accessToken: process.env.BF_T } }) : null };\n"
            "m.applyPurchasePlan(plan, { origin: process.argv[4], storage }).then((r) => fs.writeFileSync(process.argv[5], JSON.stringify(r)));\n",
            encoding="utf-8")
        out_path = self.tmp / "purchase-result.json"
        env = {k: v for k, v in os.environ.items() if k not in (import_common.ACCESS_ENV, import_common.REFRESH_ENV)}
        env["BF_T"] = token
        subprocess.run([NODE, str(runner), str(BROWSER_APPLY), str(plan_path), origin, str(out_path)],
                       check=True, env=env, timeout=60, capture_output=True)
        return out_path, json.loads(out_path.read_text(encoding="utf-8"))

    def test_creates_unassigned_purchases_and_rerun_is_safe(self):
        self.write("매입.xlsx", xlsx_bytes(tax_cells(ROWS)))
        plan_path = self.tmp / "plan.json"
        with PurchaseGateway() as gateway:
            gateway.purchases.append({"id": 600, "siteId": 11, "itemName": "합성 각관 외", "quantity": 1,
                                      "unitPrice": 200000.0, "supplier": "(주)합성철강",
                                      "purchaseDate": "2025-04-01", "memo": "직접 입력"})
            code, out, err = self.emit(plan_path, base_url=gateway.base_url)
            self.assertEqual(code, 0, out + err)
            self.assertEqual(gateway.requests, [])
            result_path, result = self.run_node(plan_path, gateway.base_url)
            self.assertTrue(result["ok"], result)
            posts = gateway.posts("/api/v1/purchases")
            self.assertEqual(len(posts), 2)  # 수동 입력 매입과는 비교하지 않는다
            for _, _, body, _ in posts:
                self.assertNotIn("siteId", body)
                self.assertEqual(set(body), {"itemName", "quantity", "unitPrice", "supplier", "purchaseDate", "memo"})
            self.assertEqual(posts[0][2]["unitPrice"], "200000")
            self.assertFalse(any(r[0] == "PATCH" for r in gateway.requests))
            self.assertEqual([(e["row"], e["action"], e["siteId"]) for e in result["entries"]],
                             [(7, "created", None), (8, "created", None)])
            self.assertEqual(result["entries"][1]["hint"], "비공사 추정(세무)")
            self.assertEqual(result["amountBasis"], "supply")
            self.assertNotIn(ACCESS, result_path.read_text(encoding="utf-8"))

            # 기록 전 같은 계획을 다시 돌려도 표식으로 연결만 한다
            _, again = self.run_node(plan_path, gateway.base_url)
            self.assertTrue(again["ok"], again)
            self.assertEqual([e["action"] for e in again["entries"]], ["linked", "linked"])
            self.assertEqual(len(gateway.posts("/api/v1/purchases")), 2)

            self.assertEqual(self.run_purchases("--record-result", result_path)[0], 0)
            code, out, _ = self.emit(self.tmp / "plan2.json", base_url=gateway.base_url)
            self.assertEqual(code, 0)
            self.assertIn("이미 적재 2행", out)
            self.assertEqual(self.plan_entries(self.tmp / "plan2.json"), [])

    def test_same_content_from_other_imported_file_not_duplicated(self):
        first = self.tmp / "a" / "매입(1월~2월).xlsx"
        first.parent.mkdir()
        first.write_bytes(xlsx_bytes(tax_cells(ROWS[:1])))
        self.write("매입(2월~3월).xlsx", xlsx_bytes(tax_cells(ROWS)))
        with PurchaseGateway() as gateway:
            plan_a = self.tmp / "plan-a.json"
            code, out, err = self.run_purchases(first.parent, "--emit-plan", plan_a, "--base-url", gateway.base_url)
            self.assertEqual(code, 0, out + err)
            self.assertTrue(self.run_node(plan_a, gateway.base_url)[1]["ok"])
            plan_b = self.tmp / "plan-b.json"
            self.assertEqual(self.emit(plan_b, base_url=gateway.base_url)[0], 0)
            _, result = self.run_node(plan_b, gateway.base_url)
            self.assertTrue(result["ok"], result)
            self.assertEqual([s["reason"] for s in result["skipped"]], ["같은 내용 이미 있음"])
            self.assertEqual([e["row"] for e in result["entries"]], [8])
            self.assertEqual(len(gateway.posts("/api/v1/purchases")), 2)

    def test_refuses_other_plans_site_body_mismatched_origin_and_bad_token(self):
        self.write("매입.xlsx", xlsx_bytes(tax_cells(ROWS)))
        plan_path = self.tmp / "plan.json"
        with PurchaseGateway() as gateway:
            self.assertEqual(self.emit(plan_path, base_url="http://localhost:3999")[0], 0)
            _, result = self.run_node(plan_path, gateway.base_url)
            self.assertFalse(result["ok"])
            self.assertIn("다릅니다", result["error"])
            self.assertEqual(gateway.requests, [])

            self.assertEqual(self.emit(plan_path, base_url=gateway.base_url)[0], 0)
            _, result = self.run_node(plan_path, gateway.base_url, token="wrong-token-value")
            self.assertFalse(result["ok"])
            self.assertIn("인증 만료", result["error"])
            self.assertEqual(gateway.posts("/api/v1/purchases"), [])
            self.assertNotIn("wrong-token-value", json.dumps(result))

            plan = json.loads(plan_path.read_text(encoding="utf-8"))
            plan["entries"][0]["purchase"]["siteId"] = 11
            plan_path.write_text(json.dumps(plan, ensure_ascii=False), encoding="utf-8")
            _, result = self.run_node(plan_path, gateway.base_url)
            self.assertFalse(result["ok"])
            self.assertIn("siteId", result["error"])
            self.assertEqual(gateway.posts("/api/v1/purchases"), [])

            tax_plan = self.tmp / "tax-plan.json"
            tax_plan.write_text(json.dumps({"format": taxes.PLAN_FORMAT, "version": 1,
                                            "baseUrl": gateway.base_url, "entries": []}), encoding="utf-8")
            _, result = self.run_node(tax_plan, gateway.base_url)
            self.assertFalse(result["ok"])
            self.assertIn("계획 형식이 아닙니다", result["error"])


if __name__ == "__main__":
    unittest.main()
