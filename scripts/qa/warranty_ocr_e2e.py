#!/usr/bin/env python3
"""하자보증보험 합성 PDF 업로드→OCR 로컬 격리 실측 (Gateway·JWT 경유).

사용: python -I -X utf8 scripts/qa/warranty_ocr_e2e.py [--skip-build] [--keep] [--gateway-port 18080]

- 별도 Compose 프로젝트 `bfqaocr`(빈 볼륨 `bfqaocr_*`, 고정 container_name·공용 네트워크 없음)로
  mysql·redis·kafka·eureka·gateway·auth·site·notification만 띄운다. 기존 `build-flow` 프로젝트·볼륨은
  조회만 하고 건드리지 않는다. 게이트웨이만 127.0.0.1:<gateway-port>로 공개한다.
- notification-service는 VPS와 같은 제한(mem_limit 896m, -Xmx320m)으로 실행한다.
- 합성 PDF 두 종류를 실행 임시 디렉터리에서 만든다(커밋·외부 다운로드 없음):
  (a) 한글 폰트 내장 텍스트 층 1쪽 → PDFBox 텍스트 추출 경로
  (b) 같은 내용을 300 DPI 흑백 래스터로만 넣은 1쪽(텍스트 층 없음) → Tesseract CLI 경로
  생성은 notification 이미지 안의 PDFBox jar + 로컬 JDK 이미지(네트워크 없음) + 호스트 한글 폰트로 한다.
- DB 비밀번호·JWT 비밀·QA 관리자 비밀번호는 실행마다 메모리에서만 무작위 생성하고 출력·저장하지 않는다.
- 종료 시(--keep 제외) 격리 프로젝트의 컨테이너·네트워크를 내리고 볼륨은 이름을 지정해 삭제한다.
  종료 코드 0=전부 PASS, 1=FAIL 있음, 2=준비 실패.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import random
import re
import secrets
import subprocess
import sys
import tempfile
import threading
import time
import uuid
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Optional

sys.path.insert(0, str(Path(__file__).resolve().parent))
import local_e2e as e2e  # noqa: E402  (같은 디렉터리의 공용 HTTP·자격·기록 도우미)

PROJECT = "bfqaocr"
PROTECTED_PROJECT = "build-flow"
PROTECTED_VOLUMES = ("build-flow_kafka_data", "build-flow_mysql_data",
                     "build-flow_redis_data", "build-flow_warranty_uploads")
VOLUMES = ("mysql_data", "redis_data", "kafka_data", "warranty_uploads")
APP_SERVICES = ("eureka-server", "gateway-server", "auth-service", "site-service", "notification-service")
DEFAULT_FONT = Path(r"C:\Windows\Fonts\malgun.ttf")
PDFGEN_IMAGE = "eclipse-temurin:17-jdk-jammy"
PDFBOX_LIBS = re.compile(r"BOOT-INF/lib/(pdfbox|fontbox|pdfbox-io|commons-logging)-[\d.]+\.jar")
READY_TIMEOUT = 420.0
OCR_TIMEOUT = 180.0
EXPIRING_DAYS = 30
INSURER_TEXT = "서울보증보험"
INSURER_IMAGE = "삼성화재"


# ─────────────────────────────────────────
# 순수 함수 (단위 테스트 대상)
# ─────────────────────────────────────────

def require_isolated_project(name: str) -> str:
    if not re.fullmatch(r"bfqa[a-z0-9]*", name) or name == PROTECTED_PROJECT:
        raise e2e.SetupError("격리 프로젝트 이름은 bfqa로 시작하는 소문자·숫자여야 합니다.")
    return name


def volume_names(project: str) -> list[str]:
    return [f"{project}_{v}" for v in VOLUMES]


def normalize_policy(value: str) -> str:
    """WarrantyOcrService.normalizePolicyNumber와 같은 OCR 혼동 글자·구분자 정규화."""
    table = {"O": "0", "Q": "0", "D": "0", "I": "1", "L": "1", "Z": "2", "A": "4",
             "S": "5", "G": "6", "B": "8"}
    out = []
    for ch in value.upper():
        if ch in "- _.":
            continue
        out.append(table.get(ch, ch))
    return "".join(out)


@dataclass(frozen=True)
class PdfSpec:
    kind: str          # text | image
    filename: str
    insurer: str
    policy: str
    start: dt.date
    end: dt.date

    def line(self) -> str:
        return "\t".join([self.kind, self.filename, self.insurer, self.policy,
                          self.start.isoformat(), self.end.isoformat()])


def make_specs(today: dt.date, rng: random.Random) -> list[PdfSpec]:
    """만료 목록(30일) 안에 들도록 종료일을 오늘 이후 가까운 날로 둔다. 두 PDF 값은 서로 다르다."""
    return [
        PdfSpec("text", "synthetic-text.pdf", INSURER_TEXT, f"QA-{rng.randint(100000, 999999)}-01",
                today - dt.timedelta(days=350), today + dt.timedelta(days=15)),
        PdfSpec("image", "synthetic-image.pdf", INSURER_IMAGE, f"2026-{rng.randint(100000, 999999)}",
                today - dt.timedelta(days=345), today + dt.timedelta(days=20)),
    ]


def evaluate_ocr(spec: PdfSpec, data: dict) -> tuple[list[str], str]:
    """(필수 판정 불일치 목록, 증권번호 관찰). 코드 판정 기준: 보험사·시작일·종료일 완전 + 충돌 없음 = SUCCESS.
    텍스트 층은 증권번호까지 정확해야 한다. 이미지 OCR은 증권번호(선택 필드)를 정규화 비교하고 미추출을 허용한다."""
    mismatches = []
    if data.get("ocrStatus") != "SUCCESS":
        mismatches.append(f"ocrStatus={data.get('ocrStatus')}≠SUCCESS")
    for key, want in (("insuranceCompany", spec.insurer), ("startDate", spec.start.isoformat()),
                      ("endDate", spec.end.isoformat())):
        if data.get(key) != want:
            mismatches.append(f"{key}={data.get(key)}≠{want}")
    got = data.get("policyNumber")
    if got == spec.policy:
        note = "정확"
    elif got is None:
        note = "미추출"
    elif normalize_policy(got) == normalize_policy(spec.policy):
        note = f"정규화 일치({got})"
    else:
        note = f"불일치({got})"
    if spec.kind == "text" and note != "정확":
        mismatches.append(f"policyNumber={got}≠{spec.policy}")
    return mismatches, note


def multipart_body(fields: dict[str, str], file_field: str, filename: str,
                   content: bytes, boundary: str) -> bytes:
    parts = []
    for name, value in fields.items():
        parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n"
                     .encode("utf-8"))
    parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{file_field}\"; "
                 f"filename=\"{filename}\"\r\nContent-Type: application/pdf\r\n\r\n".encode("utf-8"))
    parts.append(content + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode("utf-8"))
    return b"".join(parts)


_UNITS = {"B": 1, "KIB": 1024, "MIB": 1024 ** 2, "GIB": 1024 ** 3, "KB": 1000, "MB": 1000 ** 2, "GB": 1000 ** 3}


def parse_mem_usage(text: str) -> Optional[int]:
    """`docker stats` MemUsage("412.3MiB / 896MiB")의 사용량을 바이트로."""
    match = re.match(r"\s*([\d.]+)\s*([KMG]?i?B)", text, re.IGNORECASE)
    if not match:
        return None
    return int(float(match.group(1)) * _UNITS[match.group(2).upper()])


def audit_seconds(history: list[dict]) -> Optional[float]:
    """UPLOADED→OCR_APPLIED/OCR_FAILED 서버 기록 시각 차(초)."""
    def when(action: str) -> Optional[dt.datetime]:
        for row in history:
            if row.get("action") == action and row.get("occurredAt"):
                try:
                    return dt.datetime.fromisoformat(str(row["occurredAt"]))
                except ValueError:
                    return None
        return None
    start = when("UPLOADED")
    end = when("OCR_APPLIED") or when("OCR_FAILED")
    return (end - start).total_seconds() if start and end else None


def build_compose(repo: Path, gateway_port: int) -> dict:
    """격리 프로젝트 Compose 정의. 비밀값은 ${BFQA_*} 참조만 담고 파일에 쓰지 않는다."""
    root = repo.as_posix()
    eureka = {"EUREKA_CLIENT_SERVICEURL_DEFAULTZONE": "http://eureka-server:8761/eureka/"}
    common = {"SPRING_PROFILES_ACTIVE": "docker", "MANAGEMENT_TRACING_ENABLED": "false", **eureka}
    db = {"DB_HOST": "mysql", "DB_USERNAME": "root", "DB_PASSWORD": "${BFQA_DB_PASSWORD:?run the script}"}
    jwt = {"JWT_SECRET": "${BFQA_JWT_SECRET:?run the script}"}
    healthy = {"condition": "service_healthy"}

    def app(service: str, dockerfile: str = "Dockerfile", **extra: Any) -> dict:
        spec = {"build": {"context": root, "dockerfile": dockerfile, "args": {"SERVICE_NAME": service}},
                "image": f"{PROJECT}-{service}:local"}
        spec.update(extra)
        return spec

    return {
        "name": PROJECT,
        "services": {
            "mysql": {
                "image": "mysql:8.0",
                "environment": {"MYSQL_ROOT_PASSWORD": "${BFQA_DB_PASSWORD:?run the script}",
                                "MYSQL_CHARACTER_SET_SERVER": "utf8mb4",
                                "MYSQL_COLLATION_SERVER": "utf8mb4_unicode_ci"},
                "volumes": ["mysql_data:/var/lib/mysql",
                            {"type": "bind", "source": f"{root}/docker/mysql/init",
                             "target": "/docker-entrypoint-initdb.d", "read_only": True}],
                "healthcheck": {"test": ["CMD", "mysqladmin", "ping", "-h", "localhost"],
                                "interval": "5s", "timeout": "5s", "retries": 30},
            },
            "redis": {
                "image": "redis:7.2-alpine", "command": "redis-server --appendonly yes",
                "volumes": ["redis_data:/data"],
                "healthcheck": {"test": ["CMD", "redis-cli", "ping"], "interval": "5s", "retries": 20},
            },
            "kafka": {
                "image": "bitnamilegacy/kafka:3.7",
                "environment": {
                    "KAFKA_CFG_NODE_ID": "1", "KAFKA_CFG_PROCESS_ROLES": "broker,controller",
                    "KAFKA_CFG_CONTROLLER_QUORUM_VOTERS": "1@kafka:9093",
                    "KAFKA_CFG_LISTENERS": "PLAINTEXT://:9092,CONTROLLER://:9093",
                    "KAFKA_CFG_ADVERTISED_LISTENERS": "PLAINTEXT://kafka:9092",
                    "KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP": "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT",
                    "KAFKA_CFG_CONTROLLER_LISTENER_NAMES": "CONTROLLER",
                    "KAFKA_CFG_INTER_BROKER_LISTENER_NAME": "PLAINTEXT",
                    "KAFKA_CFG_AUTO_CREATE_TOPICS_ENABLE": "true",
                    "KAFKA_CFG_DEFAULT_REPLICATION_FACTOR": "1", "KAFKA_CFG_NUM_PARTITIONS": "3",
                    "KAFKA_HEAP_OPTS": "-Xms256m -Xmx384m"},
                "volumes": ["kafka_data:/bitnami/kafka"],
                "healthcheck": {"test": ["CMD-SHELL", "kafka-topics.sh --bootstrap-server localhost:9092 --list"],
                                "interval": "10s", "timeout": "10s", "retries": 20},
            },
            "eureka-server": app("eureka-server", environment={"SPRING_PROFILES_ACTIVE": "docker"}),
            "gateway-server": app(
                "gateway-server", ports=[f"127.0.0.1:{gateway_port}:8080"],
                # config-server를 띄우지 않으므로 Gateway의 필수 config import 검사를 끈다(라우트는 자체 yml).
                environment={**common, **jwt, "SPRING_DATA_REDIS_HOST": "redis",
                             "SPRING_CLOUD_CONFIG_ENABLED": "false"},
                depends_on={"redis": healthy, "eureka-server": {"condition": "service_started"}}),
            "auth-service": app(
                "auth-service", environment={**common, **db, **jwt, "REDIS_HOST": "redis"},
                depends_on={"mysql": healthy, "redis": healthy}),
            "site-service": app(
                "site-service", environment={**common, **db, "REDIS_HOST": "redis",
                                             "KAFKA_BOOTSTRAP_SERVERS": "kafka:9092"},
                depends_on={"mysql": healthy, "kafka": healthy}),
            "notification-service": app(
                "notification-service", dockerfile="notification-service/Dockerfile",
                # VPS와 같은 자원 경계(docker-compose.vps.yml)
                mem_limit="896m",
                environment={**common, **db, **jwt, "REDIS_HOST": "redis",
                             "KAFKA_BOOTSTRAP_SERVERS": "kafka:9092",
                             "UPLOAD_DIR": "/app/uploads/warranties",
                             "JAVA_TOOL_OPTIONS": "-Xms64m -Xmx320m"},
                volumes=["warranty_uploads:/app/uploads/warranties"],
                depends_on={"mysql": healthy, "kafka": healthy, "redis": healthy}),
        },
        "volumes": {v: {} for v in VOLUMES},
    }


# ─────────────────────────────────────────
# Docker
# ─────────────────────────────────────────

class Docker:
    def __init__(self, compose_file: Path, env: dict[str, str], project: str = PROJECT):
        self.project = require_isolated_project(project)
        self.compose_file = compose_file
        self.env = env

    def run(self, args: list[str], timeout: float = 120, check: bool = True,
            input_bytes: Optional[bytes] = None, quiet: bool = False) -> subprocess.CompletedProcess:
        result = subprocess.run(args, input=input_bytes, capture_output=True, env=self.env,
                                timeout=timeout, check=False)
        if check and result.returncode != 0:
            tail = "" if quiet else result.stderr.decode("utf-8", "replace")[-600:]
            raise e2e.SetupError(f"docker 명령 실패({' '.join(args[:4])}…): {tail}")
        return result

    def compose(self, *args: str, timeout: float = 120, check: bool = True,
                input_bytes: Optional[bytes] = None, quiet: bool = False) -> subprocess.CompletedProcess:
        return self.run(["docker", "compose", "-p", self.project, "-f", str(self.compose_file), *args],
                        timeout=timeout, check=check, input_bytes=input_bytes, quiet=quiet)

    def container_id(self, service: str) -> str:
        return self.compose("ps", "-q", service).stdout.decode().strip()

    def project_containers(self) -> list[str]:
        out = self.run(["docker", "ps", "-aq", "--filter", f"label=com.docker.compose.project={self.project}"])
        return out.stdout.decode().split()

    def exited_services(self) -> list[str]:
        out = self.compose("ps", "-a", "--status", "exited", "--format", "{{.Service}}", check=False)
        return out.stdout.decode().split()

    def existing_volumes(self) -> list[str]:
        out = self.run(["docker", "volume", "ls", "-q"]).stdout.decode().split()
        return [v for v in out if v in volume_names(self.project)]

    def cleanup(self) -> list[str]:
        """격리 프로젝트만 내리고 볼륨은 이름을 지정해 지운다(down -v 미사용)."""
        if self.project_containers():
            self.compose("down", "--remove-orphans", "--timeout", "20", timeout=180)
        removed = []
        for name in self.existing_volumes():
            self.run(["docker", "volume", "rm", name], timeout=60)
            removed.append(name)
        return removed


def protected_volumes_present(docker: Docker) -> list[str]:
    out = docker.run(["docker", "volume", "ls", "-q"]).stdout.decode().split()
    return [v for v in PROTECTED_VOLUMES if v in out]


class MemSampler(threading.Thread):
    """OCR 동안 notification 컨테이너 메모리(docker stats)를 표본 추출한다."""

    def __init__(self, docker: Docker, container: str):
        super().__init__(daemon=True)
        self.docker, self.container = docker, container
        self.samples: list[int] = []
        self._halt = threading.Event()

    def run(self) -> None:
        while not self._halt.is_set():
            try:
                out = self.docker.run(["docker", "stats", "--no-stream", "--format", "{{.MemUsage}}",
                                       self.container], timeout=30, check=False)
                value = parse_mem_usage(out.stdout.decode())
                if value:
                    self.samples.append(value)
            except subprocess.TimeoutExpired:
                pass

    def stop(self) -> Optional[int]:
        self._halt.set()
        self.join(timeout=35)
        return max(self.samples) if self.samples else None


def cgroup_memory(docker: Docker) -> dict[str, str]:
    out = docker.compose("exec", "-T", "notification-service", "sh", "-c",
                         "cat /sys/fs/cgroup/memory.peak 2>/dev/null; echo ---; "
                         "cat /sys/fs/cgroup/memory.events 2>/dev/null; echo ---; "
                         "cat /sys/fs/cgroup/memory.current", check=False).stdout.decode()
    peak, events, current = (out.split("---") + ["", "", ""])[:3]
    max_events = re.search(r"^max (\d+)", events, re.MULTILINE)
    oom = re.search(r"^oom_kill (\d+)", events, re.MULTILINE)
    return {"peak": peak.strip(), "current": current.strip(),
            "max_events": max_events.group(1) if max_events else "?",
            "oom_kill": oom.group(1) if oom else "?"}


def mib(value: Any) -> str:
    try:
        return f"{int(value) / 1024 / 1024:.0f} MiB"
    except (TypeError, ValueError):
        return "?"


# ─────────────────────────────────────────
# 합성 PDF 생성
# ─────────────────────────────────────────

def generate_pdfs(docker: Docker, work: Path, font: Path,
                  specs: list[PdfSpec]) -> tuple[dict[str, bytes], dict[str, int]]:
    lib = work / "lib"
    lib.mkdir()
    created = docker.run(["docker", "create", f"{PROJECT}-notification-service:local"]).stdout.decode().strip()
    try:
        docker.run(["docker", "cp", f"{created}:/app/app.jar", str(work / "app.jar")], timeout=120)
    finally:
        docker.run(["docker", "rm", created], check=False)
    with zipfile.ZipFile(work / "app.jar") as jar:
        names = [n for n in jar.namelist() if PDFBOX_LIBS.fullmatch(n)]
        if len({PDFBOX_LIBS.fullmatch(n).group(1) for n in names}) != 4:
            raise e2e.SetupError("notification 이미지에서 PDFBox jar를 찾지 못했습니다.")
        for name in names:
            (lib / Path(name).name).write_bytes(jar.read(name))
    (work / "app.jar").unlink()
    (work / "spec.tsv").write_text("\n".join(s.line() for s in specs) + "\n", encoding="utf-8")
    source = Path(__file__).resolve().with_name("WarrantyOcrPdfGen.java")
    gen = docker.run(["docker", "run", "--rm", "--network", "none",
                "--mount", f"type=bind,source={work},target=/work",
                "--mount", f"type=bind,source={font},target=/font.ttf,readonly",
                "--mount", f"type=bind,source={source},target=/gen/WarrantyOcrPdfGen.java,readonly",
                PDFGEN_IMAGE, "java", "-Dfile.encoding=UTF-8", "-cp", "/work/lib/*",
                "/gen/WarrantyOcrPdfGen.java", "/font.ttf", "/work", "/work/spec.tsv"], timeout=300)
    return {s.kind: (work / s.filename).read_bytes() for s in specs}, parse_text_layer(gen.stdout.decode())


def parse_text_layer(output: str) -> dict[str, int]:
    """생성기 출력 `generated<TAB>kind<TAB>bytes<TAB>textChars` → kind별 PDFBox 텍스트 글자 수."""
    counts = {}
    for line in output.splitlines():
        parts = line.strip().split("\t")
        if len(parts) == 4 and parts[0] == "generated" and parts[3].isdigit():
            counts[parts[1]] = int(parts[3])
    return counts


# ─────────────────────────────────────────
# 실측
# ─────────────────────────────────────────

def wait_until(what: str, probe: Callable[[], bool], timeout: float = READY_TIMEOUT, interval: float = 3.0,
               docker: Optional["Docker"] = None) -> float:
    started = time.monotonic()
    while True:
        exited = docker.exited_services() if docker else []
        if exited:
            raise e2e.SetupError(f"{what} 준비 중 컨테이너 종료: {', '.join(exited)}")
        try:
            if probe():
                return time.monotonic() - started
        except (e2e.SetupError, subprocess.TimeoutExpired):
            pass
        if time.monotonic() - started > timeout:
            raise e2e.SetupError(f"{what} 준비 시간 초과")
        time.sleep(interval)


def bootstrap_admin(docker: Docker, creds: e2e.Credentials) -> bool:
    result = docker.compose("exec", "-T", "auth-service", "java", "-jar", "/app/app.jar",
                            "--spring.main.web-application-type=none", "--eureka.client.enabled=false",
                            "--app.admin-bootstrap.enabled=true",
                            input_bytes=e2e.bootstrap_payload(creds), timeout=240, check=False, quiet=True)
    return result.returncode == 0


def upload(api: e2e.Api, site_id: int, spec: PdfSpec, content: bytes) -> e2e.Response:
    boundary = "bfqa" + uuid.uuid4().hex
    body = multipart_body({"siteId": str(site_id)}, "file", spec.filename, content, boundary)
    headers = {"Accept": "application/json", "Content-Type": f"multipart/form-data; boundary={boundary}",
               "Authorization": f"Bearer {api.token}"}
    status, raw = api.transport("POST", api.base_url + "/api/v1/warranties/upload", headers, body)
    return e2e.Response(status, e2e.parse_body(raw))


def download(api: e2e.Api, warranty_id: int, auth: bool = True) -> tuple[int, bytes]:
    headers = {"Accept": "application/pdf"}
    if auth:
        headers["Authorization"] = f"Bearer {api.token}"
    return api.transport("GET", f"{api.base_url}/api/v1/warranties/{warranty_id}/file", headers, None)


def scenario(api: e2e.Api, rec: e2e.Recorder, docker: Docker, specs: list[PdfSpec],
             pdfs: dict[str, bytes], prefix: str) -> list[dict]:
    site = api.ok("POST", "/api/v1/sites", {"siteName": f"{prefix} 보증 OCR", "memo": "로컬 OCR 합성 데이터"},
                  expected=(201,))
    container = docker.container_id("notification-service")
    summary = []
    for spec in specs:
        sc = f"OCR-{spec.kind}"
        content = pdfs[spec.kind]
        sha = hashlib.sha256(content).hexdigest()
        sampler = MemSampler(docker, container)
        sampler.start()
        started = time.monotonic()
        response = upload(api, site["id"], spec, content)
        if not rec.status(sc, "업로드 202", response, 202):
            sampler.stop()
            continue
        data = response.data or {}
        rec.check(sc, "접수 응답 PENDING·원본 보유", data.get("ocrStatus") == "PENDING" and data.get("hasUploadedPdf"),
                  f"ocrStatus={data.get('ocrStatus')} hasUploadedPdf={data.get('hasUploadedPdf')}")
        wid = data["id"]
        done, final = e2e.poll(lambda: api.ok("GET", f"/api/v1/warranties/{wid}"),
                               lambda d: (d or {}).get("ocrStatus") != "PENDING",
                               timeout=OCR_TIMEOUT, interval=0.5)
        elapsed = time.monotonic() - started
        peak_sample = sampler.stop()
        final = final or {}
        rec.check(sc, "OCR 종료(PENDING 해제)", done, f"{elapsed:.1f}s")
        mismatches, policy_note = evaluate_ocr(spec, final)
        rec.check(sc, "SUCCESS·추출 필드 일치", not mismatches, "; ".join(mismatches)[:200])
        rec.check(sc, "SUCCESS는 만료 미확정", final.get("expiryConfirmed") is False,
                  f"expiryConfirmed={final.get('expiryConfirmed')}")

        expiring = api.ok("GET", f"/api/v1/warranties/expiring?days={EXPIRING_DAYS}") or []
        rec.check(sc, "MANUAL 확인 전 만료 목록 제외", all(w.get("id") != wid for w in expiring))

        confirm = {"insuranceCompany": spec.insurer, "policyNumber": spec.policy,
                   "startDate": spec.start.isoformat(), "endDate": spec.end.isoformat()}
        updated = api.call("PUT", f"/api/v1/warranties/{wid}", confirm)
        rec.status(sc, "MANUAL 확인 저장 200", updated, 200)
        u = updated.data or {}
        rec.check(sc, "MANUAL·만료 확정", u.get("ocrStatus") == "MANUAL" and u.get("expiryConfirmed") is True
                  and u.get("policyNumber") == spec.policy,
                  f"ocrStatus={u.get('ocrStatus')} expiryConfirmed={u.get('expiryConfirmed')}")
        expiring = api.ok("GET", f"/api/v1/warranties/expiring?days={EXPIRING_DAYS}") or []
        row = next((w for w in expiring if w.get("id") == wid), None)
        want_days = (spec.end - dt.date.today()).days
        rec.check(sc, "MANUAL 확인 후 만료 목록 포함", row is not None and row.get("daysUntilExpiry") == want_days,
                  f"row={'있음' if row else '없음'} days={row.get('daysUntilExpiry') if row else None}≠{want_days}"
                  if not row or row.get("daysUntilExpiry") != want_days else "")

        status, _ = download(api, wid, auth=False)
        rec.check(sc, "무인증 원본 다운로드 401", status == 401, f"HTTP {status}")
        status, raw = download(api, wid)
        got_sha = hashlib.sha256(raw).hexdigest()
        rec.check(sc, "원본 다운로드 체크섬 동일", status == 200 and got_sha == sha,
                  f"HTTP {status} bytes={len(raw)}/{len(content)}")

        history = api.ok("GET", f"/api/v1/warranties/{wid}/history") or []
        actions = [h.get("action") for h in history]
        rec.check(sc, "감사 이력 UPLOADED→OCR_APPLIED→MANUAL_UPDATED",
                  actions == ["UPLOADED", "OCR_APPLIED", "MANUAL_UPDATED"], ",".join(map(str, actions)))
        summary.append({"kind": spec.kind, "bytes": len(content), "status": final.get("ocrStatus"),
                        "insurer": final.get("insuranceCompany"), "policy": final.get("policyNumber"),
                        "policy_note": policy_note, "start": final.get("startDate"), "end": final.get("endDate"),
                        "client_seconds": elapsed, "server_seconds": audit_seconds(history),
                        "peak_sample": peak_sample})
    return summary


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="보증보험 합성 PDF OCR 로컬 격리 실측")
    parser.add_argument("--gateway-port", type=int, default=18080)
    parser.add_argument("--font", type=Path, default=DEFAULT_FONT, help="한글 TrueType 폰트(.ttf)")
    parser.add_argument("--skip-build", action="store_true", help="이미 만든 bfqaocr-*:local 이미지 재사용")
    parser.add_argument("--keep", action="store_true", help="실측 후 격리 프로젝트를 남긴다(수동 정리 필요)")
    args = parser.parse_args(argv)

    base_url = e2e.check_local_target(f"http://127.0.0.1:{args.gateway_port}")
    if not args.font.is_file() or args.font.suffix.lower() != ".ttf":
        print(f"[SETUP] 한글 .ttf 폰트가 필요합니다: {args.font}")
        return 2
    repo = e2e.REPO_ROOT
    with tempfile.TemporaryDirectory(prefix="bfqaocr-") as tmp:
        work = Path(tmp)
        compose_file = work / "compose.json"
        compose_file.write_text(json.dumps(build_compose(repo, args.gateway_port), ensure_ascii=False),
                                encoding="utf-8")
        env = dict(os.environ)
        env["BFQA_DB_PASSWORD"] = secrets.token_urlsafe(24)
        env["BFQA_JWT_SECRET"] = secrets.token_hex(48)
        docker = Docker(compose_file, env)
        protected_before = protected_volumes_present(docker)
        rec = e2e.Recorder()
        summary: list[dict] = []
        cgroup: dict[str, str] = {}
        timings: dict[str, float] = {}
        exit_code = 2
        try:
            leftover = docker.cleanup()
            if leftover:
                print(f"[정리] 이전 격리 볼륨 삭제: {', '.join(leftover)}")
            if not args.skip_build:
                t0 = time.monotonic()
                for service in APP_SERVICES:  # 병렬 빌드의 외부 TLS 오류를 피하려고 순차 빌드
                    docker.compose("build", service, timeout=1800)
                timings["build"] = time.monotonic() - t0
            pdf_dir = work / "pdf"
            pdf_dir.mkdir()
            specs = make_specs(dt.date.today(), random.Random(secrets.randbits(32)))
            pdfs, text_layer = generate_pdfs(docker, pdf_dir, args.font.resolve(), specs)
            rec.check("S0", "텍스트 PDF는 PDFBox 텍스트 층 있음", text_layer.get("text", 0) > 50,
                      f"textChars={text_layer.get('text')}")
            rec.check("S0", "이미지 PDF는 텍스트 층 없음(Tesseract 경로)", text_layer.get("image") == 0,
                      f"textChars={text_layer.get('image')}")
            t0 = time.monotonic()
            docker.compose("up", "-d", timeout=600)
            api = e2e.Api(base_url)
            # 잘못된 자격으로 로그인 401이 Gateway→auth 경로가 살아 있다는 신호다.
            wait_until("Gateway·auth", lambda: api.call("POST", "/api/v1/auth/login",
                       {"loginId": "bfqa-probe", "password": "x" * 12}, auth=False).status in (400, 401),
                       docker=docker)
            creds = e2e.generate_credentials()
            if not bootstrap_admin(docker, creds):
                raise e2e.SetupError("QA 관리자 부트스트랩 실패")
            api.login(creds)
            wait_until("site·notification 라우팅",
                       lambda: api.call("GET", "/api/v1/sites").status == 200
                       and api.call("GET", "/api/v1/warranties").status == 200, docker=docker)
            timings["ready"] = time.monotonic() - t0
            rec.check("S0", "무인증 보증 목록 401", api.call("GET", "/api/v1/warranties", auth=False).status == 401)
            idle = cgroup_memory(docker)
            prefix = f"[QA-OCR-{dt.datetime.now():%Y%m%d%H%M%S}]"
            summary = scenario(api, rec, docker, specs, pdfs, prefix)
            cgroup = cgroup_memory(docker)
            cgroup["idle_current"] = idle.get("current", "")
            api.logout()
            exit_code = 1 if rec.failed else 0
        except (e2e.SetupError, e2e.ApiError, subprocess.TimeoutExpired) as exc:
            print(f"[SETUP] {exc}")
            if docker.project_containers():
                logs = docker.compose("logs", "--no-color", "--tail", "40", "notification-service",
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
            protected_after = protected_volumes_present(docker)
            print(f"[보호] 기존 볼륨 {len(protected_before)}개 → {len(protected_after)}개: {', '.join(protected_after)}")
            if protected_before != protected_after:
                exit_code = max(exit_code, 1)

    print(rec.render())
    for row in summary:
        server = f"{row['server_seconds']:.1f}s" if row["server_seconds"] is not None else "?"
        print(f"[{row['kind']}] {row['bytes']}B ocrStatus={row['status']} 보험사={row['insurer']} "
              f"증권번호={row['policy']}({row['policy_note']}) 기간={row['start']}~{row['end']} "
              f"소요(클라이언트 폴링)={row['client_seconds']:.1f}s 서버 기록={server} "
              f"표본 최대 메모리={mib(row['peak_sample'])}")
    if cgroup:
        print(f"[메모리] notification 대기 {mib(cgroup.get('idle_current'))}, 최종 {mib(cgroup.get('current'))}, "
              f"cgroup 최대 {mib(cgroup.get('peak'))} / 896 MiB, memory.max 접촉 {cgroup.get('max_events')}회, "
              f"oom_kill {cgroup.get('oom_kill')}")
    for name, seconds in timings.items():
        print(f"[시간] {name} {seconds:.0f}s")
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
