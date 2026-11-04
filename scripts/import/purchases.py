#!/usr/bin/env python3
"""홈택스 매입 전자세금계산서 목록 엑셀을 로컬 개발 환경에 '현장 미배정 매입'으로 적재한다.

사용:
  python -I -X utf8 scripts/import/purchases.py <목록 파일 또는 폴더>... [--emit-plan <JSON>]
      [--base-url http://localhost:3000] [--ledger <저장소 밖 경로>] [--recursive] [--skip-negative]
  python -I -X utf8 scripts/import/purchases.py --record-result <브라우저 결과 JSON> [--ledger ...]

- 양식은 매출 목록과 같다(tax_sheet.py 재사용): '작성일자 | 상호 | 합계금액 | 공급가액 | (세액) | 품목명'.
- 매입 1건 = 세금계산서 1행: 품목명 = 세금계산서 품목명(200자), 수량 1, 단가 = 공급가액, 공급업체 = 상호,
  매입일 = 작성일자, 메모 = 출처 표식 `[가져오기 매입 sha256:<16자> 행:<n>]`. siteId는 보내지 않는다(미배정).
  가족이 화면에서 현장을 배정한다(PATCH /api/v1/purchases/{id}/site — 이 도구는 배정하지 않음).
- 매입 API에는 세액 필드가 없다. 금액은 공급가액 기준이며 세액은 미리보기·계획 정보에만 남기고 본문 어디에도
  넣지 않는다. 매입 세금계산서(tax-service)는 만들지 않는다(현장 필수라 범위 밖).
- 세무 수수료·전기요금 같은 비공사 항목도 그대로 미배정으로 올린다. 분류 힌트는 미리보기·계획·결과에만 둔다.
- 기본은 미리보기(dry-run)이며 API를 호출하지 않는다. 직접 --apply는 없다. 미리보기에 ERROR가 하나라도 있으면
  계획을 거부한다(강제 옵션 없음). 원본은 바이트로 읽기만 한다.
- 같은 (대상, 파일 SHA-256, 행)은 적재 기록(ledger, kind 'purchase')으로 다시 넣지 않고, 메모의 출처 표식으로
  브라우저 쪽(browser_apply.js buildflowApplyPurchasePlan)에서도 다시 만들지 않는다.
"""

from __future__ import annotations

import argparse
import datetime as dt
import re
import sys
from decimal import Decimal
from pathlib import Path
from typing import Any, Optional

sys.path.insert(0, str(Path(__file__).resolve().parent))

from estimate_sheet import SheetFormatError, format_amount  # noqa: E402
from import_common import (  # noqa: E402
    DEFAULT_BASE_URL, ImportSetupError, Ledger, collect_files, default_ledger_path, outside_repo, read_source,
    validate_base_url,
)
from tax_sheet import TaxRow, parse_tax_list  # noqa: E402
from taxes import FilePlan, mark_duplicates, read_result, write_json_outside_repo  # noqa: E402

KIND = "purchase"
SUFFIXES = (".xlsx", ".xls")
PLAN_FORMAT = "buildflow-purchase-import-plan"
RESULT_FORMAT = "buildflow-purchase-import-result"
FORMAT_VERSION = 1
MARKER_PREFIX = "[가져오기 매입 sha256:"
NEGATIVE_BLOCKER = "수정(음수) 세금계산서 — 매입 단가는 0 이상만 받음. 빼고 진행하려면 --skip-negative(화면에서 원 건과 상계)"
# PurchaseCreateRequest 경계: itemName @Size(200), supplier @Size(200), unitPrice @Digits(integer=10, fraction=2)
ITEM_NAME_MAX = 200
SUPPLIER_MAX = 200
UNIT_PRICE_MAX = Decimal("9999999999.99")
MEMO_ITEM_MAX = 2000
NO_ITEM_NAME = "(품목명 없음)"
AMOUNT_BASIS = "매입 금액은 공급가액 기준(매입 API에 세액 필드 없음 — 세액은 저장하지 않음)"
# 결과에만 표시하는 비공사 추정 힌트(적재 여부·본문에는 영향 없음). 정규화 없이 품목명·상호에 든 글자로 본다
NON_CONSTRUCTION_HINTS = ("세무", "기장", "수수료", "전기요금", "전력", "통신", "인터넷", "전화", "휴대폰", "보험료",
                          "임대료", "렌탈", "렌트", "리스", "주유", "유류", "식대", "수도요금", "가스요금", "관리비",
                          "광고", "카드")


def marker_of(sha256: str, row: int) -> str:
    """메모에 남기는 출처 표식. 닫는 괄호까지 포함해 '행:1'이 '행:12'에 걸리지 않게 한다."""
    return f"{MARKER_PREFIX}{sha256[:16]} 행:{row}]"


