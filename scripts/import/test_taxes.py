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


class TaxAliasFileTest(TaxCliBase):
    """--client-alias·--site-map·--unmatched-csv(합성 이름만)."""

    def csv_file(self, name, text):
        path = self.tmp / "aliases" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(text.encode("utf-8-sig"))
        return path

    def test_company_key_matches_js_rule(self):
        for name in ("(주)합성건설", "㈜ 합성건설", "합성건설 주식회사", "합성건설(주)", "（주）합성건설", "합성 건설"):
            self.assertEqual(taxes.company_key(name), "합성건설", name)

    def test_load_client_aliases(self):
        path = self.csv_file("client.csv", "세금계산서 상호,DB 거래처명\n# 주석\n(주)가나합성디자인건설,가나 합성\n"
                                           "가나합성디자인건설 주식회사,가나 합성\n\n합성상회 ,  합성 상사 \n")
        aliases = taxes.load_client_aliases(path)
        self.assertEqual(aliases, {"가나합성디자인건설": "가나 합성", "합성상회": "합성 상사"})
        bad = {
            "conflict.csv": "(주)가나합성,가나 합성\n가나합성 주식회사,다른 이름\n",
            "one.csv": "가나합성\n",
            "symbol.csv": "(주),가나 합성\n",
        }
        for name, text in bad.items():
            with self.subTest(name=name), self.assertRaises(import_common.ImportSetupError):
                taxes.load_client_aliases(self.csv_file(name, text))
        inside = import_common.REPO_ROOT / "scripts" / "import" / "client-alias-should-not-exist.csv"
        with self.assertRaises(import_common.ImportSetupError):
            taxes.load_client_aliases(inside)

    def test_load_site_map_precedence_and_conflict(self):
        path = self.csv_file("site.csv", "세금계산서 상호(또는 *),품목명,DB 현장명\n"
                                         "*,합성병원 잔금,인천 합성병원\n"
                                         "(주)합성건설,합성병원 잔금,서울 합성병원\n"
                                         "합성상사,합성 대전 주유소,대전 합성 주유소\n")
        site_map = taxes.load_site_map(path)
        self.assertEqual(site_map.lookup("합성건설 주식회사", "합성병원  잔금"), "서울 합성병원")  # 상호 지정 줄 우선
        self.assertEqual(site_map.lookup("합성공업", "합성병원잔금"), "인천 합성병원")              # * 줄
        self.assertEqual(site_map.lookup("합성상사", "합성대전주유소"), "대전 합성 주유소")
        self.assertIsNone(site_map.lookup("합성공업", "합성대전주유소"))   # 다른 상호
        self.assertIsNone(site_map.lookup("합성상사", "합성 대전 주유소 잔금"))  # 품목명은 같아야(포함 아님)
        self.assertEqual(len(site_map.used), 3)
        for text in ("*,합성병원,가\n*,합성 병원,나\n", "*,합성병원\n", "*,-,합성현장\n"):
            with self.subTest(text=text), self.assertRaises(import_common.ImportSetupError):
                taxes.load_site_map(self.csv_file("bad-site.csv", text))

    def test_emit_plan_carries_client_name_and_site_name(self):
        rows = [("2025-03-02", "(주)가나합성디자인건설", 100000, 10000, "합성병원 잔금"),
                ("2025-03-03", "합성상사", 50000, 5000, "합성현장B 자재")]
        self.write("매출.xlsx", xlsx_bytes(tax_cells(rows)))
        client = self.csv_file("client.csv", "가나합성디자인건설 주식회사,가나 합성\n")
        site = self.csv_file("site.csv", "*,합성병원잔금,인천 합성병원\n*,쓰이지않는품목,없는 현장\n")
        plan_path = self.tmp / "plan.json"
        code, out, err = self.emit(plan_path, "--client-alias", client, "--site-map", site)
        self.assertEqual(code, 0, out + err)
        self.assertIn("거래처 별칭 적용: 1행", out)
        self.assertIn("현장 지정 적용: 1행 / 지정 2줄(이번 계획에 안 걸린 줄 1)", out)
        entries = json.loads(plan_path.read_text(encoding="utf-8"))["entries"]
        self.assertEqual([(e["clientName"], e["clientAlias"], e["siteName"]) for e in entries],
                         [("가나 합성", True, "인천 합성병원"), ("합성상사", False, None)])
        self.assertEqual(entries[0]["invoice"]["counterparty"], "(주)가나합성디자인건설")  # 본문 상호는 원본 그대로
        inside = import_common.REPO_ROOT / "scripts" / "import" / "site-map-should-not-exist.csv"
        self.assertEqual(self.emit(plan_path, "--site-map", inside)[0], 2)

    def result_with_skips(self):
        return {
            "format": "buildflow-tax-import-result", "version": 1, "kind": "tax", "baseUrl": "http://localhost:3000",
            "ok": True, "error": None, "entries": [],
            "skipped": [
                {"entry": "1:7", "row": 7, "counterparty": "(주)합성건설", "itemName": "합성 대전 주유소",
                 "issueDate": "2025-03-02", "supplyAmount": "100000", "reason": "후보 여럿", "of": "현장",
                 "candidates": [11, 12], "candidateNames": ["대전 합성 주유소", "합성 주유소 2"]},
                {"entry": "1:8", "row": 8, "counterparty": "=합성수식", "itemName": "-합성", "issueDate": "2025-03-03",
                 "supplyAmount": "-5000", "reason": "지정 현장 없음", "siteName": "없는 현장",
                 "candidates": [], "candidateNames": []},
                {"entry": "1:9", "row": 9, "counterparty": "합성상사", "itemName": "합성", "issueDate": "2025-03-04",
                 "supplyAmount": "1000", "reason": "같은 내용 이미 있음"},
                {"estimateId": 3, "reason": "이미 CONFIRMED"},  # 행이 없는 항목은 무시
            ],
        }

    def read_unmatched(self, path):
        import csv as csv_module
        raw = path.read_bytes()
        self.assertTrue(raw.startswith(b"\xef\xbb\xbf"))
        return list(csv_module.reader(io_text(raw.decode("utf-8-sig"))))

    def test_unmatched_csv_from_result(self):
        result_path = self.tmp / "result.json"
        result_path.write_text(json.dumps(self.result_with_skips(), ensure_ascii=False), encoding="utf-8")
        out_csv = self.tmp / "out" / "unmatched.csv"
        code, out, err = self.run_taxes("--from-result", result_path, "--unmatched-csv", out_csv)
        self.assertEqual(code, 0, out + err)
        self.assertIn("미연결 CSV: 2행 · 같은 내용 이미 있음 1행 제외", out)
        self.assertEqual(self.read_unmatched(out_csv), [
            ["상호", "품목명", "작성일자", "공급가액", "사유", "후보"],
            ["(주)합성건설", "합성 대전 주유소", "2025-03-02", "100000", "후보 여럿", "대전 합성 주유소 / 합성 주유소 2"],
            ["'=합성수식", "'-합성", "2025-03-03", "-5000", "지정 현장 없음(지정: 없는 현장)", ""],
        ])
        self.assertFalse(self.ledger.exists())  # --from-result는 적재 기록을 남기지 않는다

    def test_unmatched_csv_rules(self):
        result_path = self.tmp / "result.json"
        result_path.write_text(json.dumps(self.result_with_skips(), ensure_ascii=False), encoding="utf-8")
        inside = import_common.REPO_ROOT / "scripts" / "import" / "unmatched-should-not-exist.csv"
        self.assertEqual(self.run_taxes("--from-result", result_path, "--unmatched-csv", inside)[0], 2)
        self.assertFalse(inside.exists())
        self.assertEqual(self.run_taxes("--from-result", result_path)[0], 2)               # 출력 경로 없음
        self.assertEqual(self.run_taxes(self.site_dir, "--unmatched-csv", self.tmp / "u.csv")[0], 2)  # 결과 없음
        self.assertEqual(self.run_taxes(self.site_dir, "--from-result", result_path,
                                        "--unmatched-csv", self.tmp / "u.csv")[0], 2)
        # --record-result와 함께: 기록 + CSV
        out_csv = self.tmp / "u.csv"
        code, out, err = self.run_taxes("--record-result", result_path, "--unmatched-csv", out_csv)
        self.assertEqual(code, 0, out + err)
        self.assertEqual(len(self.read_unmatched(out_csv)), 3)
        bad = {**self.result_with_skips(), "format": "buildflow-import-result"}
        result_path.write_text(json.dumps(bad), encoding="utf-8")
        self.assertEqual(self.run_taxes("--from-result", result_path, "--unmatched-csv", out_csv)[0], 2)


