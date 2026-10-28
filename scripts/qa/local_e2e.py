#!/usr/bin/env python3
"""BuildFlow 로컬 풀 Docker 주간 기능 실측 (Gateway 경유).

사용: python -I -X utf8 scripts/qa/local_e2e.py [--base-url http://127.0.0.1:8080]

- 대상은 로컬 개발 호스트(127.0.0.1/localhost/::1)만 허용한다. VPS·공개 도메인은 거부한다.
- 테스트 관리자는 실행 환경마다 무작위 비밀번호로 만들고 `.local/qa-admin.env`(gitignore)에만 저장한다.
  auth-service의 AdminBootstrapRunner(표준입력 JSON 1줄, 비웹 실행)를 `docker exec -i buildflow-auth`로 호출한다.
  계정이 이미 있는 스택이면 부트스트랩이 거부되므로, 대화형 터미널에서 기존 로컬 관리자 값을 입력받는다(저장하지 않음).
- 합성 데이터는 `[QA-주간-<타임스탬프>]` 접두사로 남기고 삭제하지 않는다(시나리오 안의 정책 검증용 삭제 제외).
- 표준 라이브러리만 사용한다. 비밀번호·토큰은 출력하지 않는다.
"""

from __future__ import annotations

import argparse
import datetime as dt
import getpass
import ipaddress
import json
import os
import re
import secrets
import string
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path
from typing import Any, Callable, Iterable, Optional

DEFAULT_BASE_URL = "http://127.0.0.1:8080"
ALLOWED_HOSTS = {"localhost", "127.0.0.1", "::1"}
REPO_ROOT = Path(__file__).resolve().parents[2]
CREDENTIAL_FILE = REPO_ROOT / ".local" / "qa-admin.env"
AUTH_CONTAINER = "buildflow-auth"
BOOTSTRAP_COMMAND = [
    "docker", "exec", "-i", AUTH_CONTAINER,
    "java", "-jar", "/app/app.jar",
    "--spring.main.web-application-type=none",
    "--eureka.client.enabled=false",
    "--app.admin-bootstrap.enabled=true",
]
LOGIN_ID_PATTERN = re.compile(r"[A-Za-z0-9._-]{3,50}")
CONVERGE_TIMEOUT_SECONDS = 30.0
MISSING_ID = 9_000_000_000_000


class SetupError(Exception):
    """대상·자격 준비 실패. 종료 코드 2."""


# ─────────────────────────────────────────
# 대상 호스트 검사
# ─────────────────────────────────────────

def check_local_target(url: str) -> str:
    """로컬 개발 호스트의 Gateway 원점만 허용하고 정규화한 URL을 돌려준다."""
    parsed = urllib.parse.urlsplit(url.strip())
    if parsed.scheme not in ("http", "https"):
        raise SetupError("대상 URL은 http(s)여야 합니다.")
    if parsed.username is not None or parsed.password is not None:
        raise SetupError("대상 URL에 사용자 정보를 넣을 수 없습니다.")
    host = (parsed.hostname or "").lower()
    if host not in ALLOWED_HOSTS:
        raise SetupError(f"로컬 개발 호스트만 허용합니다(127.0.0.1/localhost/::1): {host or '(없음)'}")
    if host != "localhost" and not ipaddress.ip_address(host).is_loopback:
        raise SetupError("루프백 주소가 아닙니다.")
    if parsed.path not in ("", "/") or parsed.query or parsed.fragment:
        raise SetupError("대상 URL은 Gateway 원점(경로·쿼리 없음)이어야 합니다.")
    try:
        port = parsed.port
    except ValueError as exc:
        raise SetupError("포트가 올바르지 않습니다.") from exc
    netloc = f"[{host}]" if ":" in host else host
    if port is not None:
        netloc = f"{netloc}:{port}"
    return f"{parsed.scheme}://{netloc}"


# ─────────────────────────────────────────
# 로컬 자격 파일
# ─────────────────────────────────────────

@dataclass(frozen=True)
class Credentials:
    login_id: str
    password: str = field(repr=False)


def generate_credentials() -> Credentials:
    suffix = secrets.token_hex(4)
    alphabet = string.ascii_letters + string.digits
    password = "".join(secrets.choice(alphabet) for _ in range(32))
    return Credentials(login_id=f"qa-admin-{suffix}", password=password)


