#!/usr/bin/env python3
"""견적서 엑셀을 로컬 개발 환경에 현장 단위로 적재한다 (Gateway API 경유).

사용:
  python -I -X utf8 scripts/import/estimates.py <현장폴더 또는 파일>... [--apply | --emit-plan <JSON>]
      [--base-url http://localhost:3000] [--ledger <저장소 밖 경로>]
      [--recursive] [--append-size] [--append-note] [--confirm-latest] [--complete-sites]
      [--site-alias <CSV/JSON>] [--site-from-folder] [--lump-when-empty] [--allow-missing-total]
  python -I -X utf8 scripts/import/estimates.py --record-result <브라우저 결과 JSON> [--ledger ...]

- 기본은 미리보기(dry-run)이며 API를 호출하지 않는다. --apply일 때만 쓴다.
- --emit-plan은 API를 부르지 않고 적용할 요청 목록을 JSON으로 내보낸다. 로그인된 로컬 BuildFlow
  페이지 콘솔에서 browser_apply.js로 적용하고, 그 결과 JSON을 --record-result로 적재 기록에 남긴다.
- 원본은 바이트로 읽기만 한다. DB에 직접 쓰지 않고 Gateway API로만 생성한다.
- 미리보기에 ERROR가 하나라도 있으면 --apply/--emit-plan을 거부한다(강제 옵션 없음).
  "확인 필요"(계산값 없는 수식 합계, 미확정 같은 현장 후보)는 해당 옵션으로 확정해야 진행한다.
- 같은 대상·파일 SHA-256·시트는 적재 기록(ledger)으로 다시 넣지 않는다.
- 토큰은 BUILDFLOW_ACCESS_TOKEN(+선택 BUILDFLOW_REFRESH_TOKEN) 환경변수로만 받고 출력하지 않는다.
"""

from __future__ import annotations

import argparse
import datetime as dt
import difflib
import json
import os
import re
import sys
import unicodedata
from dataclasses import dataclass, field
from decimal import Decimal
from pathlib import Path
from typing import Any, Optional

sys.path.insert(0, str(Path(__file__).resolve().parent))

from estimate_sheet import (  # noqa: E402
    SITE_MISSING_ERROR, ParsedEstimate, SheetFormatError, format_amount, parse_estimate,
)
from import_common import (  # noqa: E402
    DEFAULT_BASE_URL, ApiClient, ApiError, ImportSetupError, Ledger, SourceFile, collect_files,
    default_ledger_path, load_site_aliases, outside_repo, read_source, site_key, validate_base_url,
)

KIND = "estimate"
SITE_STATUS_KIND = "site-status"
SUFFIXES = (".xlsx", ".xls")
MARKER_PREFIX = "로컬 가져오기 sha256:"
DATE_SOURCE_MTIME = "file-mtime"
FOLDER_YEAR = re.compile(r"(?:19|20)\d{2}")
PLAN_FORMAT = "buildflow-import-plan"
RESULT_FORMAT = "buildflow-import-result"
FORMAT_VERSION = 1
COMPLETED = "COMPLETED"
SIMILAR_RATIO = 0.85


def folder_year_of(path: Path) -> Optional[int]:
    """가장 가까운 상위 폴더 중 이름이 연도(예: 2026)인 것."""
    for parent in path.resolve().parents:
        if FOLDER_YEAR.fullmatch(parent.name):
            return int(parent.name)
    return None


FOLDER_DATE = re.compile(r"(\d{4}\s*년\s*)?\d{1,2}\s*월\s*\d{1,2}\s*일|\d{4}[.\-]\d{1,2}[.\-]\d{1,2}|\d{1,2}[.\-]\d{1,2}")


def _folder_name(folder: Path) -> str:
    # 제어·사용자 정의 영역 글자(엑셀/메신저에서 섞여 온  등)는 버린다
    cleaned = "".join(ch for ch in folder.name if unicodedata.category(ch) not in {"Cc", "Cf", "Co", "Cs"})
    return re.sub(r"\s+", " ", cleaned).strip()


