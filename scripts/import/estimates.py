#!/usr/bin/env python3
"""견적서 엑셀을 로컬 개발 환경에 현장 단위로 적재한다 (Gateway API 경유).

사용:
  python -I -X utf8 scripts/import/estimates.py <현장폴더 또는 파일>... [--apply]
      [--base-url http://localhost:3000] [--ledger <저장소 밖 경로>]
      [--recursive] [--append-size] [--append-note] [--confirm-latest]

- 기본은 미리보기(dry-run)이며 API를 호출하지 않는다. --apply일 때만 쓴다.
- 원본은 바이트로 읽기만 한다. DB에 직접 쓰지 않고 Gateway API로만 생성한다.
- 미리보기에 ERROR가 하나라도 있으면 --apply를 거부한다(강제 옵션 없음).
- 같은 대상·파일 SHA-256·시트는 적재 기록(ledger)으로 다시 넣지 않는다.
- 토큰은 BUILDFLOW_ACCESS_TOKEN(+선택 BUILDFLOW_REFRESH_TOKEN) 환경변수로만 받고 출력하지 않는다.
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path
from typing import Any, Optional

sys.path.insert(0, str(Path(__file__).resolve().parent))

from estimate_sheet import ParsedEstimate, SheetFormatError, format_amount, parse_estimate  # noqa: E402
from import_common import (  # noqa: E402
    DEFAULT_BASE_URL, ApiClient, ApiError, ImportSetupError, Ledger, SourceFile, collect_files,
    default_ledger_path, read_source, validate_base_url,
)

KIND = "estimate"
SUFFIXES = (".xlsx", ".xls")
MARKER_PREFIX = "로컬 가져오기 sha256:"
DATE_SOURCE_MTIME = "file-mtime"
FOLDER_YEAR = re.compile(r"(?:19|20)\d{2}")


def folder_year_of(path: Path) -> Optional[int]:
    """가장 가까운 상위 폴더 중 이름이 연도(예: 2026)인 것."""
    for parent in path.resolve().parents:
        if FOLDER_YEAR.fullmatch(parent.name):
            return int(parent.name)
    return None


@dataclass
class Plan:
    source: Optional[SourceFile]
    path: Path
    parsed: Optional[ParsedEstimate]
    errors: list[str]
    warnings: list[str]
    estimate_date: Optional[Any] = None
    date_source: str = ""
    ledger_record: Optional[dict[str, Any]] = None
    duplicate_of: Optional[int] = None

    @property
    def ok(self) -> bool:
        return not self.errors

    @property
    def marker(self) -> str:
        return MARKER_PREFIX + self.source.sha256[:16] if self.source else ""


def build_plan(path: Path, ledger: Ledger, base_url: str, append_size: bool, append_note: bool) -> Plan:
    try:
        source = read_source(path)
    except (OSError, ValueError) as exc:
        return Plan(None, path, None, [f"파일을 읽을 수 없음({exc.__class__.__name__})"], [])
    try:
        parsed = parse_estimate(source.data, append_size=append_size, append_note=append_note)
    except SheetFormatError as exc:
        return Plan(source, path, None, [str(exc)], [])
    plan = Plan(source, path, parsed, list(parsed.errors), list(parsed.warnings))
    if parsed.estimate_date:
        plan.estimate_date, plan.date_source = parsed.estimate_date, parsed.date_source
    else:
        plan.estimate_date, plan.date_source = source.mtime_date, DATE_SOURCE_MTIME
    folder_year = folder_year_of(path)
    if folder_year and parsed.sheet_year and folder_year != parsed.sheet_year:
        plan.warnings.append(f"폴더 연도({folder_year})와 시트 표기 연도({parsed.sheet_year})가 다름")
    plan.ledger_record = ledger.find(base_url, KIND, source.sha256, parsed.sheet)
    return plan


def mark_duplicates(plans: list[Plan]) -> None:
    """같은 바이트의 사본(USB 백업 폴더 등)은 처음 것만 적재한다."""
    seen: dict[tuple[str, str], int] = {}
    for index, plan in enumerate(plans, start=1):
        if plan.source is None or plan.parsed is None:
            continue
        key = (plan.source.sha256, plan.parsed.sheet)
        if key in seen:
            plan.duplicate_of = seen[key]
        else:
            seen[key] = index


def print_preview(plans: list[Plan]) -> None:
    print(f"미리보기: 견적 파일 {len(plans)}개")
    for index, plan in enumerate(plans, start=1):
        parsed = plan.parsed
        if parsed is None:
            print(f"[{index}] {plan.path.name} | 읽기 실패")
        else:
            state = "ERROR" if plan.errors else "OK"
            if plan.ledger_record:
                state = f"이미 적재(견적 #{plan.ledger_record.get('estimateId')})"
            elif plan.duplicate_of:
                state = f"건너뜀([{plan.duplicate_of}]과 같은 내용)"
            print(
                f"[{index}] {plan.path.name} | 거래처 {parsed.client_name or '-'} | 현장 {parsed.site_name or '-'}"
                f" | 품목 {len(parsed.items)} | 계산 {format_amount(parsed.computed_total)}"
                f" / 셀 {format_amount(parsed.cell_total)} | 견적일 {plan.estimate_date} ({plan.date_source})"
                f" | 표기연도 {parsed.sheet_year or '-'}"
                f" | {state}"
            )
        for warning in plan.warnings:
            print(f"    경고: {warning}")
        for error in plan.errors:
            print(f"    ERROR: {error}")


def estimate_payload(plan: Plan, site_id: int) -> dict[str, Any]:
    parsed = plan.parsed
    assert parsed is not None and plan.source is not None
    return {
        "siteId": site_id,
        "title": plan.path.stem[:200],
        "estimateDate": plan.estimate_date.isoformat(),
        "items": [
            {
                "itemName": item.name,
                "unit": item.unit,
                "quantity": format(item.quantity, "f"),
                "unitPrice": format(item.unit_price, "f"),
            }
            for item in parsed.items
        ],
        "memo": f"{plan.marker} 시트:{parsed.sheet[:100]} 행:{parsed.row_span}",
        "kind": "ORIGINAL",
    }


class Resolver:
    """이름으로 기존 거래처·현장을 재사용하고, 없으면 만든다."""

    def __init__(self, client: ApiClient):
        self.api = client
        self.clients = list(client.request("GET", "/api/v1/clients") or [])
        self.sites = list(client.request("GET", "/api/v1/sites") or [])

    @staticmethod
    def _match(rows: list[dict[str, Any]], field: str, name: str, label: str) -> Optional[dict[str, Any]]:
        matches = [row for row in rows if str(row.get(field) or "").strip() == name]
        active = [row for row in matches if not row.get("archivedAt")]
        if len(active) > 1:
            raise ApiError(409, f"같은 이름의 {label}이(가) {len(active)}개라 자동으로 고를 수 없음 — 이름을 정리 후 다시 실행")
        if active:
            return active[0]
        if matches:
            raise ApiError(409, f"보관된 {label}과 이름이 같음 — 복원하거나 이름을 바꾼 뒤 다시 실행")
        return None

    def client_id(self, name: str) -> tuple[Optional[int], bool]:
        if not name:
            return None, False
        found = self._match(self.clients, "companyName", name, "거래처")
        if found:
            return int(found["id"]), False
        created = self.api.request("POST", "/api/v1/clients", {"companyName": name[:200]})
        self.clients.append(created)
        return int(created["id"]), True

    def site_id(self, name: str, client_id: Optional[int],
                sheet_year: Optional[int] = None) -> tuple[int, bool, Optional[str]]:
        found = self._match(self.sites, "siteName", name, "현장")
        if found:
            linked = (found.get("client") or {}).get("id")
            note = None
            if client_id is not None and linked is not None and int(linked) != client_id:
                note = "기존 현장의 거래처가 견적 수신처와 다름(현장은 그대로 둠)"
            return int(found["id"]), False, note
        # 시작·종료일은 추정하지 않고 비워 둔다. 시트 표기 연도는 새 현장 메모에만 남긴다.
        body: dict[str, Any] = {"siteName": name[:200]}
        if sheet_year:
            body["memo"] = f"견적서 표기 연도 {sheet_year}"
        if client_id is not None:
            body["clientId"] = client_id
        created = self.api.request("POST", "/api/v1/sites", body)
        self.sites.append(created)
        return int(created["id"]), True, None


def apply_plans(plans: list[Plan], api: ApiClient, ledger: Ledger, confirm_latest: bool) -> int:
    resolver = Resolver(api)
    estimate_ids: dict[int, int] = {}
    created_count = 0
    for index, plan in enumerate(plans, start=1):
        parsed = plan.parsed
        assert parsed is not None and plan.source is not None
        if plan.duplicate_of:
            print(f"[{index}] 건너뜀: [{plan.duplicate_of}]과 같은 내용")
            continue
        if plan.ledger_record:
            estimate_ids[index] = int(plan.ledger_record["estimateId"])
            print(f"[{index}] 건너뜀: 이미 적재됨(견적 #{plan.ledger_record['estimateId']})")
            continue
        client_id, client_new = resolver.client_id(parsed.client_name)
        site_id, site_new, note = resolver.site_id(parsed.site_name, client_id, parsed.sheet_year)
        existing = [
            row for row in (api.request("GET", f"/api/v1/estimates?siteId={site_id}") or [])
            if plan.marker in str(row.get("memo") or "")
        ]
        if existing:
            estimate = existing[0]
            action = "기존 견적 연결(기록 복구)"
        else:
            estimate = api.request("POST", "/api/v1/estimates", estimate_payload(plan, site_id))
            action = "견적 초안 생성"
            created_count += 1
        record = {
            "kind": KIND, "baseUrl": api.base_url, "sha256": plan.source.sha256, "sheet": parsed.sheet,
            "rows": parsed.row_span, "itemCount": len(parsed.items), "total": str(parsed.computed_total),
            "fileName": plan.path.name, "estimateId": int(estimate["id"]), "siteId": site_id,
            "clientId": client_id, "createdSite": site_new, "createdClient": client_new,
            "status": estimate.get("status"), "estimateDate": plan.estimate_date.isoformat(),
            "dateSource": plan.date_source, "sheetYear": parsed.sheet_year,
        }
        ledger.append(record)
        plan.ledger_record = record
        estimate_ids[index] = int(estimate["id"])
        print(
            f"[{index}] {action}: 견적 #{estimate['id']} 합계 {format_amount(Decimal(str(estimate.get('totalAmount', '0'))))}"
            f" | 현장 #{site_id}({'신규' if site_new else '기존'})"
            f" | 거래처 {('#' + str(client_id) + ('(신규)' if client_new else '(기존)')) if client_id else '없음'}"
        )
        if note:
            print(f"    경고: {note}")

    if confirm_latest:
        latest: dict[str, tuple[Any, int]] = {}
        for index, plan in enumerate(plans, start=1):
            if plan.duplicate_of:
                continue
            key = plan.parsed.site_name
            rank = (plan.estimate_date, plan.source.mtime_date, plan.path.name)
            if key not in latest or rank > latest[key][0]:
                latest[key] = (rank, index)
        for site_name, (_, index) in latest.items():
            estimate_id = estimate_ids[index]
            current = api.request("GET", f"/api/v1/estimates/{estimate_id}") or {}
            if current.get("status") != "DRAFT":
                print(f"확정 건너뜀: 견적 #{estimate_id}는 이미 {current.get('status')}")
                continue
            confirmed = api.request("PATCH", f"/api/v1/estimates/{estimate_id}/confirm")
            ledger.append({**plans[index - 1].ledger_record, "status": confirmed.get("status")})
            print(f"확정: 견적 #{estimate_id}(현장의 가장 최근 파일)")
    return created_count


def parse_args(argv: Optional[list[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="견적서 엑셀을 로컬 개발 환경에 적재(기본 미리보기)")
    parser.add_argument("paths", nargs="+", type=Path, help="현장 폴더 또는 견적 엑셀 파일")
    parser.add_argument("--apply", action="store_true", help="실제로 Gateway API에 생성")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help="로컬 Gateway 주소(기본 프론트 Nginx 경유)")
    parser.add_argument("--ledger", type=Path, default=None, help="적재 기록 JSONL(저장소 밖)")
    parser.add_argument("--recursive", action="store_true", help="하위 폴더까지 찾기")
    parser.add_argument("--append-size", action="store_true", help="품명 뒤에 사이즈를 괄호로 덧붙임")
    parser.add_argument("--append-note", action="store_true", help="품명 뒤에 비고를 괄호로 덧붙임")
    parser.add_argument("--confirm-latest", action="store_true", help="현장별 가장 최근 파일의 견적만 확정")
    return parser.parse_args(argv)


def main(argv: Optional[list[str]] = None, api_factory=ApiClient.from_env) -> int:
    args = parse_args(argv)
    try:
        base_url = validate_base_url(args.base_url)
        ledger = Ledger(args.ledger or default_ledger_path())
        files, hidden = collect_files(args.paths, SUFFIXES, args.recursive)
    except ImportSetupError as exc:
        print(f"설정 오류: {exc}", file=sys.stderr)
        return 2
    if hidden:
        print(f"숨김·임시 파일 {hidden}개는 건너뜀")
    if not files:
        print("대상 견적 파일이 없습니다")
        return 0
    plans = [build_plan(path, ledger, base_url, args.append_size, args.append_note) for path in files]
    mark_duplicates(plans)
    print_preview(plans)
    error_count = sum(1 for plan in plans if plan.errors)
    pending = [plan for plan in plans if plan.ok and not plan.ledger_record and not plan.duplicate_of]
    print(f"요약: OK {len(plans) - error_count} · ERROR {error_count} · 새로 적재할 파일 {len(pending)}")
    if not args.apply:
        print("미리보기만 했습니다(API 호출 없음). 적재하려면 --apply")
        return 1 if error_count else 0
    if error_count:
        print("ERROR가 있어 --apply를 거부합니다. 원본 사본을 고치거나 해당 파일을 빼고 다시 실행하세요", file=sys.stderr)
        return 1
    try:
        api = api_factory(base_url)
        created = apply_plans(plans, api, ledger, args.confirm_latest)
    except ImportSetupError as exc:
        print(f"설정 오류: {exc}", file=sys.stderr)
        return 2
    except ApiError as exc:
        print(f"적재 중단: {exc}. 앞서 생성된 항목은 적재 기록에 남아 다시 실행해도 중복되지 않습니다", file=sys.stderr)
        return 1
    print(f"완료: 새 견적 {created}개. 적재 기록: {ledger.path}")
    if getattr(api, "rotated", False):
        print("참고: 토큰을 회전했습니다. 환경변수의 리프레시 토큰은 더 이상 유효하지 않을 수 있습니다")
    return 0


if __name__ == "__main__":
    sys.exit(main())
