"""견적 적재 오독 방어·거래처 별칭·회사 키 거래처 재사용 테스트(합성 픽스처만, 실데이터 없음).

- 현장명·수신처가 양식 제목 글자, 단위 낱말, 숫자·금액만으로 읽히면 ERROR
- --site-from-folder 현장명이 날짜만 남으면 --site-alias로 지정할 때까지 ERROR
- --client-alias로 수신처를 정식 거래처명으로 바꾸고, 표기만 다른 수신처가 섞이면 경고
- --apply(Resolver)와 browser_apply.js가 같은 규칙으로 회사 키가 같은 활성 거래처를 재사용
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import import_common  # noqa: E402
from estimate_sheet import FORM_TEXT_ERROR, form_text_reason, parse_estimate  # noqa: E402
from estimates import DATE_ONLY_SITE_ERROR  # noqa: E402
from fixture_books import sample_cells, xls_bytes, xlsx_bytes  # noqa: E402
import test_estimates_cli as cli_base  # noqa: E402
from test_estimates_cli import ACCESS, FakeGateway  # noqa: E402
from test_real_data_cases import BROWSER_APPLY, NODE  # noqa: E402

ITEM = [{"no": 1, "name": "합성 자재", "spec": "EA", "qty": 1, "price": 300, "amount": 300}]


def cells(**kwargs):
    return sample_cells(ITEM, 300, **kwargs)


class FormTextReasonTest(unittest.TestCase):
    def test_patterns(self):
        cases = {
            "견적": "양식 제목", "견 적 서": "양식 제목", "공내역서": "양식 제목", "합계": "양식 제목",
            "EA": "단위", "set": "단위", "M2": "단위", "㎡": "단위", "식": "단위", "본": "단위",
            "20000": "숫자·금액", "20,000원": "숫자·금액", "₩1,500": "숫자·금액", "3.5": "숫자·금액",
        }
        for value, reason in cases.items():
            with self.subTest(value=value):
                self.assertEqual(form_text_reason(value), reason)

    def test_normal_names_pass(self):
        for value in ("", "합성현장A", "합성 A동 공사", "M동 합성", "합성 2지점", "SM 합성", "3월 31일", "합성견적센터"):
            with self.subTest(value=value):
                self.assertIsNone(form_text_reason(value))


class ParseGuardTest(unittest.TestCase):
    def assert_form_error(self, parsed, label):
        hits = [e for e in parsed.errors if e.startswith(FORM_TEXT_ERROR) and label in e]
        self.assertTrue(hits, parsed.errors)

    def test_site_cell_with_form_title(self):
        for kind, data in (("xlsx", xlsx_bytes(cells(site="견적"))), ("xls", xls_bytes(cells(site="견 적 서")))):
            with self.subTest(kind=kind):
                self.assert_form_error(parse_estimate(data), "현장명")

    def test_shifted_columns_unit_and_number(self):
        parsed = parse_estimate(xlsx_bytes(cells(client="EA", site=20000)))
        self.assert_form_error(parsed, "현장명")
        self.assert_form_error(parsed, "수신처")

    def test_client_form_title_and_normal_values(self):
        self.assert_form_error(parse_estimate(xlsx_bytes(cells(client="합계"))), "수신처")
        ok = parse_estimate(xlsx_bytes(cells(client="합성 디자인 귀하", site="합성 A동")))
        self.assertEqual(ok.errors, [])


class GuardCliTest(cli_base.CliBase):
    def env(self):
        return {import_common.ACCESS_ENV: ACCESS}

    def test_form_text_cell_blocks_apply(self):
        self.write("a.xlsx", xlsx_bytes(cells(site="견적")))
        with FakeGateway() as gateway:
            code, out, err = self.run_cli(self.site_dir, "--apply", "--base-url", gateway.base_url, env=self.env())
            self.assertEqual(code, 1)
            self.assertIn(FORM_TEXT_ERROR, out)
            self.assertEqual(gateway.requests, [])

    def test_date_only_folder_site_needs_alias(self):
        folder = self.tmp / "2024" / "2024년 합성 견적 모음" / "3월 31일"
        folder.mkdir(parents=True)
        (folder / "a.xlsx").write_bytes(xlsx_bytes(cells(client=None, site=None)))
        code, out, _ = self.run_cli(folder, "--site-from-folder")
        self.assertEqual(code, 1)
        self.assertIn(DATE_ONLY_SITE_ERROR, out)
        self.assertIn("'3월 31일'", out)
        alias = self.tmp / "site-alias.csv"
        alias.write_text("원본,적재\n3월 31일,합성 날짜현장\n", encoding="utf-8")
        with FakeGateway() as gateway:
            code, out, err = self.run_cli(folder, "--site-from-folder", "--site-alias", alias, "--apply",
                                          "--base-url", gateway.base_url, env=self.env())
            self.assertEqual(code, 0, out + err)
            self.assertEqual([s["siteName"] for s in gateway.sites], ["합성 날짜현장"])

    def test_date_folder_with_upper_name_still_ok(self):
        folder = self.tmp / "2024" / "합성거래처폴더" / "3월 31일"
        folder.mkdir(parents=True)
        (folder / "a.xlsx").write_bytes(xlsx_bytes(cells(client=None, site=None)))
        code, out, _ = self.run_cli(folder, "--site-from-folder")
        self.assertEqual(code, 0, out)
        self.assertNotIn(DATE_ONLY_SITE_ERROR, out)

    def test_form_title_folder_site_is_error(self):
        folder = self.tmp / "2024" / "견적서"
        folder.mkdir(parents=True)
        (folder / "a.xlsx").write_bytes(xlsx_bytes(cells(site=None)))
        code, out, _ = self.run_cli(folder, "--site-from-folder")
        self.assertEqual(code, 1)
        self.assertIn(FORM_TEXT_ERROR, out)

    def test_mixed_client_spelling_warns_and_alias_resolves(self):
        self.write("a.xlsx", xlsx_bytes(cells(client="합성디자인 귀하")))
        self.write("b.xlsx", xlsx_bytes(cells(client="합성 디자인 귀하", site="합성현장B")))
        self.write("c.xlsx", xlsx_bytes(cells(client="합성로담", site="합성현장C")))
        code, out, _ = self.run_cli(self.site_dir)
        self.assertEqual(code, 0, out)
        self.assertIn("같은 회사로 보이는 수신처 표기가 섞임", out)
        self.assertIn("'합성 디자인' / '합성디자인'", out)

        alias = self.tmp / "client-alias.csv"
        alias.write_text("견적서 수신처,DB 거래처명\n합성 디자인,합성디자인(주)\n합성로담,합성로담건축(주)\n",
                         encoding="utf-8-sig")
        plan_path = self.tmp / "plan.json"
        code, out, err = self.run_cli(self.site_dir, "--client-alias", alias, "--emit-plan", plan_path)
        self.assertEqual(code, 0, out + err)
        self.assertNotIn("같은 회사로 보이는 수신처 표기가 섞임", out)
        self.assertIn("수신처 '합성로담' → 거래처 '합성로담건축(주)'(--client-alias)", out)
        entries = json.loads(plan_path.read_text(encoding="utf-8"))["entries"]
        self.assertEqual(sorted(e["clientName"] for e in entries),
                         ["합성디자인(주)", "합성디자인(주)", "합성로담건축(주)"])
        self.assertTrue(all(e["clientAliased"] for e in entries))

    def test_client_alias_file_must_be_outside_repo(self):
        self.write("a.xlsx", xlsx_bytes(cells()))
        inside = import_common.REPO_ROOT / "scripts" / "import" / "estimate-client-alias-should-not-exist.csv"
        code, _, err = self.run_cli(self.site_dir, "--client-alias", inside)
        self.assertEqual(code, 2)
        self.assertIn("저장소 밖", err)

    def test_apply_reuses_active_key_matched_client(self):
        self.write("a.xlsx", xlsx_bytes(cells(client="합성거래처 귀하")))
        with FakeGateway() as gateway:
            gateway.clients.extend([
                {"id": 7, "companyName": "합성거래처", "archivedAt": "2025-01-01T00:00:00"},
                {"id": 8, "companyName": "㈜ 합성거래처", "archivedAt": None},
            ])
            code, out, err = self.run_cli(self.site_dir, "--apply", "--base-url", gateway.base_url, env=self.env())
            self.assertEqual(code, 0, out + err)
            self.assertEqual(gateway.posts("/api/v1/clients"), [])
            self.assertEqual(gateway.sites[0]["client"]["id"], 8)
            self.assertIn("기존·표기만 다름", out)
        row = self.ledger_rows()[0]
        self.assertEqual((row["clientId"], row["clientRule"], row["createdClient"]), (8, "key", False))

    def test_apply_stops_on_archived_key_match_or_many_active(self):
        self.write("a.xlsx", xlsx_bytes(cells(client="합성거래처 귀하")))
        for clients, message in (
            ([{"id": 7, "companyName": "합성거래처(주)", "archivedAt": "2025-01-01T00:00:00"}], "보관된 거래처"),
            ([{"id": 7, "companyName": "합성거래처(주)", "archivedAt": None},
              {"id": 8, "companyName": "합성 거래처", "archivedAt": None}], "표기만 다른 활성 거래처가 2곳"),
        ):
            with self.subTest(message=message), FakeGateway() as gateway:
                gateway.clients.extend(clients)
                code, _, err = self.run_cli(self.site_dir, "--apply", "--base-url", gateway.base_url, env=self.env())
                self.assertEqual(code, 1)
                self.assertIn(message, err)
                self.assertEqual(gateway.posts("/api/v1/clients"), [])
                self.assertEqual(gateway.posts("/api/v1/estimates"), [])


class BrowserClientKeyTest(cli_base.CliBase):
    def emit(self, plan_path, base_url):
        return self.run_cli(self.site_dir, "--emit-plan", plan_path, "--base-url", base_url)

    def run_node(self, plan_path, origin):
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
        env["BF_T"] = ACCESS
        subprocess.run([NODE, str(runner), str(BROWSER_APPLY), str(plan_path), origin, str(out_path)],
                       check=True, env=env, timeout=60, capture_output=True)
        return json.loads(out_path.read_text(encoding="utf-8"))

    @unittest.skipUnless(NODE, "node 없음")
    def test_key_match_reuses_active_client_over_archived_same_name(self):
        self.write("a.xlsx", xlsx_bytes(cells(client="합성거래처 귀하")))
        plan_path = self.tmp / "plan.json"
        with FakeGateway() as gateway:
            gateway.clients.extend([
                {"id": 7, "companyName": "합성거래처", "archivedAt": "2025-01-01T00:00:00"},
                {"id": 8, "companyName": "합성거래처 주식회사", "archivedAt": None},
            ])
            self.assertEqual(self.emit(plan_path, gateway.base_url)[0], 0)
            result = self.run_node(plan_path, gateway.base_url)
            self.assertTrue(result["ok"], result)
            self.assertEqual(gateway.posts("/api/v1/clients"), [])
            entry = result["entries"][0]
            self.assertEqual((entry["clientId"], entry["clientRule"], entry["createdClient"]), (8, "key", False))
            self.assertEqual(gateway.sites[0]["client"]["id"], 8)
            # 결과 기록에도 clientRule을 남긴다
            result_path = self.tmp / "result.json"
            result_path.write_text(json.dumps(result), encoding="utf-8")
            self.assertEqual(self.run_cli("--record-result", result_path)[0], 0)
            self.assertEqual(self.ledger_rows()[0]["clientRule"], "key")

    @unittest.skipUnless(NODE, "node 없음")
    def test_exact_then_created_rules(self):
        self.write("a.xlsx", xlsx_bytes(cells(client="합성거래처 귀하")))
        self.write("b.xlsx", xlsx_bytes(sample_cells(ITEM, 300, client="합성신규처 귀하", site="합성현장B")))
        self.write("c.xlsx", xlsx_bytes(sample_cells(
            [{"no": 1, "name": "합성 다른 자재", "spec": "EA", "qty": 2, "price": 300, "amount": 600}], 600,
            client="(주)합성신규처", site="합성현장C")))
        plan_path = self.tmp / "plan.json"
        with FakeGateway() as gateway:
            gateway.clients.append({"id": 5, "companyName": "합성거래처", "archivedAt": None})
            self.assertEqual(self.emit(plan_path, gateway.base_url)[0], 0)
            result = self.run_node(plan_path, gateway.base_url)
            self.assertTrue(result["ok"], result)
            rules = {e["fileName"]: (e["clientRule"], e["createdClient"]) for e in result["entries"]}
            # 새로 만든 거래처는 같은 실행의 다음 표기 변형('(주)합성신규처')이 키로 재사용한다
            self.assertEqual(rules, {"a.xlsx": ("exact", False), "b.xlsx": ("created", True),
                                     "c.xlsx": ("key", False)})
            self.assertEqual([r[2]["companyName"] for r in gateway.posts("/api/v1/clients")], ["합성신규처"])

    @unittest.skipUnless(NODE, "node 없음")
    def test_archived_key_match_stops(self):
        self.write("a.xlsx", xlsx_bytes(cells(client="합성거래처 귀하")))
        plan_path = self.tmp / "plan.json"
        with FakeGateway() as gateway:
            gateway.clients.append({"id": 7, "companyName": "합성거래처(주)", "archivedAt": "2025-01-01T00:00:00"})
            self.assertEqual(self.emit(plan_path, gateway.base_url)[0], 0)
            result = self.run_node(plan_path, gateway.base_url)
            self.assertFalse(result["ok"])
            self.assertIn("보관된 거래처", result["error"])
            self.assertEqual(gateway.posts("/api/v1/clients"), [])


if __name__ == "__main__":
    unittest.main()
