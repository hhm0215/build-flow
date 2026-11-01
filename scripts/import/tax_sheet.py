"""홈택스 '매출전자세금계산서목록' 엑셀(.xls/.xlsx) 파서. 순수 함수만 둔다(API·파일 쓰기 없음).

양식(머리글 위치·열 순서는 글자로 찾는다):
  상단 요약: '사업자 등록번호 | … | 상호 | …', '총 합계금액 | 값 | 총 공급가액 | 값 | 총 세액 | 값'
  목록 머리글: '작성일자 | 상호 | (대표자명) | 합계금액 | 공급가액 | (세액) | (품목일자) | 품목명'
  세액 열이 없는 해(예전 내보내기)는 세액 = 합계금액 − 공급가액으로 계산한다.

검증:
  - 행의 금액은 소수 2자리·정수 13자리 이내(서버 DECIMAL(15,2)·@Digits와 같은 경계).
  - 합계금액·공급가액·세액이 모두 있으면 합계 = 공급가액 + 세액이어야 한다(아니면 ERROR).
  - 행 공급가액 합계가 시트 요약 '총 공급가액'과 다르면 ERROR. 요약 총 세액·총 합계도 있으면 같은 규칙.
  - 세액이 공급가액의 10%(원 단위 반올림 ±1원)와 다르면 경고만(영세율·면세 가능).
  - 음수 금액(수정 세금계산서)은 API가 받지 않으므로 행을 따로 표시한다(적용 여부는 호출 측이 정함).
"""

from __future__ import annotations

import datetime as dt
import re
from dataclasses import dataclass, field
from decimal import ROUND_HALF_UP, Decimal
from typing import Any, Optional

from estimate_sheet import MAX_AMOUNT, NumberError, SheetFormatError, fits_scale, load_sheet, text, to_decimal

HEADER_SCAN_ROWS = 40
REQUIRED_LABELS = ("작성일자", "상호", "공급가액")
COLUMN_LABELS = {
    "date": {"작성일자"},
    "counterparty": {"상호", "공급받는자상호"},
    "total": {"합계금액"},
    "supply": {"공급가액"},
    "tax": {"세액"},
    "item": {"품목명"},
}
SUMMARY_LABELS = {"총공급가액": "supply", "총세액": "tax", "총합계금액": "total"}
SUMMARY_NAMES = {"supply": "총 공급가액", "tax": "총 세액", "total": "총 합계금액"}
DATE_TEXT = re.compile(r"(\d{4})\s*[.\-/년]?\s*(\d{1,2})\s*[.\-/월]?\s*(\d{1,2})\s*일?")
TAX_RATE = Decimal("0.1")


def label_key(value: Any) -> str:
    return re.sub(r"\s", "", text(value))


@dataclass
class TaxRow:
    row: int  # 엑셀 1-based 행 번호
    issue_date: Optional[dt.date]
    counterparty: str
    supply: Optional[Decimal]
    tax: Optional[Decimal]
    total: Optional[Decimal]
    item_name: str
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    @property
    def negative(self) -> bool:
        return any(v is not None and v < 0 for v in (self.supply, self.tax, self.total))

    @property
    def content_key(self) -> tuple[str, str, str, str, str]:
        """여러 목록 파일(기간이 겹치는 내보내기)에 같은 세금계산서가 들어 있는지 비교하는 키."""
        return (self.issue_date.isoformat() if self.issue_date else "", self.counterparty,
                str(self.supply), str(self.tax), self.item_name)


@dataclass
class ParsedTaxList:
    sheet: str
    header_row: int  # 1-based
    columns: dict[str, int]
    rows: list[TaxRow]
    summary: dict[str, Optional[Decimal]]
    errors: list[str]
    warnings: list[str]

    def sum_of(self, key: str) -> Decimal:
        return sum((getattr(r, key) or Decimal(0) for r in self.rows), Decimal(0))


def find_header(grid: list[list[Any]]) -> tuple[int, dict[str, int]]:
    for r, row in enumerate(grid[:HEADER_SCAN_ROWS]):
        labels = [label_key(v) for v in row]
        columns: dict[str, int] = {}
        for c, label in enumerate(labels):
            for key, names in COLUMN_LABELS.items():
                if label in names and key not in columns:
                    columns[key] = c
        if all(name in labels for name in REQUIRED_LABELS) and "date" in columns:
            return r, columns
    raise SheetFormatError("목록 머리글(작성일자·상호·공급가액)을 찾을 수 없습니다 — 홈택스 매출 세금계산서 목록인지 확인")