def folder_site_candidate(path: Path, client_name: str = "") -> Optional[str]:
    """파일이 든 폴더 이름. 연도 폴더(2026 등)·빈 이름은 후보가 아니다.

    날짜 폴더(예: '거래처/11월 6일/')는 날짜만으로 현장을 가릴 수 없으므로 위 폴더 이름을 앞에 붙인다.
    위 폴더가 '2025년 견적서'처럼 연도가 든 모음 폴더면 견적 수신처(거래처) 이름을 대신 붙인다.
    """
    folder = path.resolve().parent
    name = _folder_name(folder)
    if not name or FOLDER_YEAR.fullmatch(name):
        return None
    if FOLDER_DATE.fullmatch(name):
        upper = _folder_name(folder.parent)
        prefix = upper if upper and not re.search(r"\d{4}", upper) else client_name.strip()
        if prefix:
            name = f"{prefix} {name}"
    return name[:200]


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
    site_name: str = ""  # 적재할 현장명(별칭 적용 후)
    raw_site_name: str = ""  # 셀 또는 폴더에서 읽은 이름(별칭 적용 전)
    site_source: str = "cell"  # cell | folder
    aliased: bool = False
    blockers: list[str] = field(default_factory=list)  # ERROR는 아니지만 옵션 확정 전에는 적용 금지

    @property
    def ok(self) -> bool:
        return not self.errors

    @property
    def pending(self) -> bool:
        return self.ok and self.parsed is not None and not self.ledger_record and not self.duplicate_of

    @property
    def marker(self) -> str:
        return MARKER_PREFIX + self.source.sha256[:16] if self.source else ""


def build_plan(path: Path, ledger: Ledger, base_url: str, args: argparse.Namespace,
               aliases: dict[str, tuple[str, str]]) -> Plan:
    try:
        source = read_source(path)
    except (OSError, ValueError) as exc:
        return Plan(None, path, None, [f"파일을 읽을 수 없음({exc.__class__.__name__})"], [])
    try:
        parsed = parse_estimate(source.data, append_size=args.append_size, append_note=args.append_note,
                                lump_when_empty=args.lump_when_empty, lump_name=f"{path.stem} (합계 1식)")
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

    plan.raw_site_name = parsed.site_name
    if not parsed.site_name:
        candidate = folder_site_candidate(path, parsed.client_name)
        if candidate and args.site_from_folder:
            plan.raw_site_name, plan.site_source = candidate, "folder"
            plan.errors = [e for e in plan.errors if e != SITE_MISSING_ERROR]
            plan.warnings.append(f"현장명 셀이 비어 폴더 이름 '{candidate}'을(를) 현장명으로 사용(--site-from-folder)")
        elif candidate:
            plan.warnings.append(f"현장명 후보: 폴더 이름 '{candidate}' — 쓰려면 --site-from-folder")
    plan.site_name = plan.raw_site_name
    alias = aliases.get(site_key(plan.raw_site_name)) if plan.raw_site_name else None
    if alias:
        plan.aliased = True
        if alias[1] != plan.raw_site_name:
            plan.site_name = alias[1]
            plan.warnings.append(f"현장명 '{plan.raw_site_name}' → '{alias[1]}'(--site-alias)")

    if parsed.total_formula_uncached and not args.allow_missing_total:
        plan.blockers.append("합계 셀 계산값 없음 — 품목 합계로 넣으려면 --allow-missing-total")
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


@dataclass
class SiteReport:
    groups: list[tuple[str, list[str], bool]]  # (키, 서로 다른 원래 이름들, 별칭으로 확정됐는지)
    similar: list[tuple[str, str]]


