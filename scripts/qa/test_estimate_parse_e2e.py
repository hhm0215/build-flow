"""estimate_parse_e2e.py 단위 테스트 — Docker·네트워크 없이 실행한다.

python -I -X utf8 -m unittest discover -s scripts/qa -p "test_*.py"
"""

import io
import json
import random
import unittest
import zipfile
from decimal import Decimal
from pathlib import Path

import estimate_parse_e2e as parse
import warranty_ocr_e2e as ocr


def as_parsed(items, **override):
    return [{"itemName": i.name, "unit": i.unit, "quantity": float(i.quantity), "unitPrice": 0, "amount": 0,
             **override} for i in items]


class ItemTests(unittest.TestCase):
    def test_items_are_five_to_eight_and_include_blank_spec(self):
        for seed in range(30):
            items = parse.make_items(random.Random(seed))
            self.assertTrue(5 <= len(items) <= 8)
            self.assertEqual(len({i.name for i in items}), len(items))
            self.assertTrue(any(i.spec == "" for i in items))

    def test_boq_has_header_section_subtotal_total_and_blank_rows(self):
        items = parse.make_items(random.Random(1))
        rows = parse.boq_rows(items, "합성")
        flat = ["|".join(map(str, r)) for r in rows]
        self.assertIn([], rows)
        self.assertTrue(any("품명" in r and "단가" in r for r in flat))
        self.assertTrue(any("소계" in r for r in flat))
        self.assertTrue(any("합계" in r for r in flat))
        item_rows = [r for r in rows if r and isinstance(r[0], int)]
        self.assertEqual(len(item_rows), len(items))
        self.assertTrue(all(r[5] == "" and r[6] == "" for r in item_rows))  # 단가·금액 빈칸

    def test_xlsx_omits_blank_cells_and_escapes_text(self):
        data = parse.build_xlsx([["a&b", "", 3], [], ["x"]])
        with zipfile.ZipFile(io.BytesIO(data)) as zf:
            sheet = zf.read("xl/worksheets/sheet1.xml").decode("utf-8")
            self.assertIn("xl/styles.xml", zf.namelist())
        self.assertIn("a&amp;b", sheet)
        self.assertNotIn('r="B1"', sheet)
        self.assertIn('<c r="C1"><v>3</v></c>', sheet)
        self.assertNotIn('<row r="2">', sheet)


class EvaluateTests(unittest.TestCase):
    def setUp(self):
        self.items = parse.make_items(random.Random(7))

    def test_exact_match_passes(self):
        result = parse.evaluate_parse(self.items, as_parsed(self.items))
        self.assertTrue(parse.parse_passes(result))
        self.assertEqual(result["null_price"], 0)

    def test_whitespace_reorder_and_spec_suffix_are_tolerated(self):
        parsed = list(reversed(as_parsed(self.items)))
        parsed[0]["itemName"] = " " + parsed[0]["itemName"].replace(" ", "  ") + " "
        parsed[1]["itemName"] = parsed[1]["itemName"] + " 규격"
        result = parse.evaluate_parse(self.items, parsed)
        self.assertTrue(parse.parse_passes(result))
        self.assertEqual(result["name_with_spec"], 1)

    def test_extra_total_row_missing_item_wrong_qty_and_unit_fail(self):
        parsed = as_parsed(self.items)
        parsed.append({"itemName": "합계", "unit": "", "quantity": 0})
        self.assertFalse(parse.parse_passes(parse.evaluate_parse(self.items, parsed)))
        self.assertFalse(parse.parse_passes(parse.evaluate_parse(self.items, as_parsed(self.items)[1:])))
        wrong = as_parsed(self.items)
        wrong[0]["quantity"] = 999
        self.assertFalse(parse.parse_passes(parse.evaluate_parse(self.items, wrong)))
        wrong = as_parsed(self.items)
        wrong[0]["unit"] = "규격값"
        self.assertFalse(parse.parse_passes(parse.evaluate_parse(self.items, wrong)))

    def test_null_price_counted(self):
        result = parse.evaluate_parse(self.items, as_parsed(self.items, unitPrice=None, amount=None))
        self.assertEqual(result["null_price"], len(self.items))

    def test_draft_total(self):
        body = parse.draft_items(as_parsed(self.items), random.Random(2))
        self.assertTrue(all(b["unitPrice"] >= 1000 for b in body))
        self.assertEqual(parse.expected_total([{"quantity": "2.5", "unitPrice": 1000}, {"quantity": 1, "unitPrice": 3}]),
                         Decimal("2503"))


class ComposeTests(unittest.TestCase):
    def test_isolated_compose(self):
        spec = parse.build_compose(Path("C:/repo"), 18083)
        text = json.dumps(spec)
        self.assertEqual(spec["name"], "bfqaparse")
        self.assertNotIn("container_name", text)
        self.assertNotIn("notification-service", spec["services"])
        self.assertEqual(sorted(spec["volumes"]), ["kafka_data", "mysql_data", "redis_data"])
        ports = [p for svc in spec["services"].values() for p in svc.get("ports", [])]
        self.assertEqual(ports, ["127.0.0.1:18083:8080"])
        est = spec["services"]["estimate-service"]
        self.assertEqual(est["image"], "bfqaparse-estimate-service:local")
        self.assertEqual(est["environment"]["OLLAMA_URL"], "${BFQA_OLLAMA_URL:?run the script}")
        self.assertEqual(est["environment"]["CLAUDE_API_KEY"], "")
        self.assertIn("host.docker.internal:host-gateway", est["extra_hosts"])
        self.assertNotIn("build-flow", ocr.volume_names(parse.PROJECT)[0])


if __name__ == "__main__":
    unittest.main()