def parse_credential_file(text: str) -> Credentials:
    values: dict[str, str] = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    login_id = values.get("BUILDFLOW_QA_LOGIN_ID", "")
    password = values.get("BUILDFLOW_QA_PASSWORD", "")
    if not LOGIN_ID_PATTERN.fullmatch(login_id) or len(password) < 9:
        raise SetupError("로컬 자격 파일 형식이 올바르지 않습니다.")
    return Credentials(login_id, password)


def format_credential_file(creds: Credentials) -> str:
    return (
        "# BuildFlow 로컬 QA 관리자 — 커밋 금지(.gitignore), 로컬 스택 전용\n"
        f"BUILDFLOW_QA_LOGIN_ID={creds.login_id}\n"
        f"BUILDFLOW_QA_PASSWORD={creds.password}\n"
    )


def write_credential_file(path: Path, creds: Credentials) -> None:
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(format_credential_file(creds))


def bootstrap_payload(creds: Credentials) -> bytes:
    return json.dumps(
        {"loginId": creds.login_id, "name": "QA 자동화", "password": creds.password},
        ensure_ascii=False,
    ).encode("utf-8") + b"\n"


def run_bootstrap(creds: Credentials, runner: Callable[..., Any] = subprocess.run) -> bool:
    """AdminBootstrapRunner에 JSON 1줄을 표준입력으로 넘긴다. 출력은 비밀번호 노출 방지를 위해 버린다."""
    try:
        result = runner(BOOTSTRAP_COMMAND, input=bootstrap_payload(creds),
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                        cwd=REPO_ROOT, check=False, timeout=180)
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return False
    return result.returncode == 0


def obtain_credentials(path: Path, allow_bootstrap: bool,
                       runner: Callable[..., Any] = subprocess.run,
                       prompt: Optional[Callable[[], Credentials]] = None) -> tuple[Credentials, str]:
    if path.is_file():
        return parse_credential_file(path.read_text(encoding="utf-8")), "로컬 파일 재사용"
    if allow_bootstrap:
        creds = generate_credentials()
        # 부트스트랩 성공 후 저장 실패로 비밀번호를 잃지 않도록 먼저 저장하고, 실패하면 지운다.
        write_credential_file(path, creds)
        if run_bootstrap(creds, runner):
            return creds, "새 QA 관리자 부트스트랩"
        path.unlink(missing_ok=True)
    if prompt is not None:
        return prompt(), "기존 로컬 관리자 입력(저장 안 함)"
    raise SetupError(
        "QA 관리자를 준비하지 못했습니다. 스택 기동 상태를 확인하세요. 이미 관리자가 있는 스택이면"
        " 대화형 터미널에서 다시 실행해 기존 로컬 관리자 값을 입력하세요.")


def interactive_prompt() -> Credentials:
    if not sys.stdin.isatty():
        raise SetupError("관리자가 이미 있는 스택입니다. 대화형 터미널에서 실행하세요.")
    print("부트스트랩 불가(이미 관리자 존재 가능). 로컬 스택의 기존 관리자 값을 입력하세요. 저장하지 않습니다.")
    login_id = input("관리자 아이디: ").strip()
    password = getpass.getpass("비밀번호(표시 안 함): ")
    if not LOGIN_ID_PATTERN.fullmatch(login_id) or not password:
        raise SetupError("입력 형식이 올바르지 않습니다.")
    return Credentials(login_id, password)


# ─────────────────────────────────────────
# HTTP (Gateway 경유)
# ─────────────────────────────────────────

Transport = Callable[[str, str, dict, Optional[bytes]], tuple[int, bytes]]


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):  # noqa: D401 - 리다이렉트로 다른 호스트에 토큰이 가지 않게 한다
        return None


def urllib_transport(method: str, url: str, headers: dict, body: Optional[bytes]) -> tuple[int, bytes]:
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect())
    request = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with opener.open(request, timeout=40) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read()
    except (urllib.error.URLError, OSError):
        return 0, "{\"error\": \"연결 실패\"}".encode("utf-8")


@dataclass
class Response:
    status: int
    body: Any

    @property
    def data(self) -> Any:
        return self.body.get("data") if isinstance(self.body, dict) else None

    @property
    def error(self) -> str:
        return str(self.body.get("error", "")) if isinstance(self.body, dict) else ""


def parse_body(raw: bytes) -> Any:
    if not raw:
        return None
    try:
        return json.loads(raw.decode("utf-8"), parse_float=Decimal)
    except (UnicodeDecodeError, json.JSONDecodeError):
        return {"error": "JSON 아님"}


class ApiError(Exception):
    pass