def analyze_site_names(plans: list[Plan], aliases: dict[str, tuple[str, str]]) -> SiteReport:
    """띄어쓰기·괄호·기호만 다른 현장명 묶음(같은 현장 후보)과, 글자만 조금 다른 이름 쌍을 찾는다."""
    by_key: dict[str, list[str]] = {}
    final_of: dict[str, str] = {}
    for plan in plans:
        if plan.parsed is None or not plan.raw_site_name or plan.duplicate_of:
            continue
        key = site_key(plan.raw_site_name)
        names = by_key.setdefault(key, [])
        if plan.raw_site_name not in names:
            names.append(plan.raw_site_name)
        final_of[key] = plan.site_name
    groups = []
    for key, names in by_key.items():
        if len(names) > 1:
            resolved = key in aliases
            groups.append((key, sorted(names), resolved))
            if not resolved:
                for plan in plans:
                    if plan.raw_site_name in names and plan.parsed is not None:
                        plan.blockers.append("같은 현장 후보(띄어쓰기·기호만 다름) 미확정 — --site-alias로 적재 이름 확정")
    clients_of: dict[str, set[str]] = {}
    for plan in plans:
        if plan.parsed is not None and plan.site_name and not plan.duplicate_of and plan.parsed.client_name:
            clients_of.setdefault(plan.site_name, set()).add(plan.parsed.client_name)
    for plan in plans:
        if plan.parsed is not None and len(clients_of.get(plan.site_name, ())) > 1:
            plan.blockers.append("같은 현장명에 거래처가 여럿 — 다른 현장이면 --site-alias로 이름을 나눠 확정")
    similar = []
    keys = sorted(k for k in by_key if len(k) >= 3)
    for i, left in enumerate(keys):
        for right in keys[i + 1:]:
            if final_of[left] == final_of[right]:
                continue
            if difflib.SequenceMatcher(None, left, right).ratio() >= SIMILAR_RATIO:
                similar.append((by_key[left][0], by_key[right][0]))
    return SiteReport(groups, similar)


def print_preview(plans: list[Plan], report: SiteReport) -> None:
    print(f"미리보기: 견적 파일 {len(plans)}개")
    for index, plan in enumerate(plans, start=1):
        parsed = plan.parsed
        if parsed is None:
            print(f"[{index}] {plan.path.name} | 읽기 실패")
        else:
            state = "ERROR" if plan.errors else ("확인 필요" if plan.blockers else "OK")
            if plan.ledger_record:
                state = f"이미 적재(견적 #{plan.ledger_record.get('estimateId')})"
            elif plan.duplicate_of:
                state = f"건너뜀([{plan.duplicate_of}]과 같은 내용)"
            site = plan.site_name or "-"
            if plan.site_source == "folder":
                site += " (출처: 폴더 이름)"
            print(
                f"[{index}] {plan.path.name} | 거래처 {parsed.client_name or '-'} | 현장 {site}"
                f" | 품목 {len(parsed.items)} | 계산 {format_amount(parsed.computed_total)}"
                f" / 셀 {format_amount(parsed.cell_total)} | 견적일 {plan.estimate_date} ({plan.date_source})"
                f" | 표기연도 {parsed.sheet_year or '-'}"
                f" | {state}"
            )
        for warning in plan.warnings:
            print(f"    경고: {warning}")
        for blocker in plan.blockers:
            print(f"    확인 필요: {blocker}")
        for error in plan.errors:
            print(f"    ERROR: {error}")
    for _, names, resolved in report.groups:
        label = "같은 현장 후보(별칭으로 확정됨)" if resolved else "같은 현장 후보(미확정)"
        print(f"{label}: " + " / ".join(f"'{n}'" for n in names))
    for left, right in report.similar:
        print(f"비슷한 현장명(자동으로 묶지 않음, 같은 현장이면 --site-alias): '{left}' / '{right}'")