def find_summary(grid: list[list[Any]], header_row: int) -> tuple[dict[str, Optional[Decimal]], list[str]]:
    """머리글 위쪽에서 '총 공급가액' 같은 글자 오른쪽 첫 값."""
    summary: dict[str, Optional[Decimal]] = {key: None for key in SUMMARY_NAMES}
    errors: list[str] = []
    for row in grid[:header_row]:
        for c, value in enumerate(row):
            key = SUMMARY_LABELS.get(label_key(value))
            if not key or summary[key] is not None:
                continue
            for right in row[c + 1:]:
                if text(right):
                    try:
                        summary[key] = to_decimal(right)
                    except NumberError:
                        errors.append(f"시트 요약 {SUMMARY_NAMES[key]}을(를) 숫자로 읽을 수 없음")
                    break
    return summary, errors


def parse_date(value: Any) -> Optional[dt.date]:
    if isinstance(value, dt.datetime):
        return value.date()
    if isinstance(value, dt.date):
        return value
    match = DATE_TEXT.fullmatch(text(value))
    if not match:
        return None
    try:
        return dt.date(int(match.group(1)), int(match.group(2)), int(match.group(3)))
    except ValueError:
        return None


def _amount(row: TaxRow, value: Any, label: str) -> Optional[Decimal]:
    try:
        amount = to_decimal(value)
    except NumberError:
        row.errors.append(f"{label}을(를) 숫자로 읽을 수 없음")
        return None
    if amount is None:
        return None
    if not fits_scale(amount):
        row.errors.append(f"{label}이(가) 소수 2자리를 넘음")
    elif abs(amount) > MAX_AMOUNT:
        row.errors.append(f"{label}이(가) 정수 13자리를 넘음")
    return amount


def expected_tax(supply: Decimal) -> Decimal:
    return (supply * TAX_RATE).quantize(Decimal(1), rounding=ROUND_HALF_UP)


def _parse_row(r: int, values: list[Any], columns: dict[str, int]) -> Optional[TaxRow]:
    def at(key: str) -> Any:
        c = columns.get(key)
        return values[c] if c is not None and c < len(values) else None

    if not any(text(v) for v in values):
        return None
    row = TaxRow(row=r + 1, issue_date=parse_date(at("date")), counterparty=text(at("counterparty"))[:200],
                 supply=None, tax=None, total=None, item_name=text(at("item")))
    if row.issue_date is None:
        row.errors.append("작성일자를 날짜로 읽을 수 없음")
    if not row.counterparty:
        row.errors.append("상호가 비어 있음")
    row.supply = _amount(row, at("supply"), "공급가액")
    row.total = _amount(row, at("total"), "합계금액") if "total" in columns else None
    if "tax" in columns:
        row.tax = _amount(row, at("tax"), "세액")
    elif row.total is not None and row.supply is not None:
        row.tax = row.total - row.supply
    if row.supply is None:
        row.errors.append("공급가액이 비어 있음")
    if row.tax is None and not row.errors:
        row.errors.append("세액이 비어 있음")
    if row.errors:
        return row
    if row.total is not None and row.total != row.supply + row.tax:
        row.errors.append("합계금액 ≠ 공급가액 + 세액")
    if row.total is None:
        row.total = row.supply + row.tax
    if abs(row.total) > MAX_AMOUNT:
        row.errors.append("합계금액(공급가액+세액)이 정수 13자리를 넘음")
    if not row.errors and not row.negative and abs(row.tax - expected_tax(row.supply)) > 1:
        row.warnings.append("세액이 공급가액의 10%와 다름(영세율·면세면 무시)")
    return row


def parse_tax_list(data: bytes) -> ParsedTaxList:
    sheet = load_sheet(data)
    grid = sheet.grid
    header_row, columns = find_header(grid)
    if "tax" not in columns and "total" not in columns:
        raise SheetFormatError("세액·합계금액 열이 모두 없어 세액을 알 수 없습니다")
    summary, errors = find_summary(grid, header_row)
    warnings: list[str] = []
    rows = [row for r in range(header_row + 1, len(grid))
            if (row := _parse_row(r, grid[r], columns)) is not None]
    parsed = ParsedTaxList(sheet.title, header_row + 1, columns, rows, summary, errors, warnings)
    if not rows:
        errors.append("데이터 행이 없음")
    if summary["supply"] is None:
        errors.append("시트 요약 '총 공급가액'을 찾을 수 없음 — 합계 검증 불가")
    if not any(r.errors for r in rows):
        for key in ("supply", "tax", "total"):
            expected = summary[key]
            if expected is not None and parsed.sum_of(key) != expected:
                errors.append(f"행 {SUMMARY_NAMES[key][2:]} 합계와 시트 요약 {SUMMARY_NAMES[key]}이(가) 다름")
    return parsed