class Api:
    def __init__(self, base_url: str, transport: Transport = urllib_transport):
        self.base_url = base_url
        self.transport = transport
        self.token: Optional[str] = None

    def call(self, method: str, path: str, body: Any = None, auth: bool = True) -> Response:
        headers = {"Accept": "application/json"}
        payload = None
        if body is not None:
            payload = json.dumps(body, ensure_ascii=False, default=str).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if auth and self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        status, raw = self.transport(method, self.base_url + path, headers, payload)
        return Response(status, parse_body(raw))

    def ok(self, method: str, path: str, body: Any = None, expected: Iterable[int] = (200, 201)) -> Any:
        response = self.call(method, path, body)
        if response.status not in tuple(expected):
            raise ApiError(f"{method} {path} → {response.status} {response.error[:120]}")
        return response.data

    def login(self, creds: Credentials) -> None:
        response = self.call("POST", "/api/v1/auth/login",
                             {"loginId": creds.login_id, "password": creds.password}, auth=False)
        data = response.data or {}
        if response.status != 200 or not data.get("accessToken"):
            raise SetupError(f"로그인 실패: HTTP {response.status}")
        if str(data.get("tokenType", "Bearer")) != "Bearer":
            raise SetupError("예상하지 못한 토큰 형식입니다.")
        self.token = data["accessToken"]

    def logout(self) -> None:
        if self.token:
            self.call("POST", "/api/v1/auth/logout")
            self.token = None


# ─────────────────────────────────────────
# 기대값 계산 (순수 함수, 단위 테스트 대상)
# ─────────────────────────────────────────

def dec(value: Any) -> Decimal:
    return Decimal("0") if value is None else Decimal(str(value))


def expected_payment_state(total: Any, payments: Iterable[tuple[Any, bool]]) -> tuple[Decimal, Decimal, str]:
    """(입금액, 취소여부) 목록 → (유효 입금 합계, 잔액, UNPAID/PARTIAL/PAID)."""
    paid = sum((dec(amount) for amount, canceled in payments if not canceled), Decimal("0"))
    remaining = dec(total) - paid
    if paid <= 0:
        status = "UNPAID"
    elif remaining <= 0:
        status = "PAID"
    else:
        status = "PARTIAL"
    return paid, remaining, status


def expected_outstanding(invoices: Iterable[tuple[Any, Any]]) -> dict:
    """(총액, 유효 입금) 매출 계산서 목록 → 미수금 요약."""
    rows = [(dec(t), dec(p)) for t, p in invoices]
    total = sum((t for t, _ in rows), Decimal("0"))
    confirmed = sum((p for _, p in rows), Decimal("0"))
    return {"totalSalesAmount": total, "confirmedAmount": confirmed,
            "outstandingAmount": total - confirmed, "unpaidCount": sum(1 for t, p in rows if t > p)}


def margin_rate(estimate: Any, purchase: Any) -> Decimal:
    est, pur = dec(estimate), dec(purchase)
    if est <= 0:
        return Decimal("0")
    rate = ((est - pur) * 100 / est).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    return max(rate, Decimal("-999.99"))


def expected_profit(estimate: Any, purchase: Any) -> dict:
    est, pur = dec(estimate), dec(purchase)
    return {"totalEstimateAmount": est, "totalPurchaseAmount": pur,
            "margin": est - pur, "marginRate": margin_rate(est, pur)}


def active_estimate_total(estimates: Iterable[dict]) -> Decimal:
    """손익에 반영되는 견적 = CONFIRMED만(DRAFT·SUPERSEDED 제외)."""
    return sum((dec(e.get("totalAmount")) for e in estimates if e.get("status") == "CONFIRMED"), Decimal("0"))


def dashboard_totals_excluding_bidding(stats: dict) -> tuple[Decimal, Decimal]:
    """현장별 목록에서 BIDDING을 뺀 견적·매입 합계(대시보드 전체 합계의 기대값)."""
    est = pur = Decimal("0")
    for row in stats.get("siteProfits") or []:
        if row.get("status") == "BIDDING":
            continue
        est += dec(row.get("estimateAmount"))
        pur += dec(row.get("purchaseAmount"))
    return est, pur