def estimate_body(plan: Plan) -> dict[str, Any]:
    parsed = plan.parsed
    assert parsed is not None and plan.source is not None
    return {
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


def estimate_payload(plan: Plan, site_id: int) -> dict[str, Any]:
    return {"siteId": site_id, **estimate_body(plan)}


def new_site_memo(sheet_year: Optional[int]) -> Optional[str]:
    return f"견적서 표기 연도 {sheet_year}" if sheet_year else None


def ledger_fields(plan: Plan) -> dict[str, Any]:
    parsed = plan.parsed
    assert parsed is not None and plan.source is not None
    return {
        "kind": KIND, "sha256": plan.source.sha256, "sheet": parsed.sheet, "rows": parsed.row_span,
        "itemCount": len(parsed.items), "total": str(parsed.computed_total), "fileName": plan.path.name,
        "estimateDate": plan.estimate_date.isoformat(), "dateSource": plan.date_source,
        "sheetYear": parsed.sheet_year,
    }


def latest_per_site(plans: list[Plan]) -> dict[str, int]:
    """현장별 가장 최근(견적일 → 수정일 → 파일명) 파일의 1-based 번호."""
    latest: dict[str, tuple[Any, int]] = {}
    for index, plan in enumerate(plans, start=1):
        if plan.duplicate_of or plan.parsed is None or plan.source is None:
            continue
        rank = (plan.estimate_date, plan.source.mtime_date, plan.path.name)
        if plan.site_name not in latest or rank > latest[plan.site_name][0]:
            latest[plan.site_name] = (rank, index)
    return {name: index for name, (_, index) in latest.items()}


class Resolver:
    """이름으로 기존 거래처·현장을 재사용하고, 없으면 만든다."""

    def __init__(self, client: ApiClient):
        self.api = client
        self.clients = list(client.request("GET", "/api/v1/clients") or [])
        self.sites = list(client.request("GET", "/api/v1/sites") or [])

    @staticmethod
    def _match(rows: list[dict[str, Any]], field_name: str, name: str, label: str) -> Optional[dict[str, Any]]:
        matches = [row for row in rows if str(row.get(field_name) or "").strip() == name]
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
                # 날짜 폴더처럼 흔한 이름은 다른 거래처의 다른 현장일 수 있어 합치지 않는다
                raise ApiError(409, f"기존 현장 '{name}'의 거래처가 견적 수신처와 다름 — --site-alias로 이름을 나눠 다시 실행")
            return int(found["id"]), False, note
        key = site_key(name)
        near = [str(row.get("siteName")) for row in self.sites if site_key(str(row.get("siteName") or "")) == key]
        if near:
            raise ApiError(409, f"기존 현장 '{near[0]}'과 띄어쓰기·기호만 다름 — --site-alias로 적재 이름을 확정한 뒤 다시 실행")
        # 시작·종료일은 추정하지 않고 비워 둔다. 시트 표기 연도는 새 현장 메모에만 남긴다.
        body: dict[str, Any] = {"siteName": name[:200]}
        memo = new_site_memo(sheet_year)
        if memo:
            body["memo"] = memo
        if client_id is not None:
            body["clientId"] = client_id
        created = self.api.request("POST", "/api/v1/sites", body)
        self.sites.append(created)
        return int(created["id"]), True, None


def complete_sites(api: ApiClient, ledger: Ledger, site_ids: list[int]) -> None:
    for site_id in dict.fromkeys(site_ids):
        current = api.request("GET", f"/api/v1/sites/{site_id}") or {}
        status = current.get("status")
        if current.get("archivedAt"):
            print(f"완료 처리 건너뜀: 현장 #{site_id}는 보관됨")
            continue
        if status == COMPLETED:
            continue
        if status == "BIDDING":
            print(f"완료 처리 건너뜀: 현장 #{site_id}는 수주 전(BIDDING) — 수주 처리 후 화면에서 변경")
            continue
        changed = api.request("PATCH", f"/api/v1/sites/{site_id}/status", {"status": COMPLETED}) or {}
        ledger.append({"kind": SITE_STATUS_KIND, "baseUrl": api.base_url, "siteId": site_id,
                       "from": status, "status": changed.get("status", COMPLETED)})
        print(f"현장 #{site_id} 상태: {status} → {changed.get('status', COMPLETED)}")


def apply_plans(plans: list[Plan], api: ApiClient, ledger: Ledger, confirm_latest: bool,
                mark_completed: bool = False) -> int:
    resolver = Resolver(api)
    estimate_ids: dict[int, int] = {}
    site_ids: list[int] = []
    created_count = 0
    for index, plan in enumerate(plans, start=1):
        parsed = plan.parsed
        assert parsed is not None and plan.source is not None
        if plan.duplicate_of:
            print(f"[{index}] 건너뜀: [{plan.duplicate_of}]과 같은 내용")
            continue
        if plan.ledger_record:
            estimate_ids[index] = int(plan.ledger_record["estimateId"])
            if plan.ledger_record.get("siteId") is not None:
                site_ids.append(int(plan.ledger_record["siteId"]))
            print(f"[{index}] 건너뜀: 이미 적재됨(견적 #{plan.ledger_record['estimateId']})")
            continue
        client_id, client_new = resolver.client_id(parsed.client_name)
        site_id, site_new, note = resolver.site_id(plan.site_name, client_id, parsed.sheet_year)
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
            **ledger_fields(plan), "baseUrl": api.base_url, "estimateId": int(estimate["id"]), "siteId": site_id,
            "clientId": client_id, "createdSite": site_new, "createdClient": client_new,
            "status": estimate.get("status"),
        }
        ledger.append(record)
        plan.ledger_record = record
        estimate_ids[index] = int(estimate["id"])
        site_ids.append(site_id)
        print(
            f"[{index}] {action}: 견적 #{estimate['id']} 합계 {format_amount(Decimal(str(estimate.get('totalAmount', '0'))))}"
            f" | 현장 #{site_id}({'신규' if site_new else '기존'})"
            f" | 거래처 {('#' + str(client_id) + ('(신규)' if client_new else '(기존)')) if client_id else '없음'}"
        )
        if note:
            print(f"    경고: {note}")

    if confirm_latest:
        for site_name, index in latest_per_site(plans).items():
            estimate_id = estimate_ids[index]
            current = api.request("GET", f"/api/v1/estimates/{estimate_id}") or {}
            if current.get("status") != "DRAFT":
                print(f"확정 건너뜀: 견적 #{estimate_id}는 이미 {current.get('status')}")
                continue
            confirmed = api.request("PATCH", f"/api/v1/estimates/{estimate_id}/confirm")
            ledger.append({**plans[index - 1].ledger_record, "status": confirmed.get("status")})
            print(f"확정: 견적 #{estimate_id}(현장의 가장 최근 파일)")
    if mark_completed:
        complete_sites(api, ledger, site_ids)
    return created_count