def io_text(text):
    import io
    return io.StringIO(text)


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

    def alias_seed(self, gateway):
        gateway.clients += [
            {"id": 6, "companyName": "가나 합성", "archivedAt": None},
            {"id": 7, "companyName": "다라합성", "archivedAt": None},
        ]
        client = {c["id"]: c for c in gateway.clients}
        gateway.sites += [
            {"id": 61, "siteName": "인천 합성병원", "client": client[6], "archivedAt": None},
            {"id": 62, "siteName": "대전 합성 주유소", "client": client[6], "archivedAt": None},
            {"id": 63, "siteName": "합성타워", "client": client[6], "archivedAt": None},
            {"id": 71, "siteName": "인천 합성의원", "client": client[7], "archivedAt": None},
            {"id": 72, "siteName": "서울 합성의원", "client": client[7], "archivedAt": None},
            {"id": 73, "siteName": "합성 학교", "client": client[7], "archivedAt": None},
            {"id": 74, "siteName": "합성학교", "client": client[7], "archivedAt": None},   # 정규화하면 73과 같은 이름
            {"id": 75, "siteName": "합성창고", "client": client[7], "archivedAt": None},
        ]

    def test_alias_site_map_and_loose_rule(self):
        rows = [
            ("2025-04-01", "(주)가나합성디자인건설", 100000, 10000, "합성병원 잔금"),       # 별칭 + 보조(품목 ⊂ 현장) → contains
            ("2025-04-02", "(주)가나합성디자인건설", 200000, 20000, "합성 주유소 대전"),     # site-map → site-map
            ("2025-04-03", "가나합성디자인건설 주식회사", 30000, 3000, "합성타워 금속공사"),  # 별칭 + 포함 → alias
            ("2025-04-04", "다라합성", 40000, 4000, "합성의원 계약금"),                     # 보조 후보 둘 → 후보 여럿
            ("2025-04-05", "다라합성", 50000, 5000, "합성 학교"),                           # site-map 이름이 둘 → 지정 현장 없음
            ("2025-04-06", "다라합성", 60000, 6000, "창고 중도금"),                         # 일반어 뺀 키 2글자 → 현장 없음
            ("2025-04-07", "다라합성", 70000, 7000, "합성 체육관"),                         # site-map 이름 없음 → 지정 현장 없음
            ("2025-04-08", "(주)가나합성디자인건설", 80000, 8000, "합성창고 인테리어"),     # 다른 거래처 현장 → 현장 없음
            ("2025-04-09", "가나합성디자인", 90000, 9000, "합성타워"),                      # 별칭 없는 상호 → 거래처 없음
        ]
        self.write("매출.xlsx", xlsx_bytes(tax_cells(rows)))
        aliases = self.tmp / "aliases"
        aliases.mkdir()
        client_alias = aliases / "client.csv"
        client_alias.write_text("세금계산서 상호,DB 거래처명\n(주)가나합성디자인건설,가나 합성\n", encoding="utf-8")
        site_map = aliases / "site.csv"
        site_map.write_text("세금계산서 상호,품목명,DB 현장명\n(주)가나합성디자인건설,합성 주유소 대전,대전 합성 주유소\n"
                            "*,합성학교,합성학교\n다라합성,합성 체육관,합성 체육관\n", encoding="utf-8")
        plan_path = self.tmp / "plan.json"
        with TaxGateway() as gateway:
            self.seed(gateway)
            self.alias_seed(gateway)
            code, out, err = self.emit(plan_path, "--client-alias", client_alias, "--site-map", site_map,
                                       base_url=gateway.base_url)
            self.assertEqual(code, 0, out + err)
            result_path, result = self.run_node(plan_path, gateway.base_url)
            self.assertTrue(result["ok"], result)
            self.assertEqual([(e["row"], e["siteId"], e["matchRule"], e["clientRule"]) for e in result["entries"]],
                             [(7, 61, "contains", "alias"), (8, 62, "site-map", "alias"), (9, 63, "alias", "alias")])
            self.assertEqual([t["counterparty"] for t in gateway.taxes],
                             ["(주)가나합성디자인건설"] * 2 + ["가나합성디자인건설 주식회사"])
            skipped = {s["row"]: s for s in result["skipped"]}
            self.assertEqual({r: s["reason"] for r, s in skipped.items()},
                             {10: "후보 여럿", 11: "지정 현장 없음", 12: "현장 없음", 13: "지정 현장 없음",
                              14: "현장 없음", 15: "거래처 없음"})
            self.assertEqual(sorted(skipped[10]["candidateNames"]), ["서울 합성의원", "인천 합성의원"])
            self.assertEqual(sorted(skipped[11]["candidates"]), [73, 74])
            self.assertEqual((skipped[13]["siteName"], skipped[13]["candidates"]), ("합성 체육관", []))
            self.assertEqual((skipped[10]["counterparty"], skipped[10]["itemName"]), ("다라합성", "합성의원 계약금"))

            self.assertEqual(self.run_taxes("--record-result", result_path, "--unmatched-csv",
                                            self.tmp / "u.csv")[0], 0)
            self.assertEqual([r["matchRule"] for r in self.ledger_rows()], ["contains", "site-map", "alias"])
            text = (self.tmp / "u.csv").read_bytes().decode("utf-8-sig")
            self.assertEqual(len(text.strip().splitlines()), 7)
            self.assertIn("다라합성,합성의원 계약금,2025-04-04,40000,후보 여럿,", text)

    def test_exact_rule_recorded_and_plan_without_client_name(self):
        self.write("매출.xlsx", xlsx_bytes(tax_cells(ROWS[:1])))
        plan_path = self.tmp / "plan.json"
        with TaxGateway() as gateway:
            self.seed(gateway)
            self.assertEqual(self.emit(plan_path, base_url=gateway.base_url)[0], 0)
            plan = json.loads(plan_path.read_text(encoding="utf-8"))
            for entry in plan["entries"]:  # 예전 계획(clientName·siteName 없음)도 상호로 찾는다
                for key in ("clientName", "clientAlias", "siteName"):
                    entry.pop(key)
            plan_path.write_text(json.dumps(plan, ensure_ascii=False), encoding="utf-8")
            _, result = self.run_node(plan_path, gateway.base_url)
            self.assertTrue(result["ok"], result)
            self.assertEqual([(e["siteId"], e["matchRule"]) for e in result["entries"]], [(11, "exact")])

    def test_strip_generic_words(self):
        samples = ["합성금속공사잔금", "합성인테리어공사", "합성계약금중도금", "공사"]
        script = ("const m = require(process.argv[1]); const s = JSON.parse(process.argv[2]);"
                  "process.stdout.write(JSON.stringify(s.map(m.stripGenericWords)))")
        completed = subprocess.run([NODE, "-e", script, str(BROWSER_APPLY), json.dumps(samples)],
                                   check=True, capture_output=True, timeout=60)
        self.assertEqual(json.loads(completed.stdout.decode("utf-8")), ["합성", "합성", "합성", ""])

    def test_company_key(self):
        samples =["(주)합성건설", "㈜ 합성건설", "합성건설 주식회사", "합성건설(주)", "（주）합성건설", "합성 건설"]
        script = ("const m = require(process.argv[1]); const s = JSON.parse(process.argv[2]);"
                  "process.stdout.write(JSON.stringify(s.map(m.companyKey)))")
        completed = subprocess.run([NODE, "-e", script, str(BROWSER_APPLY), json.dumps(samples)],
                                   check=True, capture_output=True, timeout=60)
        self.assertEqual(set(json.loads(completed.stdout.decode("utf-8"))), {"합성건설"})


if __name__ == "__main__":
    unittest.main()