def expected_evidence_summary(purchases: Iterable[tuple[Any, Optional[int]]],
                              invoices: Iterable[tuple[int, Any, Any]]) -> dict:
    """매입 (금액, 연결 계산서 id) / 매입 계산서 (id, 공급가, 합계) → 대조 요약 기대값."""
    invoice_rows = list(invoices)
    linked_by_invoice = {inv_id: Decimal("0") for inv_id, _, _ in invoice_rows}
    linked_count_by_invoice = {inv_id: 0 for inv_id, _, _ in invoice_rows}
    purchase_amount = linked_amount = Decimal("0")
    count = linked = broken = 0
    for amount, evidence_id in purchases:
        count += 1
        purchase_amount += dec(amount)
        if evidence_id is None:
            continue
        if evidence_id not in linked_by_invoice:
            broken += 1
            continue
        linked += 1
        linked_amount += dec(amount)
        linked_by_invoice[evidence_id] += dec(amount)
        linked_count_by_invoice[evidence_id] += 1
    supply = sum((dec(s) for _, s, _ in invoice_rows), Decimal("0"))
    total = sum((dec(t) for _, _, t in invoice_rows), Decimal("0"))
    return {
        "purchaseCount": count, "purchaseAmount": purchase_amount,
        "linkedPurchaseCount": linked, "linkedPurchaseAmount": linked_amount,
        "unlinkedPurchaseCount": count - linked, "unlinkedPurchaseAmount": purchase_amount - linked_amount,
        "brokenLinkCount": broken, "invoiceCount": len(invoice_rows),
        "invoiceSupplyAmount": supply, "invoiceTotalAmount": total,
        "invoiceWithoutPurchaseCount": sum(1 for c in linked_count_by_invoice.values() if c == 0),
        "supplyDifference": supply - linked_amount, "totalDifference": total - linked_amount,
    }


def diff_fields(actual: Optional[dict], expected: dict) -> list[str]:
    """금액은 Decimal 비교(스케일 무시), 나머지는 동등 비교. 다른 필드 목록."""
    actual = actual or {}
    mismatches = []
    for key, want in expected.items():
        got = actual.get(key)
        if isinstance(want, Decimal):
            same = got is not None and dec(got) == want
        else:
            same = got == want
        if not same:
            mismatches.append(f"{key}={got}≠{want}")
    return mismatches


def poll(fetch: Callable[[], Any], done: Callable[[Any], bool], timeout: float = CONVERGE_TIMEOUT_SECONDS,
         interval: float = 1.0, clock: Callable[[], float] = time.monotonic,
         sleep: Callable[[float], None] = time.sleep) -> tuple[bool, Any]:
    """Kafka 수렴 대기. 마지막 값을 함께 돌려준다."""
    deadline = clock() + timeout
    value = fetch()
    while not done(value):
        if clock() >= deadline:
            return False, value
        sleep(interval)
        value = fetch()
    return True, value


# ─────────────────────────────────────────
# 결과 기록
# ─────────────────────────────────────────

@dataclass
class Check:
    scenario: str
    name: str
    ok: bool
    detail: str = ""


class Recorder:
    def __init__(self) -> None:
        self.checks: list[Check] = []

    def check(self, scenario: str, name: str, ok: bool, detail: str = "") -> bool:
        self.checks.append(Check(scenario, name, bool(ok), detail))
        return bool(ok)

    def status(self, scenario: str, name: str, response: Response, expected: int) -> bool:
        return self.check(scenario, name, response.status == expected,
                          f"HTTP {response.status}" + (f" {response.error[:80]}" if response.status != expected else ""))

    def fields(self, scenario: str, name: str, actual: Optional[dict], expected: dict) -> bool:
        mismatches = diff_fields(actual, expected)
        return self.check(scenario, name, not mismatches, "; ".join(mismatches)[:200])

    @property
    def failed(self) -> int:
        return sum(1 for c in self.checks if not c.ok)

    def render(self) -> str:
        lines = ["| 시나리오 | 검사 | 결과 | 상세 |", "|---|---|---|---|"]
        for c in self.checks:
            detail = c.detail.replace("|", "/")
            lines.append(f"| {c.scenario} | {c.name} | {'PASS' if c.ok else 'FAIL'} | {detail} |")
        lines.append(f"\n합계: {len(self.checks)}건, PASS {len(self.checks) - self.failed}, FAIL {self.failed}")
        return "\n".join(lines)


# ─────────────────────────────────────────
# 시나리오
# ─────────────────────────────────────────

def make_prefix(now: Optional[dt.datetime] = None) -> str:
    now = now or dt.datetime.now()
    return f"[QA-주간-{now.strftime('%Y%m%d-%H%M%S')}]"