# ─────────────────────────────────────────
# 브라우저 세션 적재: 계획 내보내기·결과 기록
# ─────────────────────────────────────────

def build_browser_plan(plans: list[Plan], base_url: str, confirm_latest: bool, mark_completed: bool) -> dict[str, Any]:
    """browser_apply.js가 같은 규칙으로 실행할 요청 목록. 토큰·원본 경로는 넣지 않는다."""
    latest = latest_per_site(plans) if confirm_latest else {}
    confirm_indexes = set(latest.values())
    entries, known = [], []
    for index, plan in enumerate(plans, start=1):
        parsed = plan.parsed
        if parsed is None or plan.source is None or plan.duplicate_of:
            continue
        if plan.ledger_record:
            if index in confirm_indexes or mark_completed:
                known.append({"entry": index, "siteName": plan.site_name,
                              "estimateId": plan.ledger_record.get("estimateId"),
                              "siteId": plan.ledger_record.get("siteId"), "confirm": index in confirm_indexes})
            continue
        entries.append({
            "entry": index, **ledger_fields(plan), "marker": plan.marker,
            "clientName": parsed.client_name, "siteName": plan.site_name, "siteNameSource": plan.site_source,
            "siteAliased": plan.aliased, "newSiteMemo": new_site_memo(parsed.sheet_year),
            "estimate": estimate_body(plan), "confirm": index in confirm_indexes,
        })
    return {
        "format": PLAN_FORMAT, "version": FORMAT_VERSION, "baseUrl": base_url,
        "generatedAt": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
        "options": {"confirmLatest": confirm_latest, "completeSites": mark_completed},
        "entries": entries, "known": known,
    }