def hint_of(row: TaxRow) -> Optional[str]:
    for word in NON_CONSTRUCTION_HINTS:
        if word in row.item_name or word in row.counterparty:
            return f"비공사 추정({word})"
    return None


def check_purchase_row(row: TaxRow) -> None:
    """세금계산서 행 검증(tax_sheet)에 매입 DTO 경계를 더한다. 음수 행은 차단 규칙이 따로 다룬다."""
    if row.errors or row.supply is None:
        return
    if abs(row.supply) > UNIT_PRICE_MAX:
        row.errors.append("공급가액이 매입 단가 한도(정수 10자리)를 넘음")
    if row.supply == 0 and not row.negative:
        row.warnings.append("공급가액이 0")
    if not row.item_name:
        row.warnings.append(f"품목명이 비어 있어 '{NO_ITEM_NAME}'으로 넣음")
    elif len(row.item_name) > ITEM_NAME_MAX:
        row.warnings.append(f"품목명이 {ITEM_NAME_MAX}자를 넘어 앞부분만 품목명에 넣고 전체는 메모에 남김")


def find_ledger(ledger: Ledger, base_url: str, sha256: str, row: int) -> Optional[dict[str, Any]]:
    match = None
    for record in ledger.records:
        if (record.get("kind") == KIND and record.get("baseUrl") == base_url and record.get("sha256") == sha256
                and record.get("row") == row):
            match = record
    return match


def build_file_plan(path: Path, ledger: Ledger, base_url: str) -> FilePlan:
    try:
        source = read_source(path)
    except (OSError, ValueError) as exc:
        return FilePlan(None, path, None, [f"파일을 읽을 수 없음({exc.__class__.__name__})"])
    try:
        parsed = parse_tax_list(source.data)
    except SheetFormatError as exc:
        return FilePlan(source, path, None, [str(exc)])
    plan = FilePlan(source, path, parsed, list(parsed.errors))
    if "counterparty" in parsed.repeated:
        plan.errors.append("머리글에 '상호' 열이 둘 이상 — 공급자 상호 열을 정할 수 없음(홈택스 목록 양식 확인)")
    for row in parsed.rows:
        check_purchase_row(row)
        record = find_ledger(ledger, base_url, source.sha256, row.row)
        if record:
            plan.row_state[row.row] = f"이미 적재(매입 #{record.get('purchaseId')})"
    return plan


def apply_blockers(plans: list[FilePlan], skip_negative: bool) -> None:
    for plan in plans:
        negatives = [r for r in plan.pending_rows(False) if r.negative]
        if negatives and not skip_negative:
            plan.blockers.append(f"{NEGATIVE_BLOCKER} — {len(negatives)}행")


def print_preview(plans: list[FilePlan], skip_negative: bool) -> None:
    print(f"미리보기: 매입 세금계산서 목록 파일 {len(plans)}개 → 현장 미배정 매입")
    hints: dict[str, int] = {}
    for index, plan in enumerate(plans, start=1):
        parsed = plan.parsed
        if parsed is None:
            print(f"[{index}] {plan.path.name} | 읽기 실패")
        else:
            state = "ERROR" if not plan.ok else ("확인 필요" if plan.blockers else "OK")
            if plan.duplicate_of:
                state = f"건너뜀([{plan.duplicate_of}]과 같은 내용)"
            print(
                f"[{index}] {plan.path.name} | 행 {len(parsed.rows)} | 공급가액 합계 {format_amount(parsed.sum_of('supply'))}"
                f" / 시트 요약 {format_amount(parsed.summary.get('supply'))}"
                f" | 세액 합계(저장 안 함) {format_amount(parsed.sum_of('tax'))}"
                f" | 새로 넣을 행 {len(plan.pending_rows(skip_negative))} | {state}"
            )
            skipped: dict[str, int] = {}
            for reason in plan.row_state.values():
                label = reason.split("(")[0] if reason.startswith("이미") else "다른 파일과 같은 내용"
                skipped[label] = skipped.get(label, 0) + 1
            for label, count in skipped.items():
                print(f"    건너뜀: {label} {count}행")
            if skip_negative:
                negatives = sum(1 for r in plan.pending_rows(False) if r.negative)
                if negatives:
                    print(f"    건너뜀: 수정(음수) 세금계산서 {negatives}행(--skip-negative)")
            for row in parsed.rows:
                for warning in row.warnings:
                    print(f"    경고: {row.row}행 {warning}")
                for error in row.errors:
                    print(f"    ERROR: {row.row}행 {error}")
            if not plan.duplicate_of:
                for row in plan.pending_rows(skip_negative):
                    hint = hint_of(row)
                    if hint:
                        hints[hint] = hints.get(hint, 0) + 1
        for blocker in plan.blockers:
            print(f"    확인 필요: {blocker}")
        for error in plan.errors:
            print(f"    ERROR: {error}")
    if hints:
        print("분류 힌트(참고만, 모두 미배정으로 올림): " + " · ".join(f"{k} {v}행" for k, v in hints.items()))
    print(AMOUNT_BASIS)
    print("현장은 배정하지 않습니다(미배정 매입). 가족이 매입 화면에서 현장을 배정합니다")


