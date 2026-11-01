"""실데이터 미리보기에서 막힌 양식 증상을 합성 픽스처로 재현한 테스트.

병합 셀 품명·병합된 머리 칸, 계산값 없는 수식 합계, 띄어쓰기·기호만 다른 현장명, 현장명 빈 칸(폴더 이름),
품목 없는 견적(합계 1식), 브라우저 적용 계획(--emit-plan)·결과 기록(--record-result).
실제 업체명·현장명·금액은 쓰지 않는다.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import estimates  # noqa: E402
import import_common  # noqa: E402
from estimate_sheet import NO_ITEMS_ERROR, SITE_MISSING_ERROR, TOTAL_FORMULA_WARNING, parse_estimate  # noqa: E402
from fixture_books import sample_cells, xls_bytes, xlsx_bytes  # noqa: E402
from import_common import site_key  # noqa: E402
import test_estimates_cli as cli_base  # noqa: E402
from test_estimates_cli import ACCESS, FakeGateway  # noqa: E402

BROWSER_APPLY = Path(__file__).resolve().parent / "browser_apply.js"
NODE = shutil.which("node")

# 품명(C열)이 13~15행(0-based 12~14)에 병합. 아래 두 행은 규격·수량·단가만 따로 있다.
MERGED_ITEMS = [
    {"no": 1, "name": "병합 품명", "spec": "EA", "qty": 2, "price": 1000, "amount": 2000},
    {"no": 2, "spec": "M", "qty": 3, "price": 500, "amount": 1500},
    {"no": 3, "spec": "식", "amount": 700},
    {"no": 4, "name": "단독 품목", "spec": "EA", "qty": 1, "price": 300, "amount": 300},
]
MERGED_RANGES = [(12, 2, 14, 4)]


def both(cells, merges):
    return [("xlsx", xlsx_bytes(cells, merges=merges)), ("xls", xls_bytes(cells, merges=merges))]


class MergedCellTest(unittest.TestCase):
    def test_merged_name_rows_become_items_xlsx_and_xls(self):
        results = []
        for label, data in both(sample_cells(MERGED_ITEMS, 4500), MERGED_RANGES):
            with self.subTest(fmt=label):
                parsed = parse_estimate(data)
                self.assertEqual(parsed.errors, [])
                self.assertEqual([i.name for i in parsed.items], ["병합 품명", "병합 품명", "병합 품명", "단독 품목"])
                self.assertEqual(parsed.computed_total, Decimal(4500))
                self.assertTrue(any("병합된 품명" in w and "2개" in w for w in parsed.warnings), parsed.warnings)
                results.append([(i.name, i.unit, i.quantity, i.unit_price) for i in parsed.items])
        self.assertEqual(results[0], results[1])

    def test_unmerged_blank_name_still_error(self):
        for label, data in both(sample_cells(MERGED_ITEMS, 4500), []):
            with self.subTest(fmt=label):
                parsed = parse_estimate(data)
                self.assertTrue(any("품명 없이" in e for e in parsed.errors), parsed.errors)

    def test_merged_amount_is_not_double_counted(self):
        items = [{"no": 1, "name": "높은 행 품목", "spec": "EA", "qty": 1, "price": 900, "amount": 900}, {"no": 2}]
        merges = [(12, 2, 13, 4), (12, 12, 13, 12), (12, 8, 13, 8), (12, 10, 13, 10)]
        for label, data in both(sample_cells(items, 900), merges):
            with self.subTest(fmt=label):
                parsed = parse_estimate(data)
                self.assertEqual(parsed.errors, [])
                self.assertEqual(len(parsed.items), 1)
                self.assertEqual(parsed.computed_total, Decimal(900))

    def test_merged_header_area_cells_resolve(self):
        cells = sample_cells(MERGED_ITEMS[3:], None, site=None, client=None)
        cells.update({(5, 4): "합성병합현장", (4, 1): "합성수신처 귀하", (10, 5): 300})
        merges = [(5, 4, 5, 8), (4, 1, 4, 3), (10, 5, 10, 8)]
        for label, data in both(cells, merges):
            with self.subTest(fmt=label):
                parsed = parse_estimate(data)
                self.assertEqual(parsed.errors, [])
                self.assertEqual((parsed.site_name, parsed.client_name, parsed.cell_total),
                                 ("합성병합현장", "합성수신처", Decimal(300)))


class ParserOptionTest(unittest.TestCase):
    def test_formula_total_without_cache_is_warning(self):
        parsed = parse_estimate(xlsx_bytes(sample_cells(MERGED_ITEMS[3:], "=SUM(M13:M20)")))
        self.assertEqual(parsed.errors, [])
        self.assertTrue(parsed.total_formula_uncached)
        self.assertIn(TOTAL_FORMULA_WARNING, parsed.warnings)
        self.assertIsNone(parsed.cell_total)

    def test_lump_when_empty(self):
        cells = sample_cells([], 5000)
        self.assertIn(NO_ITEMS_ERROR, parse_estimate(xlsx_bytes(cells)).errors)
        parsed = parse_estimate(xlsx_bytes(cells), lump_when_empty=True, lump_name="합성 견적 (합계 1식)")
        self.assertEqual(parsed.errors, [])
        self.assertTrue(parsed.lump_from_total)
        item = parsed.items[0]
        self.assertEqual((item.name, item.unit, item.quantity, item.unit_price),
                         ("합성 견적 (합계 1식)", "식", Decimal(1), Decimal(5000)))
        # 합계도 없으면 1식으로 만들 수 없어 ERROR 유지
        self.assertIn(NO_ITEMS_ERROR, parse_estimate(xlsx_bytes(sample_cells([], None)), lump_when_empty=True).errors)

    def test_site_key_normalizes_spacing_brackets_fullwidth_only(self):
        self.assertEqual(site_key("합성 마트"), site_key("합성마트"))
        self.assertEqual(site_key("합성마트(본점)"), site_key("합성 마트 [본점]"))
        self.assertEqual(site_key("ＡＢ　합성・현장-1"), site_key("ab 합성현장 1"))
        self.assertNotEqual(site_key("A 합성샵 지점"), site_key("A합성샾 지점"))  # 글자 오타는 고치지 않음


class RealDataCliTest(cli_base.CliBase):
    def write_in(self, folder, name, data):
        directory = self.tmp / "2025" / folder
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / name
        path.write_bytes(data)
        return path

    def item_cells(self, **kwargs):
        return sample_cells(MERGED_ITEMS[3:], 300, **kwargs)

    def env(self):
        return {import_common.ACCESS_ENV: ACCESS}

    def test_formula_total_blocks_apply_until_allowed(self):
        self.write("수식.xlsx", xlsx_bytes(sample_cells(MERGED_ITEMS[3:], "=SUM(M13:M20)")))
        code, out, _ = self.run_cli(self.site_dir)
        self.assertEqual(code, 0)
        self.assertIn("확인 필요", out)
        self.assertIn("--allow-missing-total", out)
        with FakeGateway() as gateway:
            code, _, err = self.run_cli(self.site_dir, "--apply", "--base-url", gateway.base_url, env=self.env())
            self.assertEqual(code, 1)
            self.assertIn("확인 필요", err)
            self.assertEqual(gateway.requests, [])
            code, out, err = self.run_cli(self.site_dir, "--apply", "--allow-missing-total", "--base-url",
                                          gateway.base_url, env=self.env())
            self.assertEqual(code, 0, out + err)
            self.assertEqual(len(gateway.posts("/api/v1/estimates")), 1)

    def test_same_site_candidates_need_alias(self):
        self.write_in("폴더1", "a.xlsx", xlsx_bytes(self.item_cells(site="합성 마트")))
        self.write_in("폴더2", "b.xlsx", xlsx_bytes(sample_cells(MERGED_ITEMS[:1], 2000, site="합성마트")))
        self.write_in("폴더3", "c.xlsx", xlsx_bytes(self.item_cells(site="합성베스트샵 1지점")))
        self.write_in("폴더4", "d.xlsx", xlsx_bytes(self.item_cells(site="합성베스트샾 1지점")))
        root = self.tmp / "2025"
        code, out, _ = self.run_cli(root, "--recursive")
        self.assertEqual(code, 0)
        self.assertIn("같은 현장 후보(미확정): '합성 마트' / '합성마트'", out)
        self.assertIn("비슷한 현장명(자동으로 묶지 않음", out)
        with FakeGateway() as gateway:
            code, _, err = self.run_cli(root, "--recursive", "--apply", "--base-url", gateway.base_url, env=self.env())
            self.assertEqual(code, 1)
            self.assertEqual(gateway.requests, [])

            alias = self.tmp / "alias.csv"
            alias.write_text("원본,적재\n합성마트,합성마트 본점\n합성베스트샾 1지점,합성베스트샵 1지점\n", encoding="utf-8-sig")
            code, out, err = self.run_cli(root, "--recursive", "--site-alias", alias, "--apply", "--base-url",
                                          gateway.base_url, env=self.env())
            self.assertEqual(code, 0, out + err)
            self.assertIn("같은 현장 후보(별칭으로 확정됨)", out)
            self.assertEqual(sorted(s["siteName"] for s in gateway.sites), ["합성마트 본점", "합성베스트샵 1지점"])
            self.assertEqual(len(gateway.posts("/api/v1/estimates")), 4)

    def test_alias_file_rules(self):
        self.write("a.xlsx", xlsx_bytes(self.item_cells()))
        inside = import_common.REPO_ROOT / "scripts" / "import" / "alias-should-not-exist.csv"
        code, _, err = self.run_cli(self.site_dir, "--site-alias", inside)
        self.assertEqual(code, 2)
        self.assertIn("저장소 밖", err)
        conflict = self.tmp / "conflict.json"
        conflict.write_text(json.dumps({"합성 현장": "갑", "합성현장": "을"}, ensure_ascii=False), encoding="utf-8")
        code, _, err = self.run_cli(self.site_dir, "--site-alias", conflict)
        self.assertEqual(code, 2)
        self.assertIn("적재 이름이 둘", err)

    def test_existing_site_with_same_key_stops_apply(self):
        self.write("a.xlsx", xlsx_bytes(self.item_cells(site="합성마트")))
        with FakeGateway() as gateway:
            gateway.sites.append({"id": 9, "siteName": "합성 마트", "client": None, "status": "IN_PROGRESS",
                                  "archivedAt": None})
            code, _, err = self.run_cli(self.site_dir, "--apply", "--base-url", gateway.base_url, env=self.env())
            self.assertEqual(code, 1)
            self.assertIn("띄어쓰기·기호만 다름", err)
            self.assertEqual(gateway.posts("/api/v1/sites"), [])
            self.assertEqual(gateway.posts("/api/v1/estimates"), [])

    def test_site_from_folder_only_with_option(self):
        self.write_in("합성폴더현장", "a.xlsx", xlsx_bytes(self.item_cells(site=None)))
        folder = self.tmp / "2025" / "합성폴더현장"
        code, out, _ = self.run_cli(folder)
        self.assertEqual(code, 1)
        self.assertIn(SITE_MISSING_ERROR, out)
        self.assertIn("현장명 후보: 폴더 이름 '합성폴더현장'", out)
        with FakeGateway() as gateway:
            code, out, err = self.run_cli(folder, "--site-from-folder", "--apply", "--base-url", gateway.base_url,
                                          env=self.env())
            self.assertEqual(code, 0, out + err)
            self.assertIn("(출처: 폴더 이름)", out)
            self.assertEqual([s["siteName"] for s in gateway.sites], ["합성폴더현장"])

    def test_date_folder_takes_upper_folder_name(self):
        folder = self.tmp / "2025" / "합성거래처폴더" / "11월 6일"
        folder.mkdir(parents=True)
        (folder / "a.xlsx").write_bytes(xlsx_bytes(self.item_cells(site=None)))
        code, out, _ = self.run_cli(folder, "--site-from-folder")
        self.assertEqual(code, 0, out)
        self.assertIn("폴더 이름 '합성거래처폴더 11월 6일'", out)

    def test_date_folder_under_collection_folder_takes_client_name(self):
        folder = self.tmp / "2025" / "2025년 합성 견적서" / "1월 4일"
        folder.mkdir(parents=True)
        (folder / "a.xlsx").write_bytes(xlsx_bytes(self.item_cells(site=None)))
        code, out, _ = self.run_cli(folder, "--site-from-folder")
        self.assertEqual(code, 0, out)
        self.assertIn("폴더 이름 '합성거래처 1월 4일'", out)

    def test_same_site_name_with_different_clients_blocks(self):
        self.write("a.xlsx", xlsx_bytes(self.item_cells(site="합성공통현장")))
        other = sample_cells([{"no": 1, "name": "자재", "spec": "EA", "qty": 1, "price": 1000, "amount": 1000}],
                             1000, client="다른거래처 귀하", site="합성공통현장")
        self.write("b.xlsx", xlsx_bytes(other))
        code, out, _ = self.run_cli(self.site_dir)
        self.assertIn("같은 현장명에 거래처가 여럿", out)
        code, _, _ = self.run_cli(self.site_dir, "--emit-plan", str(self.tmp / "plan.json"))
        self.assertEqual(code, 1)
        self.assertFalse((self.tmp / "plan.json").exists())

    def test_existing_site_of_other_client_stops_apply(self):
        self.write("a.xlsx", xlsx_bytes(self.item_cells(site="합성기존현장")))
        with FakeGateway() as gateway:
            gateway.sites.append({"id": 9, "siteName": "합성기존현장", "client": {"id": 777, "companyName": "남의거래처"},
                                  "status": "IN_PROGRESS", "archivedAt": None})
            code, _, err = self.run_cli(self.site_dir, "--apply", "--base-url", gateway.base_url, env=self.env())
            self.assertEqual(code, 1)
            self.assertIn("거래처가 견적 수신처와 다름", err)
            self.assertEqual(gateway.posts("/api/v1/estimates"), [])

    def test_year_folder_is_not_site_candidate(self):
        path = self.tmp / "2025" / "a.xlsx"
        path.write_bytes(xlsx_bytes(self.item_cells(site=None)))
        code, out, _ = self.run_cli(path, "--site-from-folder")
        self.assertEqual(code, 1)
        self.assertIn(SITE_MISSING_ERROR, out)

    def test_lump_when_empty_cli(self):
        self.write("합계만.xlsx", xlsx_bytes(sample_cells([], 5000)))
        self.assertEqual(self.run_cli(self.site_dir)[0], 1)
        with FakeGateway() as gateway:
            code, out, err = self.run_cli(self.site_dir, "--lump-when-empty", "--apply", "--base-url",
                                          gateway.base_url, env=self.env())
            self.assertEqual(code, 0, out + err)
            self.assertEqual(gateway.estimates[0]["items"], [
                {"itemName": "합계만 (합계 1식)", "unit": "식", "quantity": "1", "unitPrice": "5000"}])

    def test_apply_complete_sites(self):
        self.write("a.xlsx", xlsx_bytes(self.item_cells()))
        with FakeGateway() as gateway:
            code, out, err = self.run_cli(self.site_dir, "--apply", "--complete-sites", "--base-url",
                                          gateway.base_url, env=self.env())
            self.assertEqual(code, 0, out + err)
            self.assertEqual(gateway.sites[0]["status"], "COMPLETED")
            patches = [r for r in gateway.requests if r[0] == "PATCH" and r[1].endswith("/status")]
            self.assertEqual(len(patches), 1)
            self.run_cli(self.site_dir, "--apply", "--complete-sites", "--base-url", gateway.base_url, env=self.env())
            patches = [r for r in gateway.requests if r[0] == "PATCH" and r[1].endswith("/status")]
            self.assertEqual(len(patches), 1)  # 이미 완료면 다시 바꾸지 않음
        self.assertEqual([r["kind"] for r in self.ledger_rows()], ["estimate", "site-status"])


class BrowserPlanTest(cli_base.CliBase):
    def env(self):
        return {import_common.ACCESS_ENV: ACCESS}

    def emit(self, plan_path, *extra, base_url="http://127.0.0.1:3000"):
        return self.run_cli(self.site_dir, "--emit-plan", plan_path, "--base-url", base_url, *extra)

    def test_emit_plan_format_and_no_api_calls(self):
        self.write("견적1.xlsx", xlsx_bytes(sample_cells(MERGED_ITEMS[3:], 300, year="2025년")))
        plan_path = self.tmp / "out" / "plan.json"
        code, out, err = self.emit(plan_path, "--confirm-latest", "--complete-sites")
        self.assertEqual(code, 0, out + err)
        plan = json.loads(plan_path.read_text(encoding="utf-8"))
        self.assertEqual((plan["format"], plan["version"], plan["baseUrl"]),
                         ("buildflow-import-plan", 1, "http://127.0.0.1:3000"))
        self.assertEqual(plan["options"], {"confirmLatest": True, "completeSites": True})
        entry = plan["entries"][0]
        self.assertEqual(entry["siteName"], "합성현장A")
        self.assertEqual(entry["clientName"], "합성거래처")
        self.assertEqual(entry["newSiteMemo"], "견적서 표기 연도 2025")
        self.assertTrue(entry["confirm"])
        self.assertTrue(entry["marker"].startswith(estimates.MARKER_PREFIX))
        self.assertIn(entry["marker"], entry["estimate"]["memo"])
        self.assertNotIn("siteId", entry["estimate"])
        self.assertEqual(len(entry["sha256"]), 64)
        self.assertFalse(self.ledger.exists())
        self.assertNotIn(str(self.site_dir), plan_path.read_text(encoding="utf-8"))  # 원본 경로는 넣지 않음

    def test_emit_plan_rules(self):
        self.write("견적1.xlsx", xlsx_bytes(sample_cells(MERGED_ITEMS[3:], 300)))
        inside = import_common.REPO_ROOT / "scripts" / "import" / "plan-should-not-exist.json"
        self.assertEqual(self.emit(inside)[0], 2)
        self.assertFalse(inside.exists())
        self.assertEqual(self.emit(self.tmp / "p.json", base_url="https://example.org")[0], 2)
        self.write("불일치.xlsx", xlsx_bytes(sample_cells(MERGED_ITEMS[3:], 999)))
        code, _, err = self.emit(self.tmp / "p2.json")
        self.assertEqual(code, 1)
        self.assertFalse((self.tmp / "p2.json").exists())

    def result_json(self, base_url, sha, status="DRAFT", ok=True):
        return {
            "format": "buildflow-import-result", "version": 1, "baseUrl": base_url, "ok": ok, "error": None,
            "entries": [{"entry": 1, "sha256": sha, "sheet": "견적서", "rows": "13-13", "itemCount": 1,
                         "total": "300", "fileName": "견적1.xlsx", "estimateDate": "2025-01-01",
                         "dateSource": "file-mtime", "sheetYear": None, "estimateId": 301, "siteId": 201,
                         "clientId": 101, "createdSite": True, "createdClient": True, "status": status,
                         "action": "created"}],
            "siteStatus": [{"siteId": 201, "from": "IN_PROGRESS", "status": "COMPLETED"}],
        }

    def test_record_result_is_idempotent_and_validated(self):
        result_path = self.tmp / "result.json"
        sha = "a" * 64
        result_path.write_text(json.dumps(self.result_json("http://localhost:3000", sha)), encoding="utf-8")
        code, out, err = self.run_cli("--record-result", result_path)
        self.assertEqual(code, 0, out + err)
        self.assertEqual([r["kind"] for r in self.ledger_rows()], ["estimate", "site-status"])
        self.assertEqual(self.run_cli("--record-result", result_path)[0], 0)
        self.assertEqual(len(self.ledger_rows()), 2)
        # 확정 상태가 바뀐 결과는 한 줄 추가(마지막 기록이 이긴다)
        result_path.write_text(json.dumps(self.result_json("http://localhost:3000", sha, "CONFIRMED")), encoding="utf-8")
        self.run_cli("--record-result", result_path)
        rows = self.ledger_rows()
        self.assertEqual(len(rows), 3)
        self.assertEqual(rows[-1]["status"], "CONFIRMED")
        self.assertEqual(rows[-1]["appliedVia"], "browser")
        for bad in (self.result_json("https://example.org", sha), {"format": "other"},
                    self.result_json("http://localhost:3000", "xyz")):
            with self.subTest(bad=str(bad)[:40]):
                result_path.write_text(json.dumps(bad), encoding="utf-8")
                self.assertEqual(self.run_cli("--record-result", result_path)[0], 2)
        self.assertEqual(len(self.ledger_rows()), 3)
        self.assertEqual(self.run_cli(self.site_dir, "--record-result", result_path)[0], 2)

    def test_partial_browser_result_returns_failure_but_records(self):
        result_path = self.tmp / "result.json"
        result_path.write_text(json.dumps(self.result_json("http://localhost:3000", "b" * 64, ok=False)),
                               encoding="utf-8")
        code, _, err = self.run_cli("--record-result", result_path)
        self.assertEqual(code, 1)
        self.assertIn("중간에 멈췄습니다", err)
        self.assertEqual(len(self.ledger_rows()), 2)

    def run_node(self, plan_path, origin, token=ACCESS):
        runner = self.tmp / "runner.js"
        runner.write_text(
            "const fs = require('fs');\n"
            "const m = require(process.argv[2]);\n"
            "const plan = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));\n"
            "const storage = { getItem: (k) => k === 'buildflow-auth' ? JSON.stringify({ state: { accessToken: process.env.BF_T } }) : null };\n"
            "m.applyPlan(plan, { origin: process.argv[4], storage }).then((r) => fs.writeFileSync(process.argv[5], JSON.stringify(r)));\n",
            encoding="utf-8")
        out_path = self.tmp / "browser-result.json"
        env = {k: v for k, v in os.environ.items() if k not in (import_common.ACCESS_ENV, import_common.REFRESH_ENV)}
        env["BF_T"] = token
        subprocess.run([NODE, str(runner), str(BROWSER_APPLY), str(plan_path), origin, str(out_path)],
                       check=True, env=env, timeout=60, capture_output=True)
        return out_path, json.loads(out_path.read_text(encoding="utf-8"))

    @unittest.skipUnless(NODE, "node 없음")
    def test_browser_apply_end_to_end(self):
        import datetime as dt
        self.write("견적1.xlsx", xlsx_bytes(sample_cells(MERGED_ITEMS[3:], 300)), dt.date(2025, 1, 1))
        self.write("견적2.xlsx", xlsx_bytes(sample_cells(MERGED_ITEMS[:1], 2000)), dt.date(2025, 2, 1))
        plan_path = self.tmp / "plan.json"
        with FakeGateway() as gateway:
            code, out, err = self.emit(plan_path, "--confirm-latest", "--complete-sites", base_url=gateway.base_url)
            self.assertEqual(code, 0, out + err)
            self.assertEqual(gateway.requests, [])
            result_path, result = self.run_node(plan_path, gateway.base_url)
            self.assertTrue(result["ok"], result)
            self.assertEqual(len(gateway.posts("/api/v1/estimates")), 2)
            self.assertEqual(len(gateway.posts("/api/v1/sites")), 1)
            self.assertEqual({e["title"]: e["status"] for e in gateway.estimates},
                             {"견적1": "DRAFT", "견적2": "CONFIRMED"})
            self.assertEqual(gateway.sites[0]["status"], "COMPLETED")
            self.assertEqual(result["siteStatus"], [{"siteId": 200, "from": "IN_PROGRESS", "status": "COMPLETED"}])
            self.assertNotIn(ACCESS, result_path.read_text(encoding="utf-8"))
            self.assertTrue(all(r[3] == "Bearer " + ACCESS for r in gateway.requests))

            # 같은 계획을 다시 돌려도(기록 전) 표식으로 기존 견적에 연결된다
            _, again = self.run_node(plan_path, gateway.base_url)
            self.assertTrue(again["ok"], again)
            self.assertEqual({e["action"] for e in again["entries"]}, {"linked"})
            self.assertEqual(len(gateway.posts("/api/v1/estimates")), 2)

            code, out, err = self.run_cli("--record-result", result_path)
            self.assertEqual(code, 0, out + err)
            self.assertEqual(self.run_cli("--record-result", result_path)[0], 0)
            estimates_rows = [r for r in self.ledger_rows() if r["kind"] == "estimate"]
            self.assertEqual(len(estimates_rows), 2)
            self.assertTrue(all(r["baseUrl"] == gateway.base_url for r in estimates_rows))

            code, out, _ = self.emit(self.tmp / "plan2.json", base_url=gateway.base_url)
            self.assertEqual(code, 0)
            self.assertIn("이미 적재", out)
            self.assertEqual(json.loads((self.tmp / "plan2.json").read_text(encoding="utf-8"))["entries"], [])

    @unittest.skipUnless(NODE, "node 없음")
    def test_browser_apply_stops_on_existing_site_of_other_client(self):
        self.write("견적1.xlsx", xlsx_bytes(sample_cells(MERGED_ITEMS[3:], 300, site="합성기존현장")))
        plan_path = self.tmp / "plan.json"
        with FakeGateway() as gateway:
            gateway.clients.append({"id": 5, "companyName": "합성거래처"})
            gateway.sites.append({"id": 9, "siteName": "합성기존현장", "client": {"id": 777, "companyName": "남의거래처"},
                                  "status": "IN_PROGRESS", "archivedAt": None})
            self.assertEqual(self.emit(plan_path, base_url=gateway.base_url)[0], 0)
            _, result = self.run_node(plan_path, gateway.base_url)
            self.assertFalse(result["ok"])
            self.assertIn("거래처가 견적 수신처와 다름", result["error"])
            self.assertEqual(gateway.posts("/api/v1/estimates"), [])

    @unittest.skipUnless(NODE, "node 없음")
    def test_browser_apply_refuses_mismatched_origin_and_bad_token(self):
        self.write("견적1.xlsx", xlsx_bytes(sample_cells(MERGED_ITEMS[3:], 300)))
        plan_path = self.tmp / "plan.json"
        with FakeGateway() as gateway:
            self.assertEqual(self.emit(plan_path, base_url="http://localhost:3999")[0], 0)
            _, result = self.run_node(plan_path, gateway.base_url)
            self.assertFalse(result["ok"])
            self.assertIn("다릅니다", result["error"])
            self.assertEqual(gateway.requests, [])

            self.assertEqual(self.emit(plan_path, base_url=gateway.base_url)[0], 0)
            _, result = self.run_node(plan_path, gateway.base_url, token="wrong-token-value")
            self.assertFalse(result["ok"])
            self.assertIn("인증 만료", result["error"])
            self.assertEqual(gateway.posts("/api/v1/estimates"), [])
            self.assertNotIn("wrong-token-value", json.dumps(result))

            _, result = self.run_node(plan_path, "https://example.org")
            self.assertFalse(result["ok"])
            self.assertIn("로컬 개발 호스트", result["error"])

    @unittest.skipUnless(NODE, "node 없음")
    def test_site_key_matches_python(self):
        samples = ["합성 마트", "합성마트(본점)", "ＡＢ　합성・현장-1", "A 합성샵 지점", "Ab_C.d/e", "㈜합성 현장"]
        script = ("const m = require(process.argv[1]); const s = JSON.parse(process.argv[2]);"
                  "process.stdout.write(JSON.stringify(s.map(m.siteKey)))")
        completed = subprocess.run([NODE, "-e", script, str(BROWSER_APPLY), json.dumps(samples)],
                                   check=True, capture_output=True, timeout=60)
        self.assertEqual(json.loads(completed.stdout.decode("utf-8")), [site_key(s) for s in samples])


if __name__ == "__main__":
    unittest.main()
