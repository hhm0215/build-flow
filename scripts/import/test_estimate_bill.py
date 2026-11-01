"""내역서형 견적 양식 해석 단위 테스트 (합성 픽스처만 사용)."""

from __future__ import annotations

import sys
import unittest
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from estimate_sheet import SITE_MISSING_ERROR, parse_estimate  # noqa: E402
from fixture_books import bill_cells, sample_cells, xls_bytes, xlsx_bytes  # noqa: E402

BASIC = [
    {"name": "■ 합성 공사 제목"},
    {"mark": "*", "no": 1, "name": "합성 구역"},
    {"mark": "*", "no": "1-1", "name": "벽체"},
    {"mark": "*", "name": "<합성 구역 제목>", "amount": 0},
    {"mark": "공종A", "name": "합성 구조틀", "spec": "30*30", "unit": "M2", "qty": 10, "price": 1000,
     "amount": 10000, "note": "비고값"},
    {"mark": "공종A", "name": "합성 몰딩", "unit": "M", "price": 500, "amount": 0},  # 단가만 있는 미기재 행
    {"mark": "공종A", "name": "합성 마감", "unit": "M", "qty": 2.333, "price": 2000, "amount": 4666},
    {"mark": "*", "name": "소   계", "amount": 14666},
    {"mark": "*", "no": "1-2", "name": "천장"},
    {"mark": "공종A", "name": "합성 등박스", "spec": "벽부", "unit": "M", "qty": 4, "price": 3000,
     "amount": 12000},
    {"name": "합성 보강", "unit": "식", "qty": 1, "price": 0},  # 금액 없는 행
    {"mark": "*", "name": "소   계", "amount": 12000},
    {"mark": "*", "name": "합   계", "amount": 26666},
    {"label": "공과잡비", "amount": 1334},
    {"label": "합계금액", "amount": 28000},
]


def both(cells):
    return [("xlsx", xlsx_bytes(cells, "합성")), ("xls", xls_bytes(cells, "합성"))]


class BillLayoutTest(unittest.TestCase):
    def test_basic_bill_xlsx_and_xls_match(self):
        results = []
        for label, data in both(bill_cells(BASIC)):
            with self.subTest(fmt=label):
                parsed = parse_estimate(data)
                self.assertEqual(parsed.layout, "bill")
                self.assertEqual(parsed.errors, [SITE_MISSING_ERROR])  # 현장명은 폴더 이름으로만
                self.assertEqual(parsed.cell_total, Decimal(28000))
                self.assertEqual(parsed.computed_total, Decimal(28000))
                self.assertEqual(parsed.client_name, "")
                results.append([(i.name, i.unit, i.quantity, i.unit_price) for i in parsed.items])
                self.assertTrue(any("'합계금액' 금액을 합계로 사용" in w for w in parsed.warnings), parsed.warnings)
                self.assertTrue(any("구역 제목·공종 표시 행 5개" in w for w in parsed.warnings), parsed.warnings)
                self.assertTrue(any("금액 없는 품명 행 2개" in w for w in parsed.warnings), parsed.warnings)
        self.assertEqual(results[0], results[1])
        self.assertEqual(results[0], [
            ("합성 구조틀 (30*30)", "M2", Decimal(10), Decimal(1000)),
            ("합성 마감 (2.33M × 2,000)", "식", Decimal(1), Decimal(4666)),
            ("합성 등박스 (벽부)", "M", Decimal(4), Decimal(3000)),
            ("공과잡비", "식", Decimal(1), Decimal(1334)),
        ])

    def test_note_appended_only_with_flag(self):
        parsed = parse_estimate(xlsx_bytes(bill_cells(BASIC)), append_note=True)
        self.assertEqual(parsed.items[0].name, "합성 구조틀 (30*30, 비고값)")

    def test_three_row_header_with_total_in_no_column(self):
        header = [
            {2: "구분", 3: "품 명", 4: "규 격", 5: "단 위", 6: "수 량", 7: "기준 단가", 13: "비 고"},
            {7: "재 료 비", 9: "노 무 비", 11: "합 계"},
            {7: "단가", 8: "금액", 9: "단가", 10: "금액", 11: "단가", 12: "금액"},
        ]
        rows = [
            {"no": "<합성 품목>"},
            {"mark": "*", "no": 3, "name": "바 닥 공 사"},
            {"mark": "공종B", "no": "3-1", "name": "합성 슬로프", "spec": "체크 플레이트", "unit": "m2",
             "price": 7000, "amount": 7000},  # 수량 없이 금액 = 단가 → 1식
            {"mark": "공종B", "no": "3-2", "spec": "각파이프 30*30", "unit": "m", "qty": 5, "price": 600,
             "amount": 3000},  # 품명 칸이 비면 규격을 품명으로
            {"mark": "*", "name": "바 닥 공 사    소    계", "amount": 10000},
            {"mark": "*", "no": "공사 합계", "amount": 10000},
        ]
        parsed = parse_estimate(xlsx_bytes(bill_cells(rows, header=header, header_row=4)))
        self.assertEqual(parsed.layout, "bill")
        self.assertEqual(parsed.errors, [SITE_MISSING_ERROR])
        self.assertEqual(parsed.cell_total, Decimal(10000))
        self.assertEqual([(i.name, i.unit, i.quantity, i.unit_price) for i in parsed.items], [
            ("합성 슬로프 (체크 플레이트, m2)", "식", Decimal(1), Decimal(7000)),
            ("각파이프 30*30", "m", Decimal(5), Decimal(600)),
        ])

    def test_item_sum_mismatch_is_error(self):
        rows = BASIC[:-1] + [{"label": "합계금액", "amount": 29000}]
        parsed = parse_estimate(xlsx_bytes(bill_cells(rows)))
        self.assertTrue(any("품목 합계(28,000) ≠ 합계금액 셀(29,000)" in e for e in parsed.errors), parsed.errors)

    def test_missing_total_row_is_error(self):
        rows = [r for r in BASIC if r.get("name") not in ("소   계", "합   계") and "label" not in r]
        parsed = parse_estimate(xlsx_bytes(bill_cells(rows)))
        self.assertIsNone(parsed.cell_total)
        self.assertTrue(any("합계 행" in e and "찾지 못해" in e for e in parsed.errors), parsed.errors)

    def test_adjustment_without_final_total_is_error(self):
        parsed = parse_estimate(xlsx_bytes(bill_cells(BASIC[:-1])))
        self.assertTrue(any("최종 합계금액 행으로 검증할 수 없음" in e for e in parsed.errors), parsed.errors)

    def test_amount_without_name_is_error(self):
        rows = BASIC[:5] + [{"mark": "공종A", "unit": "M", "qty": 1, "price": 100, "amount": 100}] + BASIC[5:]
        parsed = parse_estimate(xlsx_bytes(bill_cells(rows)))
        self.assertTrue(any("품명 없이 금액" in e for e in parsed.errors), parsed.errors)

    def test_estimate_layout_is_not_bill(self):
        items = [{"no": 1, "name": "합성 자재", "spec": "EA", "qty": 2, "price": 1500, "amount": 3000}]
        parsed = parse_estimate(xlsx_bytes(sample_cells(items, 3000)))
        self.assertEqual(parsed.layout, "estimate")
        self.assertEqual(parsed.errors, [])
        self.assertEqual(parsed.site_name, "합성현장A")


if __name__ == "__main__":
    unittest.main()