def purchase_body(plan: FilePlan, row: TaxRow) -> dict[str, Any]:
    """PurchaseCreateRequest 본문. siteId는 넣지 않는다(미배정). 금액은 공급가액만."""
    assert plan.source is not None and row.issue_date is not None and row.supply is not None
    memo = marker_of(plan.source.sha256, row.row)
    item_name = row.item_name or NO_ITEM_NAME
    if len(item_name) > ITEM_NAME_MAX:
        memo += f" 품목 전체:{item_name[:MEMO_ITEM_MAX]}"
    return {
        "itemName": item_name[:ITEM_NAME_MAX],
        "quantity": 1,
        "unitPrice": format(row.supply, "f"),
        "supplier": row.counterparty[:SUPPLIER_MAX],
        "purchaseDate": row.issue_date.isoformat(),
        "memo": memo,
    }


def build_browser_plan(plans: list[FilePlan], base_url: str, skip_negative: bool) -> dict[str, Any]:
    """browser_apply.js(buildflowApplyPurchasePlan)가 실행할 행 목록. 토큰·원본 경로는 넣지 않는다."""
    entries = []
    for index, plan in enumerate(plans, start=1):
        if plan.source is None or plan.parsed is None:
            continue
        for row in plan.pending_rows(skip_negative):
            entries.append({
                "entry": f"{index}:{row.row}", "file": index, "sha256": plan.source.sha256,
                "sheet": plan.parsed.sheet, "row": row.row, "fileName": plan.path.name,
                "marker": marker_of(plan.source.sha256, row.row), "issueDate": row.issue_date.isoformat(),
                "counterparty": row.counterparty, "itemName": row.item_name,
                "supplyAmount": format(row.supply, "f"), "taxAmount": format(row.tax, "f"),
                "hint": hint_of(row),
                "purchase": purchase_body(plan, row),
            })
    return {
        "format": PLAN_FORMAT, "version": FORMAT_VERSION, "kind": KIND, "baseUrl": base_url,
        "amountBasis": "supply",
        "generatedAt": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
        "entries": entries,
    }


RESULT_ENTRY_FIELDS = ("entry", "sha256", "row", "purchaseId")


def record_purchase_result(result: dict[str, Any], ledger: Ledger) -> int:
    """buildflowApplyPurchasePlan 결과를 적재 기록(kind 'purchase')에 남긴다. 다시 넣어도 줄이 늘지 않는다."""
    if result.get("format") != RESULT_FORMAT or result.get("version") != FORMAT_VERSION:
        raise ImportSetupError("buildflowApplyPurchasePlan 결과 형식이 아닙니다(format/version)")
    base_url = validate_base_url(str(result.get("baseUrl") or ""))
    entries = result.get("entries")
    if not isinstance(entries, list):
        raise ImportSetupError("결과 JSON에 entries 목록이 없습니다")
    records = []
    for raw in entries:
        if not isinstance(raw, dict) or any(raw.get(k) in (None, "") for k in RESULT_ENTRY_FIELDS):
            raise ImportSetupError("결과 항목에 entry·sha256·row·purchaseId가 필요합니다")
        if not re.fullmatch(r"[0-9a-f]{64}", str(raw["sha256"])):
            raise ImportSetupError("결과 항목의 sha256 형식이 올바르지 않습니다")
        records.append({
            "kind": KIND, "baseUrl": base_url, "sha256": raw["sha256"], "sheet": str(raw.get("sheet") or ""),
            "row": int(raw["row"]), "fileName": raw.get("fileName"), "issueDate": raw.get("issueDate"),
            "supplyAmount": raw.get("supplyAmount"), "taxAmount": raw.get("taxAmount"),
            "amountBasis": "supply", "purchaseId": int(raw["purchaseId"]),
            "siteId": None if raw.get("siteId") in (None, "") else int(raw["siteId"]),
            "action": raw.get("action"), "hint": raw.get("hint"), "appliedVia": "browser",
        })
    added = unchanged = 0
    for record in records:
        previous = find_ledger(ledger, base_url, record["sha256"], record["row"])
        if previous and previous.get("purchaseId") == record["purchaseId"]:
            unchanged += 1
            continue
        if previous:
            print(f"경고: {record['row']}행 기존 기록(매입 #{previous.get('purchaseId')})과 다른"
                  f" #{record['purchaseId']} — 새 기록으로 남김")
        ledger.append(record)
        added += 1
    reasons: dict[str, int] = {}
    for item in result.get("skipped") or []:
        if isinstance(item, dict):
            reason = str(item.get("reason") or "사유 없음")
            reasons[reason] = reasons.get(reason, 0) + 1
    print(f"결과 기록: 매입 {added}건 추가 · {unchanged}건 이미 기록됨. 적재 기록: {ledger.path}")
    print(AMOUNT_BASIS)
    if reasons:
        print("건너뜀(기록 안 함): " + " · ".join(f"{reason} {count}행" for reason, count in reasons.items()))
    if not result.get("ok", False):
        print(f"참고: 브라우저 적용이 중간에 멈췄습니다({str(result.get('error') or '사유 없음')[:300]}). "
              "같은 계획으로 다시 실행하면 표식으로 이미 넣은 매입은 건너뜁니다", file=sys.stderr)
        return 1
    return 0


