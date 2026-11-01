#!/usr/bin/env python3
"""합성 공내역서(.xlsx) → 실제 Ollama AI 파싱 → 견적 초안·확정 로컬 격리 실측 (Gateway·JWT 경유).

사용: python -I -X utf8 scripts/qa/estimate_parse_e2e.py [--skip-build] [--keep] [--runs 1]
      [--gateway-port 18083] [--model qwen2.5:7b] [--skip-failure]

- 별도 Compose 프로젝트 `bfqaparse`(빈 볼륨 `bfqaparse_*`, 고정 container_name·공용 네트워크 없음)로
  mysql·redis·kafka·eureka·gateway·auth·site·estimate만 띄운다. 기존 `build-flow` 프로젝트·볼륨과
  다른 bfqa* 스택은 건드리지 않는다. 게이트웨이만 127.0.0.1:<gateway-port>로 공개한다.
- estimate-service는 호스트 native Ollama(`host.docker.internal:11434`)를 쓴다. 사용자 API 키는 넘기지 않는다.
- 합성 공내역서는 실행 임시 디렉터리에서만 만든다(머리글·공종 행·빈 행·소계·합계 행 포함, 단가·금액 빈칸).
- DB 비밀번호·JWT 비밀·QA 관리자 비밀번호는 실행마다 메모리에서만 무작위 생성하고 출력·저장하지 않는다.
- 종료 시(--keep 제외) 격리 프로젝트의 컨테이너·네트워크를 내리고 볼륨은 이름을 지정해 삭제한다.
  종료 코드 0=전부 PASS, 1=FAIL 있음, 2=준비 실패.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import random
import secrets
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from io import BytesIO
from pathlib import Path
from typing import Any, Optional
from xml.sax.saxutils import escape

sys.path.insert(0, str(Path(__file__).resolve().parent))
import local_e2e as e2e  # noqa: E402
import warranty_ocr_e2e as ocr  # noqa: E402  (격리 Compose·Docker·부트스트랩 도우미 재사용)

PROJECT = "bfqaparse"
GATEWAY_PORT = 18083
APP_SERVICES = ("eureka-server", "gateway-server", "auth-service", "site-service", "estimate-service")
HOST_OLLAMA = "http://127.0.0.1:11434"
CONTAINER_OLLAMA = "http://host.docker.internal:11434"
UNREACHABLE_OLLAMA = "http://host.docker.internal:9"  # discard 포트 — 연결 거부로 실패 문구를 확인한다
DEFAULT_MODEL = "qwen2.5:7b"
JAVA_OPTS = "-Xms64m -Xmx384m"
CLIENT_TIMEOUT = 150.0  # 서버 Ollama timeout(120s)보다 길게 — 서버가 먼저 끊는지 본다
MSG_INVALID_FORMAT = "xlsx 형식의 파일만 업로드 가능합니다."
MSG_EXCEL_FAILED = "엑셀 파일 파싱에 실패했습니다."
MSG_OLLAMA_DOWN = "AI 파싱 서비스에 연결할 수 없습니다. Ollama가 실행 중인지 확인해주세요."

# (공종, 품명, 규격, 단위, 수량 후보) — 실제 공내역서처럼 규격이 없는 '식' 항목도 섞는다.
ITEM_POOL = (
    ("가설공사", "규준틀 설치", "평규준틀", "개소", ("4", "6", "8")),
    ("가설공사", "현장 정리", "", "식", ("1",)),
    ("철근콘크리트공사", "레미콘 타설", "25-24-150", "m3", ("12.5", "18", "32.75")),
    ("철근콘크리트공사", "철근 가공 조립", "HD13", "ton", ("2.4", "3.15", "5")),
    ("철근콘크리트공사", "유로폼 설치", "600x1200", "m2", ("85", "120.5", "146")),
    ("방수공사", "우레탄 도막방수", "3mm", "m2", ("64", "92.3")),
    ("타일공사", "바닥 자기질 타일", "300x300", "m2", ("27.5", "41")),
    ("도장공사", "수성페인트 칠", "2회", "m2", ("150", "210.8")),
    ("잡철물공사", "스테인리스 난간", "H1200", "m", ("14", "22.6")),
)


# ─────────────────────────────────────────
# 순수 함수 (단위 테스트 대상)
# ─────────────────────────────────────────

@dataclass(frozen=True)
class BoqItem:
    section: str
    name: str
    spec: str
    unit: str
    quantity: str


def make_items(rng: random.Random, count: int = 0) -> list[BoqItem]:
    """5~8개 품목. 규격 없는 '식' 항목을 반드시 하나 넣어 빈 셀 열 정렬을 시험한다."""
    count = count or rng.randint(5, 8)
    pool = [p for p in ITEM_POOL if p[2]]
    blank_spec = [p for p in ITEM_POOL if not p[2]]
    chosen = rng.sample(pool, count - 1) + blank_spec[:1]
    order = {p[1]: i for i, p in enumerate(ITEM_POOL)}
    chosen.sort(key=lambda p: order[p[1]])
    return [BoqItem(s, n, sp, u, rng.choice(q)) for s, n, sp, u, q in chosen]


def _cell(ref: str, value: Any) -> str:
    if value is None or value == "":
        return ""  # 엑셀처럼 빈 셀은 XML에 아예 쓰지 않는다
    if isinstance(value, (int, float, Decimal)):
        return f'<c r="{ref}"><v>{value}</v></c>'
    return f'<c r="{ref}" t="inlineStr"><is><t xml:space="preserve">{escape(str(value))}</t></is></c>'


def boq_rows(items: list[BoqItem], title: str) -> list[list[Any]]:
    """공내역서 표: 제목·공사명·빈 행·머리글·공종 행·품목·소계·빈 행·합계. 단가·금액은 비운다."""
    rows: list[list[Any]] = [["공 내 역 서"], ["공사명", title], [],
                             ["번호", "품명", "규격", "단위", "수량", "단가", "금액", "비고"]]
    section = None
    no = 0
    for item in items:
        if item.section != section:
            if section is not None:
                rows.append(["", f"{section} 소계"])
            section = item.section
            rows.append(["", f"[{section}]"])
        no += 1
        rows.append([no, item.name, item.spec, item.unit, Decimal(item.quantity), "", "", ""])
    rows.append(["", f"{section} 소계"])
    rows.append([])
    rows.append(["", "합계", "", "", "", "", "", "부가세 별도"])
    return rows


def build_xlsx(rows: list[list[Any]]) -> bytes:
    """openpyxl 없이 최소 OOXML 통합문서를 만든다(inlineStr, 빈 셀 생략)."""
    cols = "ABCDEFGH"
    sheet_rows = []
    for r, row in enumerate(rows, start=1):
        cells = "".join(_cell(f"{cols[c]}{r}", v) for c, v in enumerate(row))
        if cells:
            sheet_rows.append(f'<row r="{r}">{cells}</row>')
    ns = 'xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"'
    rel_ns = 'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
    parts = {
        "[Content_Types].xml":
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
            '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
            '<Default Extension="xml" ContentType="application/xml"/>'
            '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.'
            'spreadsheetml.sheet.main+xml"/>'
            '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-'
            'officedocument.spreadsheetml.worksheet+xml"/>'
            '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.'
            'spreadsheetml.styles+xml"/></Types>',
        "_rels/.rels":
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/'
            'officeDocument" Target="xl/workbook.xml"/></Relationships>',
        "xl/workbook.xml":
            f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook {ns} {rel_ns}>'
            '<sheets><sheet name="공내역서" sheetId="1" r:id="rId1"/></sheets></workbook>',
        "xl/_rels/workbook.xml.rels":
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/'
            'worksheet" Target="worksheets/sheet1.xml"/>'
            '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/'
            'styles" Target="styles.xml"/></Relationships>',
        "xl/styles.xml":
            f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet {ns}>'
            '<fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>'
            '<fills count="1"><fill><patternFill patternType="none"/></fill></fills>'
            '<borders count="1"><border/></borders>'
            '<cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="1"><xf/></cellXfs></styleSheet>',
        "xl/worksheets/sheet1.xml":
            f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet {ns}>'
            f'<sheetData>{"".join(sheet_rows)}</sheetData></worksheet>',
    }
    buf = BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for name, text in parts.items():
            zf.writestr(name, text.encode("utf-8"))
    return buf.getvalue()


def norm_name(value: Any) -> str:
    return "".join(str(value or "").split()).lower()


def to_decimal(value: Any) -> Optional[Decimal]:
    if value is None or isinstance(value, bool):
        return None
    try:
        return Decimal(str(value))
    except (InvalidOperation, ValueError):
        return None


def evaluate_parse(expected: list[BoqItem], parsed: list[dict]) -> dict:
    """원본 품목과 AI 결과를 비교한다. 서버에는 모델 출력 검증 규칙이 없으므로(OllamaService는 JSON 배열
    역직렬화만 함) 다음을 엄격 기준으로 둔다: 품목 수 일치, 품명은 공백 무시 일치(규격이 덧붙은 경우는
    `name_with_spec`로 따로 센다), 단위는 공백 무시 대소문자 무시 일치, 수량은 수치 동일."""
    used: set[int] = set()
    rows = []
    for item in expected:
        want = norm_name(item.name)
        idx = next((i for i, p in enumerate(parsed) if i not in used and norm_name(p.get("itemName")) == want), None)
        spec_merged = False
        if idx is None:
            idx = next((i for i, p in enumerate(parsed) if i not in used
                        and norm_name(p.get("itemName")).startswith(want)), None)
            spec_merged = idx is not None
        if idx is None:
            rows.append({"name": item.name, "found": False})
            continue
        used.add(idx)
        got = parsed[idx]
        rows.append({"name": item.name, "found": True, "spec_merged": spec_merged,
                     "unit_ok": norm_name(got.get("unit")) == norm_name(item.unit),
                     "qty_ok": to_decimal(got.get("quantity")) == Decimal(item.quantity),
                     "got_unit": got.get("unit"), "got_qty": got.get("quantity")})
    extras = [p.get("itemName") for i, p in enumerate(parsed) if i not in used]
    found = [r for r in rows if r["found"]]
    return {
        "expected": len(expected), "parsed": len(parsed), "matched": len(found),
        "exact_name": sum(1 for r in found if not r["spec_merged"]),
        "name_with_spec": sum(1 for r in found if r["spec_merged"]),
        "unit_ok": sum(1 for r in found if r["unit_ok"]),
        "qty_ok": sum(1 for r in found if r["qty_ok"]),
        "missing": [r["name"] for r in rows if not r["found"]],
        "extras": extras,
        "wrong": [f"{r['name']}: unit={r['got_unit']} qty={r['got_qty']}" for r in found
                  if not (r["unit_ok"] and r["qty_ok"])],
        "null_price": sum(1 for p in parsed if p.get("unitPrice") is None or p.get("amount") is None),
    }


def parse_passes(result: dict) -> bool:
    return (result["parsed"] == result["expected"] == result["matched"] == result["unit_ok"] == result["qty_ok"]
            and not result["extras"])


def draft_items(parsed: list[dict], rng: random.Random) -> list[dict]:
    """사용자가 미리보기를 확인한 뒤 단가를 입력하는 동작을 흉내 낸다. 품명·단위·수량은 AI 결과 그대로."""
    out = []
    for p in parsed:
        out.append({"itemName": str(p.get("itemName") or "").strip(), "unit": str(p.get("unit") or "").strip(),
                    "quantity": p.get("quantity"), "unitPrice": rng.randrange(1000, 200000, 500)})
    return out


def expected_total(items: list[dict]) -> Decimal:
    return sum((Decimal(str(i["quantity"])) * Decimal(str(i["unitPrice"])) for i in items), Decimal("0"))


def build_compose(repo: Path, gateway_port: int = GATEWAY_PORT, model: str = DEFAULT_MODEL) -> dict:
    """warranty_ocr_e2e의 격리 정의에서 notification을 빼고 estimate-service를 더한다.
    Ollama 주소는 ${BFQA_OLLAMA_URL}로 받아 실패 문구 시험 때 estimate만 재생성한다."""
    spec = ocr.build_compose(repo, gateway_port, project=PROJECT)
    services = spec["services"]
    services.pop("notification-service")
    spec["volumes"].pop("warranty_uploads", None)
    root = repo.as_posix()
    healthy = {"condition": "service_healthy"}
    services["estimate-service"] = {
        "build": {"context": root, "dockerfile": "Dockerfile", "args": {"SERVICE_NAME": "estimate-service"}},
        "image": f"{PROJECT}-estimate-service:local",
        "environment": {"SPRING_PROFILES_ACTIVE": "docker", "MANAGEMENT_TRACING_ENABLED": "false",
                        "EUREKA_CLIENT_SERVICEURL_DEFAULTZONE": "http://eureka-server:8761/eureka/",
                        "SPRING_CLOUD_CONFIG_ENABLED": "false",
                        "DB_HOST": "mysql", "DB_USERNAME": "root",
                        "DB_PASSWORD": "${BFQA_DB_PASSWORD:?run the script}",
                        "REDIS_HOST": "redis", "KAFKA_BOOTSTRAP_SERVERS": "kafka:9092",
                        "OLLAMA_URL": "${BFQA_OLLAMA_URL:?run the script}", "OLLAMA_MODEL": model,
                        "CLAUDE_API_KEY": "", "JAVA_TOOL_OPTIONS": JAVA_OPTS},
        # Docker Desktop 밖(Linux 엔진)에서도 host.docker.internal이 호스트를 가리키게 한다.
        "extra_hosts": ["host.docker.internal:host-gateway"],
        "depends_on": {"mysql": healthy, "kafka": healthy, "redis": healthy},
    }
    for name in ("auth-service", "site-service"):
        services[name]["environment"].update({"SPRING_CLOUD_CONFIG_ENABLED": "false", "JAVA_TOOL_OPTIONS": JAVA_OPTS})
    services["gateway-server"]["environment"]["JAVA_TOOL_OPTIONS"] = JAVA_OPTS
    return spec


# ─────────────────────────────────────────
# 부수 효과
# ─────────────────────────────────────────

def host_ollama_has(model: str) -> bool:
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(HOST_OLLAMA + "/api/tags", timeout=10) as resp:
            names = [m.get("name") for m in json.loads(resp.read().decode("utf-8")).get("models", [])]
    except (urllib.error.URLError, OSError, ValueError):
        return False
    return model in names


def upload(api: e2e.Api, filename: str, content: bytes, auth: bool = True) -> tuple[e2e.Response, float]:
    boundary = "bfqa" + uuid.uuid4().hex
    body = ocr.multipart_body({}, "file", filename, content, boundary)
    headers = {"Accept": "application/json", "Content-Type": f"multipart/form-data; boundary={boundary}"}
    if auth and api.token:
        headers["Authorization"] = f"Bearer {api.token}"
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), e2e._NoRedirect)
    req = urllib.request.Request(api.base_url + "/api/v1/estimates/parse", data=body, headers=headers,
                                 method="POST")
    started = time.monotonic()
    try:
        with opener.open(req, timeout=CLIENT_TIMEOUT) as resp:
            status, raw = resp.status, resp.read()
    except urllib.error.HTTPError as exc:
        status, raw = exc.code, exc.read()
    except (urllib.error.URLError, OSError):
        status, raw = 0, '{"error": "연결 실패 또는 시간 초과"}'.encode("utf-8")
    return e2e.Response(status, e2e.parse_body(raw)), time.monotonic() - started


def error_message(response: e2e.Response) -> str:
    err = response.body.get("error") if isinstance(response.body, dict) else None
    return str(err.get("message", "")) if isinstance(err, dict) else str(err or "")


def jsonable(items: Any) -> Any:
    return json.loads(json.dumps(items, default=str))


def scenario_guards(api: e2e.Api, rec: e2e.Recorder, sample: bytes) -> None:
    sc = "P0 입력 경계"
    response, _ = upload(api, "synthetic.xlsx", sample, auth=False)
    rec.check(sc, "무인증 파싱 401", response.status == 401, f"HTTP {response.status}")
    response, _ = upload(api, "synthetic.csv", "품명,단위,수량\n레미콘,m3,1\n".encode("utf-8"))
    rec.check(sc, "CSV 거부 400·안내 문구", response.status == 400 and error_message(response) == MSG_INVALID_FORMAT,
              f"HTTP {response.status} {error_message(response)}")
    response, _ = upload(api, "broken.xlsx", secrets.token_bytes(512))
    rec.check(sc, "깨진 xlsx 422·안내 문구", response.status == 422 and error_message(response) == MSG_EXCEL_FAILED,
              f"HTTP {response.status} {error_message(response)}")


def scenario_parse(api: e2e.Api, rec: e2e.Recorder, run: int, rng: random.Random, prefix: str) -> dict:
    sc = f"P{run} AI 파싱→견적"
    items = make_items(rng)
    content = build_xlsx(boq_rows(items, f"{prefix} 합성 공사 {run}"))
    filename = f"synthetic-boq-{run}.xlsx"
    response, elapsed = upload(api, filename, content)
    summary: dict[str, Any] = {"run": run, "items": len(items), "seconds": elapsed, "status": response.status}
    if not rec.check(sc, "파싱 200", response.status == 200,
                     f"HTTP {response.status} {elapsed:.1f}s {error_message(response)}"):
        return summary
    data = response.data or {}
    parsed = data.get("items") or []
    result = evaluate_parse(items, parsed)
    summary["result"] = result
    rec.check(sc, "응답 fileName·itemCount 일관", data.get("fileName") == filename and data.get("itemCount") == len(parsed),
              f"fileName={data.get('fileName')} itemCount={data.get('itemCount')}/{len(parsed)}")
    rec.check(sc, "품목 수·품명·단위·수량 원본 일치", parse_passes(result),
              f"{elapsed:.1f}s 기대 {result['expected']} 결과 {result['parsed']} 일치 {result['matched']} "
              f"단위 {result['unit_ok']} 수량 {result['qty_ok']} 규격 덧붙음 {result['name_with_spec']} "
              f"누락 {result['missing']} 추가 {result['extras']} 오차 {result['wrong']}"[:400])
    rec.check(sc, "단가·금액 빈칸이 null 아님(미리보기 표시 가능)", result["null_price"] == 0,
              f"null 단가/금액 {result['null_price']}건")

    usable = [p for p in parsed if norm_name(p.get("itemName")) and norm_name(p.get("unit"))
              and (to_decimal(p.get("quantity")) or 0) > 0]
    if not usable:
        rec.check(sc, "견적 초안 생성 가능 품목", False, "0건")
        return summary
    site = api.ok("POST", "/api/v1/sites", {"siteName": f"{prefix} 공내역서 {run}", "memo": "로컬 AI 파싱 합성 데이터"},
                  expected=(201,))
    body_items = draft_items(jsonable(usable), rng)
    total = expected_total(body_items)
    created = api.call("POST", "/api/v1/estimates", {
        "siteId": site["id"], "title": f"{prefix} {filename.removesuffix('.xlsx')}",
        "estimateDate": dt.date.today().isoformat(), "items": body_items})
    if not rec.status(sc, "AI 결과로 견적 초안 201", created, 201):
        return summary
    est = created.data or {}
    rec.check(sc, "초안 DRAFT·합계 일치", est.get("status") == "DRAFT" and e2e.dec(est.get("totalAmount")) == total,
              f"status={est.get('status')} total={est.get('totalAmount')}≠{total}"
              if est.get("status") != "DRAFT" or e2e.dec(est.get("totalAmount")) != total else "")
    confirmed = api.call("PATCH", f"/api/v1/estimates/{est['id']}/confirm")
    rec.check(sc, "견적 확정 CONFIRMED", confirmed.status == 200 and (confirmed.data or {}).get("status") == "CONFIRMED",
              f"HTTP {confirmed.status} status={(confirmed.data or {}).get('status')}")
    done, profit = e2e.poll(lambda: api.ok("GET", f"/api/v1/sites/{site['id']}/profit") or {},
                            lambda p: e2e.dec((p or {}).get("totalEstimateAmount")) == total, timeout=60)
    rec.check(sc, "현장 손익 견적 합계 반영(Kafka)", done,
              f"totalEstimateAmount={(profit or {}).get('totalEstimateAmount')}≠{total}" if not done else "")
    summary["total"] = total
    return summary


def scenario_ollama_down(docker: ocr.Docker, api: e2e.Api, rec: e2e.Recorder, sample: bytes) -> None:
    sc = "P9 Ollama 미기동"
    docker.env["BFQA_OLLAMA_URL"] = UNREACHABLE_OLLAMA
    docker.compose("up", "-d", "--no-deps", "estimate-service", timeout=300)
    try:
        def probe() -> bool:
            # 라우트가 살아나면 CSV 거부 400이 돌아온다(Ollama를 부르지 않는 경로).
            return upload(api, "probe.csv", b"x")[0].status == 400
        ocr.wait_until("estimate 재기동", probe, docker=docker)
        response, elapsed = upload(api, "synthetic-down.xlsx", sample)
        rec.check(sc, "503·사용자 안내 문구", response.status == 503 and error_message(response) == MSG_OLLAMA_DOWN,
                  f"HTTP {response.status} {elapsed:.1f}s {error_message(response)}")
    finally:
        docker.env["BFQA_OLLAMA_URL"] = CONTAINER_OLLAMA


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="합성 공내역서 AI 파싱 로컬 격리 실측")
    parser.add_argument("--gateway-port", type=int, default=GATEWAY_PORT)
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--runs", type=int, default=1, help="합성 공내역서 파싱 반복 횟수(매번 다른 품목)")
    parser.add_argument("--skip-build", action="store_true", help="이미 만든 bfqaparse-*:local 이미지 재사용")
    parser.add_argument("--skip-failure", action="store_true", help="Ollama 미기동 문구 시험 생략")
    parser.add_argument("--keep", action="store_true", help="실측 후 격리 프로젝트를 남긴다(수동 정리 필요)")
    args = parser.parse_args(argv)

    base_url = e2e.check_local_target(f"http://127.0.0.1:{args.gateway_port}")
    if not host_ollama_has(args.model):
        print(f"[SETUP] 호스트 Ollama({HOST_OLLAMA})에 모델 {args.model}이 없거나 응답하지 않습니다.")
        return 2
    repo = e2e.REPO_ROOT
    with tempfile.TemporaryDirectory(prefix="bfqaparse-") as tmp:
        compose_file = Path(tmp) / "compose.json"
        compose_file.write_text(json.dumps(build_compose(repo, args.gateway_port, args.model), ensure_ascii=False),
                                encoding="utf-8")
        env = dict(os.environ)
        env["BFQA_DB_PASSWORD"] = secrets.token_urlsafe(24)
        env["BFQA_JWT_SECRET"] = secrets.token_hex(48)
        env["BFQA_OLLAMA_URL"] = CONTAINER_OLLAMA
        docker = ocr.Docker(compose_file, env, project=PROJECT)
        protected_before = ocr.protected_volumes_present(docker)
        rec = e2e.Recorder()
        summaries: list[dict] = []
        timings: dict[str, float] = {}
        exit_code = 2
        rng = random.Random(secrets.randbits(32))
        try:
            leftover = docker.cleanup()
            if leftover:
                print(f"[정리] 이전 격리 볼륨 삭제: {', '.join(leftover)}")
            if not args.skip_build:
                t0 = time.monotonic()
                for service in APP_SERVICES:  # 병렬 빌드의 외부 TLS 오류를 피하려고 순차 빌드
                    docker.compose("build", service, timeout=1800)
                timings["build"] = time.monotonic() - t0
            t0 = time.monotonic()
            docker.compose("up", "-d", timeout=600)
            api = e2e.Api(base_url)
            ocr.wait_until("Gateway·auth", lambda: api.call("POST", "/api/v1/auth/login",
                           {"loginId": "bfqa-probe", "password": "x" * 12}, auth=False).status in (400, 401),
                           docker=docker)
            creds = e2e.generate_credentials()
            if not ocr.bootstrap_admin(docker, creds):
                raise e2e.SetupError("QA 관리자 부트스트랩 실패")
            api.login(creds)
            ocr.wait_until("site·estimate 라우팅", lambda: api.call("GET", "/api/v1/sites").status == 200
                           and api.call("GET", "/api/v1/estimates").status == 200, docker=docker)
            timings["ready"] = time.monotonic() - t0
            prefix = f"[QA-PARSE-{dt.datetime.now():%Y%m%d%H%M%S}]"
            sample = build_xlsx(boq_rows(make_items(rng), f"{prefix} 경계 시험"))
            scenario_guards(api, rec, sample)
            for run in range(1, max(1, args.runs) + 1):
                summaries.append(scenario_parse(api, rec, run, rng, prefix))
            if not args.skip_failure:
                scenario_ollama_down(docker, api, rec, sample)
            api.logout()
            exit_code = 1 if rec.failed else 0
        except (e2e.SetupError, e2e.ApiError, subprocess.TimeoutExpired) as exc:
            print(f"[SETUP] {exc}")
            if docker.project_containers():
                logs = docker.compose("logs", "--no-color", "--tail", "40", "estimate-service",
                                      check=False).stdout.decode("utf-8", "replace")
                print(logs[-3000:])
        finally:
            if args.keep:
                print(f"[유지] 격리 프로젝트 {PROJECT}를 남겼습니다. 정리: 이 스크립트를 다시 실행하거나 수동 삭제")
            else:
                removed = docker.cleanup()
                remaining = docker.project_containers() + docker.existing_volumes()
                print(f"[정리] 격리 컨테이너·네트워크 down, 볼륨 삭제 {len(removed)}개, 잔여 {len(remaining)}개")
                if remaining:
                    exit_code = max(exit_code, 1)
            protected_after = ocr.protected_volumes_present(docker)
            print(f"[보호] 기존 볼륨 {len(protected_before)}개 → {len(protected_after)}개: {', '.join(protected_after)}")
            if protected_before != protected_after:
                exit_code = max(exit_code, 1)

    print(rec.render())
    for s in summaries:
        r = s.get("result") or {}
        print(f"[파싱 {s['run']}] 모델={args.model} HTTP {s['status']} 소요={s['seconds']:.1f}s 품목 {s['items']}개 → "
              f"결과 {r.get('parsed')}개, 품명 일치 {r.get('matched')}(규격 덧붙음 {r.get('name_with_spec')}), "
              f"단위 {r.get('unit_ok')}, 수량 {r.get('qty_ok')}, null 단가/금액 {r.get('null_price')}")
    for name, seconds in timings.items():
        print(f"[시간] {name} {seconds:.0f}s")
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
