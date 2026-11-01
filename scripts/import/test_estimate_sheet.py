"""견적서 엑셀 파서 단위 테스트 (합성 픽스처만 사용)."""

from __future__ import annotations

import datetime as dt
import sys
import unittest
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from estimate_sheet import SheetFormatError, parse_estimate, to_decimal  # noqa: E402
from fixture_books import sample_cells, xls_bytes, xlsx_bytes  # noqa: E402

ITEMS = [
    {"no": 1, "name": "합성 자재", "size": 1200, "size2": 600, "spec": "EA", "qty": 2, "price": 1500,
     "amount": 3000, "note": "비고값"},
    {"no": 2, "name": "합성 시공", "spec": "1식", "amount": 5000},
    {"no": 3, "amount": 0},
    {"no": 4, "name": "합성 마감", "spec": "M2", "qty": 1.5, "price": 2000, "amount": 3000},
]


def both(cells):
    return [("xlsx", xlsx_bytes(cells)), ("xls", xls_bytes(cells))]


class ParseTest(unittest.TestCase):
    def test_basic_layout_xlsx_and_xls_match(self):
        cells = sample_cells(ITEMS, 11000)
        results = []
        for label, data in both(cells):
            with self.subTest(fmt=label):
                parsed = parse_estimate(data)
                self.assertEqual(parsed.errors, [])
                self.assertEqual(parsed.client_name, "합성거래처")  # '귀하' 제거
                self.assertEqual(parsed.site_name, "합성현장A")
                self.assertEqual(parsed.cell_total, Decimal(11000))
                self.assertEqual(parsed.computed_total, Decimal(11000))
                self.assertEqual(len(parsed.items), 3)  # 품명 빈 행은 건너뜀
                results.append([(i.name, i.unit, i.quantity, i.unit_price) for i in parsed.items])
        self.assertEqual(results[0], results[1])

    def test_amount_only_row_becomes_lump_sum(self):
        parsed = parse_estimate(xlsx_bytes(sample_cells(ITEMS, 11000)))
        lump = parsed.items[1]
        self.assertEqual((lump.quantity, lump.unit_price, lump.unit), (Decimal(1), Decimal(5000), "식"))
        self.assertEqual(lump.name, "합성 시공 (1식)")  # 원래 규격은 품명에 남김

    def test_decimal_quantity_kept(self):
        parsed = parse_estimate(xlsx_bytes(sample_cells(ITEMS, 11000)))
        self.assertEqual(parsed.items[2].quantity, Decimal("1.5"))
        self.assertEqual(parsed.items[2].unit, "M2")

    def test_total_mismatch_is_error(self):
        for label, data in both(sample_cells(ITEMS, 12000)):
            with self.subTest(fmt=label):
                parsed = parse_estimate(data)
                self.assertTrue(any("합계금액 셀" in e for e in parsed.errors), parsed.errors)

    def test_missing_total_is_error(self):
        parsed = parse_estimate(xlsx_bytes(sample_cells(ITEMS, None)))
        self.assertTrue(any("합계금액 셀" in e for e in parsed.errors))

    def test_total_text_cell(self):
        parsed = parse_estimate(xlsx_bytes(sample_cells(ITEMS, "일금 일만일천원정 (₩11,000)")))
        self.assertEqual(parsed.cell_total, Decimal(11000))
        self.assertEqual(parsed.errors, [])

    def test_header_mapping_by_text_when_columns_shift(self):
        header = {2: "NO.", 3: "품 명", 6: "사이즈", 8: "규격", 9: "수량", 10: "총수량", 11: "단가", 13: "금액", 16: "비고"}
        cells = sample_cells([], 700, header=header, header_row=13)
        cells.update({(14, 3): "옮긴 품목", (14, 8): "EA", (14, 9): 7, (14, 11): 100, (14, 13): 700,
                      (14, 16): "옮긴 비고"})
        parsed = parse_estimate(xlsx_bytes(cells), append_note=True)
        self.assertEqual(parsed.errors, [])
        self.assertEqual(len(parsed.items), 1)
        item = parsed.items[0]
        self.assertEqual((item.name, item.unit, item.quantity, item.unit_price),
                         ("옮긴 품목 (옮긴 비고)", "EA", Decimal(7), Decimal(100)))

    def test_append_size_and_note(self):
        parsed = parse_estimate(xlsx_bytes(sample_cells(ITEMS, 11000)), append_size=True, append_note=True)
        self.assertEqual(parsed.items[0].name, "합성 자재 (1200 600, 비고값)")
        plain = parse_estimate(xlsx_bytes(sample_cells(ITEMS, 11000)))
        self.assertEqual(plain.items[0].name, "합성 자재")

    def test_summary_rows_and_trailing_blank_rows_skipped(self):
        items = ITEMS + [{"no": 5}, {"no": 6, "amount": 0}, {"name": "소 계", "amount": 11000}]
        parsed = parse_estimate(xlsx_bytes(sample_cells(items, 11000)))
        self.assertEqual(parsed.errors, [])
        self.assertEqual(len(parsed.items), 3)
        self.assertTrue(any("합계·소계" in w for w in parsed.warnings))

    def test_amount_without_name_is_error(self):
        items = ITEMS + [{"no": 5, "amount": 100}]
        parsed = parse_estimate(xlsx_bytes(sample_cells(items, 11100)))
        self.assertTrue(any("품명 없이" in e for e in parsed.errors))

    def test_qty_price_amount_mismatch_warns(self):
        items = [{"no": 1, "name": "반올림 품목", "spec": "EA", "qty": 3, "price": 333, "amount": 1000}]
        parsed = parse_estimate(xlsx_bytes(sample_cells(items, 999)))
        self.assertEqual(parsed.errors, [])
        self.assertTrue(any("수량×단가" in w for w in parsed.warnings))

    def test_amount_cell_used_when_it_matches_total(self):
        items = [{"no": 1, "name": "자재", "spec": "M", "qty": 6, "price": 10000, "amount": 80000},
                 {"no": 2, "name": "시공", "spec": "품", "qty": 2, "price": 300000, "amount": 600000}]
        parsed = parse_estimate(xlsx_bytes(sample_cells(items, 680000)))
        self.assertEqual(parsed.errors, [])
        first = parsed.items[0]
        self.assertEqual((first.name, first.unit, first.quantity, first.unit_price),
                         ("자재 (6M × 10,000)", "식", Decimal(1), Decimal(80000)))
        self.assertEqual(parsed.items[1].unit_price, Decimal(300000))  # 맞는 행은 그대로
        self.assertTrue(any("금액 셀 기준 1식" in w for w in parsed.warnings))

    def test_total_qty_with_unit_text(self):
        items = [{"no": 1, "name": "파이프", "spec": "본", "qty": 2, "total_qty": "7.6M", "price": 1000,
                  "amount": 7600},
                 {"no": 2, "name": "사다리", "spec": "M", "qty": 6, "total_qty": "4본", "amount": 80000}]
        parsed = parse_estimate(xlsx_bytes(sample_cells(items, 87600)))
        self.assertEqual(parsed.errors, [])
        self.assertEqual(parsed.items[0].quantity, Decimal("7.6"))
        self.assertEqual(parsed.items[1].unit_price, Decimal(80000))

    def test_scale_over_two_places_is_error(self):
        items = [{"no": 1, "name": "소수 품목", "spec": "EA", "qty": 0.333, "price": 3}]
        parsed = parse_estimate(xlsx_bytes(sample_cells(items, 1)))
        self.assertTrue(any("소수 2자리" in e for e in parsed.errors))

    def test_scale_over_two_places_uses_amount_cell(self):
        items = [{"no": 1, "name": "소수 품목", "spec": "EA", "qty": 0.333, "price": 3, "amount": 1}]
        parsed = parse_estimate(xlsx_bytes(sample_cells(items, 1)))
        self.assertEqual(parsed.errors, [])
        self.assertEqual((parsed.items[0].quantity, parsed.items[0].unit_price), (Decimal(1), Decimal(1)))

    def test_quantity_formula_text_is_calculated(self):
        items = [{"no": 1, "name": "판넬", "spec": "M2", "qty": "(1.5*2)+1", "price": 1000, "amount": 4000},
                 {"no": 2, "name": "반 자재", "spec": "EA", "qty": "½", "price": 2000, "amount": 1000},
                 {"no": 3, "name": "위험 식", "spec": "EA", "qty": "__import__('os')", "price": 1, "amount": 1}]
        parsed = parse_estimate(xlsx_bytes(sample_cells(items, 5001)))
        self.assertEqual(parsed.items[0].quantity, Decimal(4))
        self.assertEqual(parsed.items[0].name, "판넬 (산출 (1.5*2)+1)")
        self.assertEqual(parsed.items[1].quantity, Decimal("0.5"))
        self.assertTrue(any("숫자가 아닌 값" in e for e in parsed.errors))

    def test_negative_amount_is_error(self):
        items = ITEMS + [{"no": 5, "name": "할인", "amount": -1000}]
        parsed = parse_estimate(xlsx_bytes(sample_cells(items, 10000)))
        self.assertTrue(any("음수" in e for e in parsed.errors))

    def test_dates_and_sheet_year(self):
        for label, data in both(sample_cells(ITEMS, 11000, date=dt.date(2025, 3, 4), year="2024년")):
            with self.subTest(fmt=label):
                parsed = parse_estimate(data)
                self.assertEqual(parsed.estimate_date, dt.date(2025, 3, 4))
                self.assertTrue(parsed.date_source.startswith("file-cell"))
                self.assertEqual(parsed.sheet_year, 2024)  # 연도 표기는 날짜로 쓰지 않음
        undated = parse_estimate(xlsx_bytes(sample_cells(ITEMS, 11000, year="2024년")))
        self.assertIsNone(undated.estimate_date)
        no_year = parse_estimate(xlsx_bytes(sample_cells(ITEMS, 11000, year="")))
        self.assertIsNone(no_year.sheet_year)

    def test_not_excel(self):
        with self.assertRaises(SheetFormatError):
            parse_estimate(b"plain text")

    def test_to_decimal_plain(self):
        self.assertEqual(str(to_decimal(1500.0)), "1500")
        self.assertEqual(str(to_decimal("1,234.50")), "1234.5")
        self.assertIsNone(to_decimal("-"))


if __name__ == "__main__":
    unittest.main()