def parse_args(argv: Optional[list[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="홈택스 매입 세금계산서 목록을 현장 미배정 매입으로 적재(기본 미리보기)")
    parser.add_argument("paths", nargs="*", type=Path, help="매입 세금계산서 목록 파일 또는 폴더")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--emit-plan", type=Path, default=None,
                      help="API 호출 없이 브라우저 적용용 계획 JSON을 저장소 밖 경로에 저장")
    mode.add_argument("--record-result", type=Path, default=None,
                      help="buildflowApplyPurchasePlan 결과 JSON을 적재 기록에 남김(경로 인자 없이 단독 실행)")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help="로컬 Gateway 주소(기본 프론트 Nginx 경유)")
    parser.add_argument("--ledger", type=Path, default=None, help="적재 기록 JSONL(저장소 밖)")
    parser.add_argument("--recursive", action="store_true", help="하위 폴더까지 찾기")
    parser.add_argument("--skip-negative", action="store_true", help="수정(음수) 세금계산서 행은 빼고 진행")
    return parser.parse_args(argv)


def main(argv: Optional[list[str]] = None) -> int:
    args = parse_args(argv)
    try:
        ledger = Ledger(args.ledger or default_ledger_path())
        if args.record_result:
            if args.paths:
                raise ImportSetupError("--record-result는 목록 경로 없이 단독으로 실행합니다")
            return record_purchase_result(read_result(args.record_result), ledger)
        if not args.paths:
            raise ImportSetupError("매입 세금계산서 목록 파일 또는 폴더 경로가 필요합니다")
        base_url = validate_base_url(args.base_url)
        if args.emit_plan:
            outside_repo(args.emit_plan, "계획 JSON(--emit-plan)")
        files, hidden = collect_files(args.paths, SUFFIXES, args.recursive)
    except ImportSetupError as exc:
        print(f"설정 오류: {exc}", file=sys.stderr)
        return 2
    if hidden:
        print(f"숨김·임시 파일 {hidden}개는 건너뜀")
    if not files:
        print("대상 목록 파일이 없습니다")
        return 0
    plans = [build_file_plan(path, ledger, base_url) for path in files]
    mark_duplicates(plans)
    apply_blockers(plans, args.skip_negative)
    print_preview(plans, args.skip_negative)
    error_files = [p for p in plans if not p.ok and not p.duplicate_of]
    error_rows = sum(1 for p in plans if not p.duplicate_of for r in p.rows if r.errors)
    blocked = [p for p in plans if p.blockers and p.ok and not p.duplicate_of]
    pending = sum(len(p.pending_rows(args.skip_negative)) for p in plans)
    print(f"요약: 파일 {len(plans)} · 행 {sum(len(p.rows) for p in plans if not p.duplicate_of)}"
          f" · ERROR 파일 {len(error_files)}(행 {error_rows}) · 확인 필요 파일 {len(blocked)} · 새로 넣을 행 {pending}")
    if not args.emit_plan:
        print("미리보기만 했습니다(API 호출 없음). 적재하려면 --emit-plan 후 browser_apply.js")
        return 1 if error_files else 0
    if error_files:
        print("ERROR가 있어 계획을 거부합니다. 원본 사본을 고치거나 해당 파일을 빼고 다시 실행하세요", file=sys.stderr)
        return 1
    if blocked:
        print("확인 필요 항목이 있어 계획을 거부합니다. 안내된 옵션으로 확정한 뒤 다시 실행하세요", file=sys.stderr)
        return 1
    browser_plan = build_browser_plan(plans, base_url, args.skip_negative)
    written = write_json_outside_repo(args.emit_plan, browser_plan, "계획 JSON(--emit-plan)")
    print(f"계획 저장: 미배정 매입 행 {len(browser_plan['entries'])}건 → {written}")
    print("로그인된 BuildFlow 페이지 콘솔에서 browser_apply.js를 붙여 넣고 buildflowApplyPurchasePlan(계획)으로 적용한 뒤,"
          " 결과 JSON을 --record-result로 기록하세요(현장 배정은 화면에서)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
