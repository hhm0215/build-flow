#!/usr/bin/env python3
"""홈택스 매출 전자세금계산서 목록 엑셀을 로컬 개발 환경에 세금계산서(매출)로 적재한다.

사용:
  python -I -X utf8 scripts/import/taxes.py <목록 파일 또는 폴더>... [--emit-plan <JSON>]
      [--base-url http://localhost:3000] [--ledger <저장소 밖 경로>] [--recursive] [--skip-negative]
  python -I -X utf8 scripts/import/taxes.py --record-result <브라우저 결과 JSON> [--ledger ...]

- 기본은 미리보기(dry-run)이며 API를 호출하지 않는다. 직접 --apply는 없다.
- --emit-plan은 API를 부르지 않고 행 정보(작성일자·상호·공급가액·세액·품목명·출처 표식)만 담은 계획 JSON을
  저장소 밖 경로에 쓴다. 현장 연결은 계획 시점에 정할 수 없으므로, 로그인된 로컬 BuildFlow 페이지에서
  browser_apply.js의 buildflowApplyTaxPlan이 현재 거래처·현장 목록으로 보수적으로 정한다
  (상호 정규화 일치 거래처 1곳 + 그 거래처 활성 현장 중 현장명이 품목명에 든 현장이 정확히 1개일 때만).
- 원본은 바이트로 읽기만 한다. 미리보기에 ERROR가 하나라도 있으면 계획을 거부한다(강제 옵션 없음).
- 같은 (대상, 파일 SHA-256, 행)은 적재 기록(ledger, kind 'tax')으로 다시 넣지 않고, 메모의 출처 표식으로
  브라우저 쪽에서도 다시 만들지 않는다. 입금 확인은 하지 않는다. 매입 세금계산서는 다루지 않는다.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional

sys.path.insert(0, str(Path(__file__).resolve().parent))

from estimate_sheet import SheetFormatError, format_amount  # noqa: E402
from import_common import (  # noqa: E402
    DEFAULT_BASE_URL, ImportSetupError, Ledger, SourceFile, collect_files, default_ledger_path, outside_repo,
    read_source, validate_base_url,
)
from tax_sheet import ParsedTaxList, TaxRow, parse_tax_list  # noqa: E402

KIND = "tax"
SUFFIXES = (".xlsx", ".xls")
PLAN_FORMAT = "buildflow-tax-import-plan"
RESULT_FORMAT = "buildflow-tax-import-result"
FORMAT_VERSION = 1
MARKER_PREFIX = "[가져오기 매출 sha256:"
NEGATIVE_BLOCKER = "수정(음수) 세금계산서 — API가 음수 금액을 받지 않음. 빼고 진행하려면 --skip-negative(화면에서 원 건과 상계)"


def marker_of(sha256: str, row: int) -> str:
    """메모에 남기는 출처 표식. 닫는 괄호까지 포함해 '행:1'이 '행:12'에 걸리지 않게 한다."""
    return f"{MARKER_PREFIX}{sha256[:16]} 행:{row}]"


@dataclass
class FilePlan:
    source: Optional[SourceFile]
    path: Path
    parsed: Optional[ParsedTaxList]
    errors: list[str]
    duplicate_of: Optional[int] = None  # 같은 바이트 사본
    row_state: dict[int, str] = field(default_factory=dict)  # 행 → 건너뜀 사유(이미 적재·다른 파일과 같은 내용)
    blockers: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.errors and not any(r.errors for r in self.rows)

    @property
    def rows(self) -> list[TaxRow]:
        return self.parsed.rows if self.parsed else []

    def pending_rows(self, skip_negative: bool) -> list[TaxRow]:
        if self.duplicate_of or not self.parsed:
            return []
        return [r for r in self.rows if r.row not in self.row_state and not (skip_negative and r.negative)]


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
    for row in parsed.rows:
        record = find_ledger(ledger, base_url, source.sha256, row.row)
        if record:
            plan.row_state[row.row] = f"이미 적재(세금계산서 #{record.get('taxInvoiceId')})"
    return plan


def mark_duplicates(plans: list[FilePlan]) -> None:
    """같은 바이트 사본은 처음 것만, 기간이 겹친 내보내기의 같은 내용 행은 앞 파일 것만 쓴다.

    같은 파일 안의 똑같은 행 여러 개는 서로 다른 세금계산서일 수 있어 그대로 둔다. 다른 파일과는 개수로 비교한다
    (앞 파일들 중 가장 많이 나온 개수만큼은 중복으로 본다).
    """
    seen_sha: dict[str, int] = {}
    seen_count: dict[tuple, tuple[int, int]] = {}  # 내용 키 → (앞 파일 최대 개수, 그 파일 번호)
    for index, plan in enumerate(plans, start=1):
        if plan.source is None or plan.parsed is None:
            continue
        if plan.source.sha256 in seen_sha:
            plan.duplicate_of = seen_sha[plan.source.sha256]
            continue
        seen_sha[plan.source.sha256] = index
        counts: dict[tuple, int] = {}
        for row in plan.rows:
            key = row.content_key
            counts[key] = counts.get(key, 0) + 1
            previous, origin = seen_count.get(key, (0, 0))
            if counts[key] <= previous and row.row not in plan.row_state:
                plan.row_state[row.row] = f"[{origin}] 파일과 같은 내용(기간 겹침)"
        for key, count in counts.items():
            if count > seen_count.get(key, (0, 0))[0]:
                seen_count[key] = (count, index)


def apply_blockers(plans: list[FilePlan], skip_negative: bool) -> None:
    for plan in plans:
        negatives = [r for r in plan.pending_rows(False) if r.negative]
        if negatives and not skip_negative:
            plan.blockers.append(f"{NEGATIVE_BLOCKER} — {len(negatives)}행")


def print_preview(plans: list[FilePlan], skip_negative: bool) -> None:
    print(f"미리보기: 매출 세금계산서 목록 파일 {len(plans)}개")
    for index, plan in enumerate(plans, start=1):
        parsed = plan.parsed
        if parsed is None:
            print(f"[{index}] {plan.path.name} | 읽기 실패")
        else:
            state = "ERROR" if not plan.ok else ("확인 필요" if plan.blockers else "OK")
            if plan.duplicate_of:
                state = f"건너뜀([{plan.duplicate_of}]과 같은 내용)"
            summary = parsed.summary.get("supply")
            print(
                f"[{index}] {plan.path.name} | 행 {len(parsed.rows)} | 공급가액 합계 {format_amount(parsed.sum_of('supply'))}"
                f" / 시트 요약 {format_amount(summary)} | 세액 합계 {format_amount(parsed.sum_of('tax'))}"
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
        for blocker in plan.blockers:
            print(f"    확인 필요: {blocker}")
        for error in plan.errors:
            print(f"    ERROR: {error}")
    print("현장 연결은 적용 시(browser_apply.js) 현재 거래처·현장 목록으로 정합니다: 상호가 정규화 일치하는 거래처 1곳,"
          " 그 거래처 활성 현장 중 현장명이 품목명에 든 현장이 정확히 1개일 때만 연결하고, 나머지는 사유와 함께 건너뜁니다")


def invoice_body(plan: FilePlan, row: TaxRow) -> dict[str, Any]:
    assert plan.source is not None and row.issue_date is not None
    memo = marker_of(plan.source.sha256, row.row)
    if row.item_name:
        memo += f" 품목:{row.item_name[:500]}"
    return {
        "type": "SALES",
        "supplyAmount": format(row.supply, "f"),
        "taxAmount": format(row.tax, "f"),
        "counterparty": row.counterparty[:200],
        "issueDate": row.issue_date.isoformat(),
        "memo": memo,
    }


def build_browser_plan(plans: list[FilePlan], base_url: str, skip_negative: bool) -> dict[str, Any]:
    """browser_apply.js(buildflowApplyTaxPlan)가 실행할 행 목록. 토큰·원본 경로는 넣지 않는다."""
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
                "invoice": invoice_body(plan, row),
            })
    return {
        "format": PLAN_FORMAT, "version": FORMAT_VERSION, "kind": KIND, "baseUrl": base_url,
        "generatedAt": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
        "entries": entries,
    }


def write_json_outside_repo(path: Path, data: Any, label: str) -> Path:
    from estimates import write_json_outside_repo as write  # 같은 저장 규칙 재사용

    return write(path, data, label)


RESULT_ENTRY_FIELDS = ("entry", "sha256", "row", "taxInvoiceId", "siteId")


def record_tax_result(result: dict[str, Any], ledger: Ledger) -> int:
    """buildflowApplyTaxPlan 결과를 적재 기록(kind 'tax')에 남긴다. 같은 결과를 다시 넣어도 줄이 늘지 않는다."""
    if result.get("format") != RESULT_FORMAT or result.get("version") != FORMAT_VERSION:
        raise ImportSetupError("buildflowApplyTaxPlan 결과 형식이 아닙니다(format/version)")
    base_url = validate_base_url(str(result.get("baseUrl") or ""))
    entries = result.get("entries")
    if not isinstance(entries, list):
        raise ImportSetupError("결과 JSON에 entries 목록이 없습니다")
    records = []
    for raw in entries:
        if not isinstance(raw, dict) or any(raw.get(k) in (None, "") for k in RESULT_ENTRY_FIELDS):
            raise ImportSetupError("결과 항목에 entry·sha256·row·taxInvoiceId·siteId가 필요합니다")
        if not re.fullmatch(r"[0-9a-f]{64}", str(raw["sha256"])):
            raise ImportSetupError("결과 항목의 sha256 형식이 올바르지 않습니다")
        records.append({
            "kind": KIND, "baseUrl": base_url, "sha256": raw["sha256"], "sheet": str(raw.get("sheet") or ""),
            "row": int(raw["row"]), "fileName": raw.get("fileName"), "issueDate": raw.get("issueDate"),
            "supplyAmount": raw.get("supplyAmount"), "taxAmount": raw.get("taxAmount"),
            "taxInvoiceId": int(raw["taxInvoiceId"]), "siteId": int(raw["siteId"]),
            "clientId": raw.get("clientId"), "action": raw.get("action"), "appliedVia": "browser",
        })
    added = unchanged = 0
    for record in records:
        previous = find_ledger(ledger, base_url, record["sha256"], record["row"])
        if previous and previous.get("taxInvoiceId") == record["taxInvoiceId"]:
            unchanged += 1
            continue
        if previous:
            print(f"경고: {record['row']}행 기존 기록(세금계산서 #{previous.get('taxInvoiceId')})과 다른"
                  f" #{record['taxInvoiceId']} — 새 기록으로 남김")
        ledger.append(record)
        added += 1
    skipped = result.get("skipped") or []
    reasons: dict[str, int] = {}
    for item in skipped:
        if isinstance(item, dict):
            reason = str(item.get("reason") or "사유 없음")
            reasons[reason] = reasons.get(reason, 0) + 1
    print(f"결과 기록: 세금계산서 {added}건 추가 · {unchanged}건 이미 기록됨. 적재 기록: {ledger.path}")
    if reasons:
        print("연결 못 해 건너뜀(기록 안 함, 거래처·현장 정리 후 다시 계획): "
              + " · ".join(f"{reason} {count}행" for reason, count in reasons.items()))
    if not result.get("ok", False):
        print(f"참고: 브라우저 적용이 중간에 멈췄습니다({str(result.get('error') or '사유 없음')[:300]}). "
              "같은 계획으로 다시 실행하면 표식으로 이미 넣은 세금계산서는 건너뜁니다", file=sys.stderr)
        return 1
    return 0


def read_result(path: Path) -> dict[str, Any]:
    resolved = outside_repo(path, "브라우저 결과 JSON(--record-result)")
    try:
        result = json.loads(resolved.read_text(encoding="utf-8-sig"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ImportSetupError(f"결과 JSON을 읽을 수 없습니다({exc.__class__.__name__})") from None
    if not isinstance(result, dict):
        raise ImportSetupError("결과 JSON 형식이 올바르지 않습니다")
    return result


def parse_args(argv: Optional[list[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="홈택스 매출 세금계산서 목록을 로컬 개발 환경에 적재(기본 미리보기)")
    parser.add_argument("paths", nargs="*", type=Path, help="매출 세금계산서 목록 파일 또는 폴더")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--emit-plan", type=Path, default=None,
                      help="API 호출 없이 브라우저 적용용 계획 JSON을 저장소 밖 경로에 저장")
    mode.add_argument("--record-result", type=Path, default=None,
                      help="buildflowApplyTaxPlan 결과 JSON을 적재 기록에 남김(경로 인자 없이 단독 실행)")
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
            return record_tax_result(read_result(args.record_result), ledger)
        if not args.paths:
            raise ImportSetupError("매출 세금계산서 목록 파일 또는 폴더 경로가 필요합니다")
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
    print(f"계획 저장: 세금계산서 행 {len(browser_plan['entries'])}건 → {written}")
    print("로그인된 BuildFlow 페이지 콘솔에서 browser_apply.js를 붙여 넣고 buildflowApplyTaxPlan(계획)으로 적용한 뒤,"
          " 결과 JSON을 --record-result로 기록하세요")
    return 0


if __name__ == "__main__":
    sys.exit(main())