class Scenarios:
    def __init__(self, api: Api, rec: Recorder, prefix: str, today: Optional[dt.date] = None,
                 poller: Callable[..., tuple[bool, Any]] = poll):
        self.api = api
        self.rec = rec
        self.prefix = prefix
        self.today = (today or dt.date.today()).isoformat()
        self.poll = poller
        self.revision_site_id: Optional[int] = None
        self.revision_expected: Optional[dict] = None

    # 공통 생성 도우미 ────────────────────
    def site(self, label: str, status: Optional[str] = None) -> dict:
        body = {"siteName": f"{self.prefix} {label}", "memo": "로컬 QA 합성 데이터"}
        if status:
            body["status"] = status
        return self.api.ok("POST", "/api/v1/sites", body, expected=(201,))

    def estimate(self, site_id: int, label: str, qty: int, price: int,
                 kind: Optional[str] = None, revision_of: Optional[int] = None) -> dict:
        body = {"siteId": site_id, "title": f"{self.prefix} {label}", "estimateDate": self.today,
                "items": [{"itemName": f"{self.prefix} 항목", "unit": "식",
                           "quantity": qty, "unitPrice": price}]}
        if kind:
            body["kind"] = kind
        if revision_of is not None:
            body["revisionOfId"] = revision_of
        return self.api.ok("POST", "/api/v1/estimates", body, expected=(201,))

    def purchase(self, site_id: int, label: str, qty: int, price: int) -> dict:
        return self.api.ok("POST", "/api/v1/purchases", {
            "siteId": site_id, "itemName": f"{self.prefix} {label}", "quantity": qty, "unitPrice": price,
            "supplier": f"{self.prefix} 공급사", "purchaseDate": self.today}, expected=(201,))

    def invoice(self, site_id: int, kind: str, supply: int, tax: int) -> dict:
        return self.api.ok("POST", "/api/v1/taxes", {
            "siteId": site_id, "type": kind, "supplyAmount": supply, "taxAmount": tax,
            "counterparty": f"{self.prefix} 거래처", "issueDate": self.today}, expected=(201,))

    def profit(self, site_id: int) -> dict:
        return self.api.ok("GET", f"/api/v1/sites/{site_id}/profit") or {}

    def wait_profit(self, site_id: int, expected: dict) -> tuple[bool, dict]:
        return self.poll(lambda: self.profit(site_id), lambda p: not diff_fields(p, expected))

    # S0 Gateway 인증 ────────────────────
    def gateway_auth(self) -> None:
        s = "S0 Gateway 인증"
        token, self.api.token = self.api.token, None
        try:
            self.rec.status(s, "무인증 현장 목록 401", self.api.call("GET", "/api/v1/sites"), 401)
            self.rec.status(s, "무인증 손익 재계산 401",
                            self.api.call("POST", f"/api/v1/sites/{MISSING_ID}/profit/reconcile"), 401)
        finally:
            self.api.token = token
        self.rec.status(s, "인증 현장 목록 200", self.api.call("GET", "/api/v1/sites"), 200)

    # S1 #193 분할 입금·취소·미수금 ────────────────────
    def split_payment(self) -> None:
        s = "S1 분할 입금(#193)"
        site = self.site("분할입금")
        inv = self.invoice(site["id"], "SALES", 1000000, 100000)
        path = f"/api/v1/taxes/{inv['id']}"
        payments: list[list[Any]] = []

        def state(name: str) -> None:
            paid, remaining, status = expected_payment_state("1100000", [(a, c) for a, c in payments])
            self.rec.fields(s, name, self.api.ok("GET", path),
                            {"paidAmount": paid, "remainingAmount": remaining, "paymentStatus": status})

        state("생성 직후 UNPAID")
        first = self.api.ok("POST", f"{path}/payments",
                            {"amount": 300000, "paymentDate": self.today, "memo": f"{self.prefix} 기성1"},
                            expected=(201,))
        payments.append(["300000", False])
        state("1차 입금 후 PARTIAL")
        self.api.ok("POST", f"{path}/payments", {"amount": 500000, "paymentDate": self.today}, expected=(201,))
        payments.append(["500000", False])
        state("2차 입금 후 PARTIAL")
        self.rec.status(s, "잔액 초과 입금 400",
                        self.api.call("POST", f"{path}/payments", {"amount": 400000, "paymentDate": self.today}),
                        400)
        canceled = self.api.call("PATCH", f"{path}/payments/{first['id']}/cancel",
                                 {"reason": f"{self.prefix} 오입금 정정"})
        if self.rec.check(s, "1차 입금 취소 200", canceled.status == 200 and (canceled.data or {}).get("canceled"),
                          f"HTTP {canceled.status}"):
            payments[0][1] = True
        state("취소 후 잔액 복원")
        self.rec.status(s, "중복 취소 409",
                        self.api.call("PATCH", f"{path}/payments/{first['id']}/cancel", {"reason": "중복"}), 409)
        paid, _, _ = expected_payment_state("1100000", [(a, c) for a, c in payments])
        self.rec.fields(s, "현장 미수금 집계",
                        self.api.ok("GET", f"/api/v1/taxes/outstanding?siteId={site['id']}"),
                        expected_outstanding([("1100000", paid)]))
        self.rec.status(s, "유효 입금 있는 계산서 삭제 409", self.api.call("DELETE", path), 409)
        self.api.ok("PATCH", f"{path}/confirm-payment", {"paymentDate": self.today})
        payments.append([str(Decimal("1100000") - paid), False])
        state("잔액 입금 확인 후 PAID")
        self.rec.fields(s, "완납 후 미수금 0",
                        self.api.ok("GET", f"/api/v1/taxes/outstanding?siteId={site['id']}"),
                        expected_outstanding([("1100000", "1100000")]))
        rows = self.api.ok("GET", f"{path}/payments") or []
        active = sum((dec(r.get("amount")) for r in rows if not r.get("canceled")), Decimal("0"))
        self.rec.check(s, "입금 내역 3건·취소 1건 보존",
                       len(rows) == 3 and sum(1 for r in rows if r.get("canceled")) == 1 and active == Decimal("1100000"),
                       f"rows={len(rows)} active={active}")

    # S2 #192 BIDDING ────────────────────
    def bidding(self) -> None:
        s = "S2 수주 전 현장(#192)"
        site = self.site("수주전", status="BIDDING")
        self.rec.check(s, "BIDDING 생성", site.get("status") == "BIDDING", str(site.get("status")))
        est = self.estimate(site["id"], "입찰 견적", 2, 250000)
        self.api.ok("PATCH", f"/api/v1/estimates/{est['id']}/confirm")
        ok, prof = self.wait_profit(site["id"], expected_profit("500000", "0"))
        self.rec.check(s, "BIDDING 현장 손익 500,000 수렴", ok, f"estimate={prof.get('totalEstimateAmount')}")

        def dashboard_check(name: str, want_status: str) -> None:
            def fetch() -> dict:
                return self.api.ok("GET", "/api/v1/dashboard/stats") or {}

            def done(stats: dict) -> bool:
                row = next((r for r in stats.get("siteProfits") or [] if r.get("siteId") == site["id"]), None)
                est_sum, pur_sum = dashboard_totals_excluding_bidding(stats)
                return (row is not None and row.get("status") == want_status
                        and dec(row.get("estimateAmount")) == Decimal("500000")
                        and dec(stats.get("totalEstimateAmount")) == est_sum
                        and dec(stats.get("totalPurchaseAmount")) == pur_sum)

            converged, stats = self.poll(fetch, done, timeout=5.0)
            est_sum, _ = dashboard_totals_excluding_bidding(stats)
            self.rec.check(s, name, converged,
                           f"total={stats.get('totalEstimateAmount')} nonBidding={est_sum}")

        dashboard_check("대시보드 합계에서 BIDDING 제외·목록 표시", "BIDDING")
        changed = self.api.call("PATCH", f"/api/v1/sites/{site['id']}/status", {"status": "IN_PROGRESS"})
        self.rec.check(s, "BIDDING→IN_PROGRESS 수주 전환 200",
                       changed.status == 200 and (changed.data or {}).get("status") == "IN_PROGRESS",
                       f"HTTP {changed.status}")
        dashboard_check("수주 전환 후 합계 포함", "IN_PROGRESS")
        self.rec.status(s, "IN_PROGRESS→BIDDING 되돌리기 400",
                        self.api.call("PATCH", f"/api/v1/sites/{site['id']}/status", {"status": "BIDDING"}), 400)

    # S3 #196 매입 증빙 ────────────────────
    def purchase_evidence(self) -> None:
        s = "S3 매입 증빙(#196)"
        site = self.site("매입증빙")
        other = self.site("매입증빙-다른현장")
        a = self.purchase(site["id"], "자재A", 2, 50000)
        b = self.purchase(site["id"], "자재B", 1, 30000)
        c = self.purchase(site["id"], "자재C", 1, 20000)
        inv = self.invoice(site["id"], "PURCHASE", 130000, 13000)
        sales = self.invoice(site["id"], "SALES", 10000, 1000)
        foreign = self.invoice(other["id"], "PURCHASE", 10000, 1000)
        link = lambda pid, tid: self.api.call("PUT", f"/api/v1/purchases/{pid}/evidence", {"taxInvoiceId": tid})
        self.rec.status(s, "매출 계산서 연결 400", link(a["id"], sales["id"]), 400)
        self.rec.status(s, "다른 현장 계산서 연결 400", link(a["id"], foreign["id"]), 400)
        for p, label in ((a, "A"), (b, "B")):
            r = link(p["id"], inv["id"])
            self.rec.check(s, f"매입{label} 증빙 연결 200",
                           r.status == 200 and (r.data or {}).get("evidenceTaxInvoiceId") == inv["id"],
                           f"HTTP {r.status}")
        amounts = [(x["totalAmount"], inv["id"] if x in (a, b) else None) for x in (a, b, c)]
        summary_path = f"/api/v1/purchases/evidence-summary?siteId={site['id']}"
        self.rec.fields(s, "현장 대조 요약(연결 2·미연결 1)", self.api.ok("GET", summary_path),
                        expected_evidence_summary(amounts, [(inv["id"], "130000", "143000")]))
        self.rec.status(s, "연결된 매입 계산서 삭제 409", self.api.call("DELETE", f"/api/v1/taxes/{inv['id']}"), 409)
        for p, label in ((a, "A"), (b, "B")):
            r = self.api.call("DELETE", f"/api/v1/purchases/{p['id']}/evidence")
            self.rec.check(s, f"매입{label} 연결 해제 200",
                           r.status == 200 and (r.data or {}).get("evidenceTaxInvoiceId") is None,
                           f"HTTP {r.status}")
        self.rec.status(s, "해제 후 계산서 삭제 200", self.api.call("DELETE", f"/api/v1/taxes/{inv['id']}"), 200)
        self.rec.fields(s, "삭제 후 대조 요약", self.api.ok("GET", summary_path),
                        expected_evidence_summary([(x["totalAmount"], None) for x in (a, b, c)], []))

    # S4 #201 수정본·추가공사 ────────────────────
    def revision_chain(self) -> None:
        s = "S4 견적 수정본(#201)"
        site = self.site("수정본체인")
        e1 = self.estimate(site["id"], "원 견적", 10, 100000)
        self.api.ok("PATCH", f"/api/v1/estimates/{e1['id']}/confirm")
        self.purchase(site["id"], "원가", 1, 200000)
        ok, prof = self.wait_profit(site["id"], expected_profit("1000000", "200000"))
        self.rec.check(s, "원 견적·매입 손익 수렴", ok, f"estimate={prof.get('totalEstimateAmount')}")

        e2 = self.estimate(site["id"], "수정본", 12, 100000, kind="REVISION", revision_of=e1["id"])
        self.rec.fields(s, "수정본 초안 체인", e2,
                        {"kind": "REVISION", "status": "DRAFT", "revisionOfId": e1["id"], "chainRootId": e1["id"]})
        self.api.ok("PATCH", f"/api/v1/estimates/{e2['id']}/confirm")
        self.rec.fields(s, "원 견적 SUPERSEDED", self.api.ok("GET", f"/api/v1/estimates/{e1['id']}"),
                        {"status": "SUPERSEDED", "supersededById": e2["id"]})
        e3 = self.estimate(site["id"], "추가공사", 1, 300000, kind="ADDITIONAL", revision_of=e2["id"])
        self.rec.fields(s, "추가공사 새 체인", e3, {"kind": "ADDITIONAL", "chainRootId": e3["id"]})
        self.api.ok("PATCH", f"/api/v1/estimates/{e3['id']}/confirm")

        listed = self.api.ok("GET", f"/api/v1/estimates?siteId={site['id']}") or []
        active = active_estimate_total(listed)
        self.rec.check(s, "유효 확정 합계 1,500,000", active == Decimal("1500000"), f"active={active}")
        expected = expected_profit(active, "200000")
        ok, prof = self.wait_profit(site["id"], expected)
        self.rec.fields(s, "손익 수렴(1,500,000/200,000/86.67%)", prof, expected)

        update_body = {"title": f"{self.prefix} 변경 시도", "estimateDate": self.today,
                       "items": [{"itemName": "x", "unit": "식", "quantity": 1, "unitPrice": 1}]}
        self.rec.status(s, "대체된 견적 수정 409",
                        self.api.call("PUT", f"/api/v1/estimates/{e1['id']}", update_body), 409)
        self.rec.status(s, "대체된 견적 확정 409", self.api.call("PATCH", f"/api/v1/estimates/{e1['id']}/confirm"), 409)
        self.rec.status(s, "대체된 견적 삭제 409", self.api.call("DELETE", f"/api/v1/estimates/{e1['id']}"), 409)
        draft = self.estimate(site["id"], "초안", 1, 1000)
        bad = self.api.call("POST", "/api/v1/estimates", {
            "siteId": site["id"], "title": f"{self.prefix} 초안의 수정본", "estimateDate": self.today,
            "items": update_body["items"], "kind": "REVISION", "revisionOfId": draft["id"]})
        self.rec.status(s, "초안을 원 견적으로 연결 400", bad, 400)
        self.rec.status(s, "검증용 초안 삭제 200", self.api.call("DELETE", f"/api/v1/estimates/{draft['id']}"), 200)
        self.revision_site_id = site["id"]
        self.revision_expected = expected

    # S5 #203/#205 손익 재계산 ────────────────────
    def reconcile(self) -> None:
        s = "S5 손익 재계산(#203/#205)"
        if self.revision_site_id is None or self.revision_expected is None:
            self.rec.check(s, "선행 S4 현장", False, "S4 실패로 생략")
            return
        site_id = self.revision_site_id
        path = f"/api/v1/sites/{site_id}/profit/reconcile"
        ok, dry = self.poll(lambda: self.api.ok("POST", path) or {}, lambda r: bool(r.get("matched")))
        self.rec.check(s, "dry-run 일치(matched)", ok,
                       f"est={dry.get('estimateEventsToMark')} pur={dry.get('purchaseEventsToMark')} "
                       f"proj={dry.get('purchaseProjectionsToRewrite')} orphan={dry.get('orphanEstimateEvents')}")
        self.rec.fields(s, "dry-run 플래그·건수", dry, {
            "siteId": site_id, "applyRequested": False, "applied": False, "estimateEventsToMark": 0,
            "purchaseEventsToMark": 0, "purchaseProjectionsToRewrite": 0, "orphanEstimateEvents": 0})
        self.rec.fields(s, "원천 합계 = 기대 손익", dry.get("source"), self.revision_expected)
        self.rec.fields(s, "현재 합계 = 원천 합계", dry.get("before"), self.revision_expected)
        applied = self.api.ok("POST", f"{path}?apply=true") or {}
        self.rec.fields(s, "일치 상태 apply는 무변경", applied,
                        {"applyRequested": True, "applied": False, "matched": True})
        self.rec.fields(s, "apply 후 손익 불변", self.profit(site_id), self.revision_expected)
        self.rec.status(s, "없는 현장 재계산 404",
                        self.api.call("POST", f"/api/v1/sites/{MISSING_ID}/profit/reconcile"), 404)

    def run_all(self) -> None:
        for name, step in (("S0 Gateway 인증", self.gateway_auth), ("S1 분할 입금(#193)", self.split_payment),
                           ("S2 수주 전 현장(#192)", self.bidding), ("S3 매입 증빙(#196)", self.purchase_evidence),
                           ("S4 견적 수정본(#201)", self.revision_chain),
                           ("S5 손익 재계산(#203/#205)", self.reconcile)):
            try:
                step()
            except ApiError as exc:
                self.rec.check(name, "시나리오 중단", False, str(exc))


# ─────────────────────────────────────────
# 진입점
# ─────────────────────────────────────────

def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="BuildFlow 로컬 풀 Docker 주간 기능 실측(Gateway 경유)")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help="로컬 Gateway 원점 (기본 %(default)s)")
    parser.add_argument("--no-bootstrap", action="store_true",
                        help="부트스트랩을 시도하지 않고 기존 로컬 관리자 값을 입력받는다")
    args = parser.parse_args(argv)
    try:
        base_url = check_local_target(args.base_url)
        creds, source = obtain_credentials(CREDENTIAL_FILE, not args.no_bootstrap, prompt=interactive_prompt)
        api = Api(base_url)
        api.login(creds)
    except SetupError as exc:
        print(f"준비 실패: {exc}", file=sys.stderr)
        return 2
    prefix = make_prefix()
    print(f"대상 {base_url} · 관리자 {source} · 접두사 {prefix}")
    rec = Recorder()
    try:
        Scenarios(api, rec, prefix).run_all()
    finally:
        try:
            api.logout()
        except OSError:
            pass
    print(rec.render())
    return 1 if rec.failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