def write_json_outside_repo(path: Path, data: Any, label: str) -> Path:
    resolved = outside_repo(path, label)
    resolved.parent.mkdir(parents=True, exist_ok=True)
    tmp = resolved.with_name(resolved.name + ".tmp")
    with open(tmp, "w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    os.replace(tmp, resolved)
    return resolved


RESULT_ENTRY_FIELDS = ("entry", "sha256", "sheet", "estimateId", "siteId")


def record_result(path: Path, ledger: Ledger) -> int:
    """browser_apply.js 결과 JSON을 적재 기록에 남긴다. 같은 결과를 다시 넣어도 줄이 늘지 않는다."""
    resolved = outside_repo(path, "브라우저 결과 JSON(--record-result)")
    try:
        result = json.loads(resolved.read_text(encoding="utf-8-sig"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ImportSetupError(f"결과 JSON을 읽을 수 없습니다({exc.__class__.__name__})") from None
    import taxes  # 세금계산서 결과(buildflowApplyTaxPlan)도 같은 명령으로 기록한다

    if isinstance(result, dict) and result.get("format") == taxes.RESULT_FORMAT:
        return taxes.record_tax_result(result, ledger)
    if not isinstance(result, dict) or result.get("format") != RESULT_FORMAT or result.get("version") != FORMAT_VERSION:
        raise ImportSetupError("browser_apply.js 결과 형식이 아닙니다(format/version)")
    base_url = validate_base_url(str(result.get("baseUrl") or ""))
    entries = result.get("entries")
    if not isinstance(entries, list):
        raise ImportSetupError("결과 JSON에 entries 목록이 없습니다")
    added = unchanged = 0
    for raw in entries:
        if not isinstance(raw, dict) or any(raw.get(k) in (None, "") for k in RESULT_ENTRY_FIELDS):
            raise ImportSetupError("결과 항목에 entry·sha256·sheet·estimateId·siteId가 필요합니다")
        if not re.fullmatch(r"[0-9a-f]{64}", str(raw["sha256"])):
            raise ImportSetupError("결과 항목의 sha256 형식이 올바르지 않습니다")
        record = {
            "kind": KIND, "baseUrl": base_url, "sha256": raw["sha256"], "sheet": str(raw["sheet"]),
            "rows": raw.get("rows"), "itemCount": raw.get("itemCount"), "total": raw.get("total"),
            "fileName": raw.get("fileName"), "estimateId": int(raw["estimateId"]), "siteId": int(raw["siteId"]),
            "clientId": raw.get("clientId"), "createdSite": bool(raw.get("createdSite")),
            "createdClient": bool(raw.get("createdClient")), "status": raw.get("status"),
            "estimateDate": raw.get("estimateDate"), "dateSource": raw.get("dateSource"),
            "sheetYear": raw.get("sheetYear"), "appliedVia": "browser",
        }
        previous = ledger.find(base_url, KIND, record["sha256"], record["sheet"])
        if previous and previous.get("estimateId") == record["estimateId"] and previous.get("status") == record["status"]:
            unchanged += 1
            continue
        if previous and previous.get("estimateId") != record["estimateId"]:
            print(f"경고: [{raw['entry']}] 기존 기록(견적 #{previous.get('estimateId')})과 다른 견적 #{record['estimateId']} — 새 기록으로 남김")
        ledger.append(record)
        added += 1
    status_added = 0
    for change in result.get("siteStatus") or []:
        if not isinstance(change, dict) or change.get("siteId") is None or not change.get("status"):
            continue
        site_id, status = int(change["siteId"]), str(change["status"])
        if any(r.get("kind") == SITE_STATUS_KIND and r.get("baseUrl") == base_url and r.get("siteId") == site_id
               and r.get("status") == status for r in ledger.records):
            continue
        ledger.append({"kind": SITE_STATUS_KIND, "baseUrl": base_url, "siteId": site_id,
                       "from": change.get("from"), "status": status, "appliedVia": "browser"})
        status_added += 1
    print(f"결과 기록: 견적 {added}건 추가 · {unchanged}건 이미 기록됨 · 현장 상태 {status_added}건. 적재 기록: {ledger.path}")
    if not result.get("ok", False):
        print(f"참고: 브라우저 적용이 중간에 멈췄습니다({str(result.get('error') or '사유 없음')[:300]}). "
              "같은 계획으로 다시 실행하면 표식으로 이미 넣은 견적은 건너뜁니다", file=sys.stderr)
        return 1
    return 0


# ─────────────────────────────────────────
# CLI
# ─────────────────────────────────────────

def parse_args(argv: Optional[list[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="견적서 엑셀을 로컬 개발 환경에 적재(기본 미리보기)")
    parser.add_argument("paths", nargs="*", type=Path, help="현장 폴더 또는 견적 엑셀 파일")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--apply", action="store_true", help="실제로 Gateway API에 생성")
    mode.add_argument("--emit-plan", type=Path, default=None,
                      help="API 호출 없이 브라우저 적용용 요청 목록 JSON을 저장소 밖 경로에 저장")
    mode.add_argument("--record-result", type=Path, default=None,
                      help="browser_apply.js 결과 JSON을 적재 기록에 남김(경로 인자 없이 단독 실행)")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help="로컬 Gateway 주소(기본 프론트 Nginx 경유)")
    parser.add_argument("--ledger", type=Path, default=None, help="적재 기록 JSONL(저장소 밖)")
    parser.add_argument("--recursive", action="store_true", help="하위 폴더까지 찾기")
    parser.add_argument("--append-size", action="store_true", help="품명 뒤에 사이즈를 괄호로 덧붙임")
    parser.add_argument("--append-note", action="store_true", help="품명 뒤에 비고를 괄호로 덧붙임")
    parser.add_argument("--confirm-latest", action="store_true", help="현장별 가장 최근 파일의 견적만 확정")
    parser.add_argument("--complete-sites", action="store_true", help="적재한 현장 상태를 완료(COMPLETED)로 변경")
    parser.add_argument("--site-alias", type=Path, default=None,
                        help="원본 현장명→적재 현장명 CSV(원본,적재) 또는 JSON 객체(저장소 밖)")
    parser.add_argument("--site-from-folder", action="store_true", help="현장명 셀이 비면 폴더 이름을 현장명으로")
    parser.add_argument("--lump-when-empty", action="store_true", help="품목이 없으면 합계금액을 1식 품목으로")
    parser.add_argument("--allow-missing-total", action="store_true",
                        help="합계 셀이 계산값 없는 수식이면 품목 합계만으로 진행")
    return parser.parse_args(argv)


def main(argv: Optional[list[str]] = None, api_factory=ApiClient.from_env) -> int:
    args = parse_args(argv)
    try:
        ledger = Ledger(args.ledger or default_ledger_path())
        if args.record_result:
            if args.paths:
                raise ImportSetupError("--record-result는 견적 경로 없이 단독으로 실행합니다")
            return record_result(args.record_result, ledger)
        if not args.paths:
            raise ImportSetupError("현장 폴더 또는 견적 파일 경로가 필요합니다")
        base_url = validate_base_url(args.base_url)
        aliases = load_site_aliases(args.site_alias) if args.site_alias else {}
        if args.emit_plan:
            outside_repo(args.emit_plan, "계획 JSON(--emit-plan)")
        files, hidden = collect_files(args.paths, SUFFIXES, args.recursive)
    except ImportSetupError as exc:
        print(f"설정 오류: {exc}", file=sys.stderr)
        return 2
    if hidden:
        print(f"숨김·임시 파일 {hidden}개는 건너뜀")
    if not files:
        print("대상 견적 파일이 없습니다")
        return 0
    plans = [build_plan(path, ledger, base_url, args, aliases) for path in files]
    mark_duplicates(plans)
    report = analyze_site_names(plans, aliases)
    print_preview(plans, report)
    error_count = sum(1 for plan in plans if plan.errors)
    blocked = [plan for plan in plans if plan.blockers and not plan.errors and not plan.ledger_record
               and not plan.duplicate_of]
    pending = [plan for plan in plans if plan.pending]
    print(f"요약: OK {len(plans) - error_count - len(blocked)} · 확인 필요 {len(blocked)} · ERROR {error_count}"
          f" · 새로 적재할 파일 {len(pending)}")
    if not args.apply and not args.emit_plan:
        print("미리보기만 했습니다(API 호출 없음). 적재하려면 --apply 또는 --emit-plan")
        return 1 if error_count else 0
    if error_count:
        print("ERROR가 있어 적용을 거부합니다. 원본 사본을 고치거나 해당 파일을 빼고 다시 실행하세요", file=sys.stderr)
        return 1
    if blocked:
        print("확인 필요 항목이 있어 적용을 거부합니다. 안내된 옵션으로 확정한 뒤 다시 실행하세요", file=sys.stderr)
        return 1
    if args.emit_plan:
        browser_plan = build_browser_plan(plans, base_url, args.confirm_latest, args.complete_sites)
        written = write_json_outside_repo(args.emit_plan, browser_plan, "계획 JSON(--emit-plan)")
        print(f"계획 저장: 견적 {len(browser_plan['entries'])}건 · 기존 적재 참조 {len(browser_plan['known'])}건 → {written}")
        print("로그인된 BuildFlow 페이지 콘솔에서 scripts/import/browser_apply.js로 적용하고, 결과 JSON을 --record-result로 기록하세요")
        return 0
    try:
        api = api_factory(base_url)
        created = apply_plans(plans, api, ledger, args.confirm_latest, args.complete_sites)
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
