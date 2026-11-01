#!/usr/bin/env python3
"""화면 QA용 로컬 격리 전체 스택 (frontend Nginx → Gateway → 전 서비스, 빈 볼륨).

사용:
  python -I -X utf8 scripts/qa/ui_stack.py up [--skip-build] [--no-chat]
  python -I -X utf8 scripts/qa/ui_stack.py status
  python -I -X utf8 scripts/qa/ui_stack.py down

- 별도 Compose 프로젝트 `bfqaui`(빈 볼륨 `bfqaui_*`, 고정 container_name·공용 네트워크 없음)로
  mysql·redis·kafka·eureka·gateway·auth·estimate·site·purchase·tax·notification·chat + frontend를 띄운다.
  config-server는 띄우지 않는다(각 서비스 자체 docker 프로필 yml, `SPRING_CLOUD_CONFIG_ENABLED=false`).
- 공개 포트는 루프백만: frontend `127.0.0.1:13080`(화면), Gateway `127.0.0.1:18081`(준비 확인·API 직접 호출).
- 이미지는 이 작업 트리에서 `bfqaui-*:local`로 순차 빌드한다(`build-flow-*` 태그는 건드리지 않음).
- DB 비밀번호·JWT 비밀은 실행 메모리에서만 무작위 생성한다. QA 관리자(`AdminBootstrapRunner`)의
  자격 증명은 출력하지 않고 저장소 무시 경로 `.local/qa-ui-admin.json`에만 저장한다.
- 합성 시드는 넣지 않는다. 빈 상태에서 화면으로 입력해 본다.
- `down`은 bfqaui 컨테이너·네트워크를 내리고(`-v` 미사용) 볼륨은 이름을 지정해 지운 뒤 자격 파일을 지운다.
  기존 `build-flow` 프로젝트와 볼륨 4개는 조회만 한다.
종료 코드 0=성공, 1=점검 실패/잔여 있음, 2=준비 실패.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import secrets
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Optional

sys.path.insert(0, str(Path(__file__).resolve().parent))
import local_e2e as e2e  # noqa: E402
import warranty_ocr_e2e as ocr  # noqa: E402  (격리 Compose·Docker·부트스트랩 도우미 재사용)

PROJECT = "bfqaui"
FRONTEND_PORT = 13080
GATEWAY_PORT = 18081
STATE_DIR = e2e.REPO_ROOT / ".local" / "qa-ui"
COMPOSE_FILE = STATE_DIR / "compose.json"
CREDENTIAL_FILE = e2e.REPO_ROOT / ".local" / "qa-ui-admin.json"
READY_TIMEOUT = 900.0
# 비밀값 없이 down/status만 할 때 Compose 보간용 자리표시자(컨테이너에 전달되지 않음)
PLACEHOLDER_ENV = {"BFQA_DB_PASSWORD": "unused-for-down", "BFQA_JWT_SECRET": "unused-for-down"}
JAVA_OPTS = "-Xms64m -Xmx384m"
# 로그인 후 200을 확인할 서비스별 목록 API (Gateway 라우트 → 각 서비스)
ROUTE_PROBES = ("/api/v1/sites", "/api/v1/clients", "/api/v1/estimates", "/api/v1/purchases",
                "/api/v1/taxes", "/api/v1/notifications", "/api/v1/warranties")
CHAT_PROBE = "/api/v1/chat/availability"


# ─────────────────────────────────────────
# 순수 함수 (단위 테스트 대상)
# ─────────────────────────────────────────

def app_services(with_chat: bool) -> list[str]:
    services = ["eureka-server", "gateway-server", "auth-service", "estimate-service", "site-service",
                "purchase-service", "tax-service", "notification-service"]
    if with_chat:
        services.append("chat-service")
    return services


def build_images(with_chat: bool) -> list[str]:
    return [*app_services(with_chat), "frontend"]


def image_name(service: str) -> str:
    return f"{PROJECT}-{service}:local"


def frontend_url(port: int = FRONTEND_PORT) -> str:
    return f"http://127.0.0.1:{port}"


def build_compose(repo: Path, frontend_port: int = FRONTEND_PORT, gateway_port: int = GATEWAY_PORT,
                  with_chat: bool = True) -> dict:
    """warranty_ocr_e2e의 격리 정의(인프라·eureka·gateway·auth·site·notification)를 bfqaui로 확장한다."""
    spec = ocr.build_compose(repo, gateway_port, project=PROJECT)
    services = spec["services"]
    root = repo.as_posix()
    healthy = {"condition": "service_healthy"}
    base = {"SPRING_PROFILES_ACTIVE": "docker", "MANAGEMENT_TRACING_ENABLED": "false",
            "EUREKA_CLIENT_SERVICEURL_DEFAULTZONE": "http://eureka-server:8761/eureka/",
            "SPRING_CLOUD_CONFIG_ENABLED": "false",
            "DB_HOST": "mysql", "DB_USERNAME": "root", "DB_PASSWORD": "${BFQA_DB_PASSWORD:?run the script}",
            "REDIS_HOST": "redis", "JAVA_TOOL_OPTIONS": JAVA_OPTS}
    kafka = {"KAFKA_BOOTSTRAP_SERVERS": "kafka:9092"}
    ollama = {"OLLAMA_URL": "http://host.docker.internal:11434"}

    def app(service: str, environment: dict, depends: dict) -> dict:
        return {"build": {"context": root, "dockerfile": "Dockerfile", "args": {"SERVICE_NAME": service}},
                "image": image_name(service), "environment": environment, "depends_on": depends}

    infra = {"mysql": healthy, "kafka": healthy, "redis": healthy}
    # 사용자 API 키를 격리 스택에 넘기지 않는다(빈 값 고정). AI 파싱은 로컬 Ollama가 있을 때만 동작.
    services["estimate-service"] = app("estimate-service", {**base, **kafka, **ollama, "CLAUDE_API_KEY": ""}, infra)
    services["purchase-service"] = app("purchase-service", {**base, **kafka}, infra)
    services["tax-service"] = app("tax-service", {**base, **kafka}, infra)
    if with_chat:
        services["chat-service"] = app("chat-service", {**base, **ollama},
                                       {"mysql": healthy, "redis": healthy})
    for name in ("auth-service", "site-service"):
        services[name]["environment"].update({"SPRING_CLOUD_CONFIG_ENABLED": "false", "JAVA_TOOL_OPTIONS": JAVA_OPTS})
    # site-service의 대시보드 AI 요약도 호스트 Ollama를 쓴다(기본값 localhost는 컨테이너 안에서 닿지 않음).
    services["site-service"]["environment"].update(ollama)
    services["notification-service"]["environment"]["SPRING_CLOUD_CONFIG_ENABLED"] = "false"
    services["gateway-server"]["environment"].update({
        "JAVA_TOOL_OPTIONS": JAVA_OPTS,
        "CORS_ALLOWED_ORIGINS": f"http://127.0.0.1:{frontend_port},http://localhost:{frontend_port}"})
    services["frontend"] = {
        "build": {"context": f"{root}/frontend", "dockerfile": "Dockerfile"},
        "image": image_name("frontend"),
        # frontend/nginx.conf가 /api/를 gateway-server:8080으로 프록시한다(같은 Compose 네트워크의 서비스 이름).
        "ports": [f"127.0.0.1:{frontend_port}:80"],
        "depends_on": {"gateway-server": {"condition": "service_started"}},
    }
    return spec


def published_ports(spec: dict) -> list[str]:
    return sorted(p for svc in spec["services"].values() for p in svc.get("ports", []))


def format_credentials_json(creds: e2e.Credentials, url: str, gateway: str,
                            now: Optional[dt.datetime] = None) -> str:
    created = (now or dt.datetime.now().astimezone()).isoformat(timespec="seconds")
    return json.dumps({"loginId": creds.login_id, "password": creds.password, "frontendUrl": url,
                       "gatewayUrl": gateway, "project": PROJECT, "createdAt": created,
                       "note": "BuildFlow 화면 QA 격리 스택 전용. 커밋·공유 금지. down 시 삭제."},
                      ensure_ascii=False, indent=2) + "\n"


def parse_credentials_json(text: str) -> e2e.Credentials:
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        raise e2e.SetupError("QA UI 자격 파일 형식이 올바르지 않습니다.") from exc
    login_id = str(data.get("loginId", "")) if isinstance(data, dict) else ""
    password = str(data.get("password", "")) if isinstance(data, dict) else ""
    if not e2e.LOGIN_ID_PATTERN.fullmatch(login_id) or len(password) < 9:
        raise e2e.SetupError("QA UI 자격 파일 형식이 올바르지 않습니다.")
    return e2e.Credentials(login_id, password)


def looks_like_spa(status: int, body: bytes) -> bool:
    return status == 200 and b'id="root"' in body


# ─────────────────────────────────────────
# 부수 효과
# ─────────────────────────────────────────

def write_private(path: Path, text: str) -> None:
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)


def http_get(url: str, timeout: float = 10) -> tuple[int, bytes]:
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # 루프백 요청을 프록시로 보내지 않는다
    try:
        with opener.open(urllib.request.Request(url), timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read()
    except (urllib.error.URLError, OSError):
        return 0, b""


class UiDocker(ocr.Docker):
    def networks(self) -> list[str]:
        out = self.run(["docker", "network", "ls", "-q", "--filter",
                        f"label=com.docker.compose.project={self.project}"], check=False)
        return out.stdout.decode().split()

    def container_rows(self) -> list[str]:
        out = self.run(["docker", "ps", "-a", "--filter", f"label=com.docker.compose.project={self.project}",
                        "--format", "{{.Names}}\t{{.Status}}\t{{.Ports}}"], check=False)
        return sorted(line for line in out.stdout.decode().splitlines() if line.strip())

    def teardown(self) -> list[str]:
        """compose 파일이 있으면 compose down(-v 없음), 없으면 라벨로 정리. 볼륨은 이름 지정 삭제."""
        if self.project_containers() or self.networks():
            if self.compose_file.is_file():
                self.compose("down", "--remove-orphans", "--timeout", "20", timeout=240)
            else:
                ids = self.project_containers()
                if ids:
                    self.run(["docker", "rm", "-f", *ids], timeout=180)
                for net in self.networks():
                    self.run(["docker", "network", "rm", net], timeout=60, check=False)
        removed = []
        for name in self.existing_volumes():
            self.run(["docker", "volume", "rm", name], timeout=60)
            removed.append(name)
        return removed

    def missing_images(self, names: list[str]) -> list[str]:
        return [n for n in names
                if self.run(["docker", "image", "inspect", n], check=False, quiet=True).returncode != 0]


def remove_local_state() -> None:
    CREDENTIAL_FILE.unlink(missing_ok=True)
    COMPOSE_FILE.unlink(missing_ok=True)
    try:
        STATE_DIR.rmdir()
    except OSError:
        pass


def report_protected(docker: UiDocker, before: list[str]) -> bool:
    after = ocr.protected_volumes_present(docker)
    print(f"[보호] 기존 볼륨 {len(before)}개 → {len(after)}개: {', '.join(after)}")
    return before == after


def print_failure_logs(docker: UiDocker) -> None:
    for service in docker.exited_services()[:3]:
        logs = docker.compose("logs", "--no-color", "--tail", "30", service, check=False)
        print(f"--- {service} 로그(끝 30줄) ---")
        print(logs.stdout.decode("utf-8", "replace")[-2500:])


def cmd_up(args: argparse.Namespace) -> int:
    url = e2e.check_local_target(frontend_url(args.frontend_port))
    gateway = e2e.check_local_target(f"http://127.0.0.1:{args.gateway_port}")
    with_chat = not args.no_chat
    spec = build_compose(e2e.REPO_ROOT, args.frontend_port, args.gateway_port, with_chat)
    env = dict(os.environ)
    env.update(PLACEHOLDER_ENV)
    docker = UiDocker(COMPOSE_FILE, env, PROJECT)
    protected_before = ocr.protected_volumes_present(docker)

    # 이전 bfqaui 스택이 있으면 이전 compose 파일로 먼저 내리고 깨끗한 빈 볼륨에서 다시 시작한다.
    leftover = docker.teardown()
    if leftover:
        print(f"[정리] 이전 bfqaui 볼륨 삭제: {', '.join(leftover)}")
    remove_local_state()
    STATE_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    COMPOSE_FILE.write_text(json.dumps(spec, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n")

    env["BFQA_DB_PASSWORD"] = secrets.token_urlsafe(24)
    env["BFQA_JWT_SECRET"] = secrets.token_hex(48)
    docker.env = env
    try:
        if args.skip_build:
            missing = docker.missing_images([image_name(s) for s in build_images(with_chat)])
            if missing:
                raise e2e.SetupError(f"--skip-build인데 이미지가 없습니다: {', '.join(missing)}")
        else:
            for service in build_images(with_chat):  # 병렬 빌드의 외부 TLS 오류를 피하려고 순차 빌드
                print(f"[빌드] {image_name(service)}", flush=True)
                docker.compose("build", service, timeout=1800)
        print("[기동] docker compose up -d", flush=True)
        docker.compose("up", "-d", timeout=900)
        api = e2e.Api(gateway)
        seconds = ocr.wait_until(
            "Gateway·auth",
            lambda: api.call("POST", "/api/v1/auth/login", {"loginId": "bfqa-probe", "password": "x" * 12},
                             auth=False).status in (400, 401),
            timeout=READY_TIMEOUT, interval=5.0, docker=docker)
        print(f"[준비] Gateway·auth {seconds:.0f}s", flush=True)

        creds = e2e.generate_credentials()
        url_note = frontend_url(args.frontend_port)
        # 부트스트랩 성공 후 저장 실패로 비밀번호를 잃지 않도록 먼저 저장하고, 실패하면 지운다.
        write_private(CREDENTIAL_FILE, format_credentials_json(creds, url_note, gateway))
        if not ocr.bootstrap_admin(docker, creds):
            raise e2e.SetupError("QA 관리자 부트스트랩 실패")
        api.login(creds)
        probes = list(ROUTE_PROBES) + ([CHAT_PROBE] if with_chat else [])
        seconds = ocr.wait_until("서비스 라우팅",
                                 lambda: all(api.call("GET", p).status == 200 for p in probes),
                                 timeout=READY_TIMEOUT, interval=5.0, docker=docker)
        print(f"[준비] 서비스 {len(probes)}개 라우팅 200 ({seconds:.0f}s)", flush=True)
        api.logout()

        status, body = http_get(url + "/")
        if not looks_like_spa(status, body):
            raise e2e.SetupError(f"frontend 응답 이상: HTTP {status}")
        front = e2e.Api(url)
        front.login(creds)  # Nginx → Gateway → auth 경로 로그인 200
        if front.call("GET", "/api/v1/sites").status != 200:
            raise e2e.SetupError("frontend 경유 현장 목록 조회 실패")
        front.logout()
    except (e2e.SetupError, e2e.ApiError, subprocess.TimeoutExpired) as exc:
        print(f"[SETUP] {exc}")
        if docker.project_containers():
            print_failure_logs(docker)
        if not args.keep_on_failure:
            removed = docker.teardown()
            remove_local_state()
            print(f"[정리] 실패로 bfqaui 정리, 볼륨 삭제 {len(removed)}개")
        report_protected(docker, protected_before)
        return 2

    print("[확인] frontend 200 · frontend 경유 로그인 200 · 현장 목록 200")
    print(f"화면: {url}")
    print(f"Gateway(루프백): {gateway}")
    print(f"QA 관리자 자격 파일: {CREDENTIAL_FILE} (값은 출력하지 않음)")
    print("정리: python -I -X utf8 scripts/qa/ui_stack.py down")
    return 0 if report_protected(docker, protected_before) else 1


def cmd_status(args: argparse.Namespace) -> int:
    env = dict(os.environ)
    env.update(PLACEHOLDER_ENV)
    docker = UiDocker(COMPOSE_FILE, env, PROJECT)
    rows = docker.container_rows()
    for row in rows:
        print(row)
    print(f"[컨테이너] {len(rows)}개, 볼륨 {len(docker.existing_volumes())}개, "
          f"자격 파일 {'있음' if CREDENTIAL_FILE.is_file() else '없음'}")
    if not rows:
        return 1
    url = frontend_url(args.frontend_port)
    status, body = http_get(url + "/")
    api_status, _ = http_get(url + "/api/v1/sites")
    print(f"[점검] {url} → HTTP {status}, 무인증 /api/v1/sites → HTTP {api_status}")
    return 0 if looks_like_spa(status, body) and api_status == 401 else 1


def cmd_down(args: argparse.Namespace) -> int:
    env = dict(os.environ)
    env.update(PLACEHOLDER_ENV)
    docker = UiDocker(COMPOSE_FILE, env, PROJECT)
    before = ocr.protected_volumes_present(docker)
    removed = docker.teardown()
    remove_local_state()
    remaining = docker.project_containers() + docker.networks() + docker.existing_volumes()
    print(f"[정리] bfqaui 컨테이너·네트워크 down, 볼륨 삭제 {len(removed)}개, 잔여 {len(remaining)}개, "
          f"자격 파일 {'남음' if CREDENTIAL_FILE.exists() else '삭제'}")
    ok = report_protected(docker, before)
    return 0 if ok and not remaining and not CREDENTIAL_FILE.exists() else 1


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="BuildFlow 화면 QA 로컬 격리 전체 스택")
    sub = parser.add_subparsers(dest="command", required=True)
    up = sub.add_parser("up", help="빈 볼륨으로 전체 스택 기동(이전 bfqaui 스택은 먼저 정리)")
    up.add_argument("--skip-build", action="store_true", help="이미 만든 bfqaui-*:local 이미지 재사용")
    up.add_argument("--no-chat", action="store_true", help="chat-service 제외")
    up.add_argument("--keep-on-failure", action="store_true", help="준비 실패 시 조사용으로 남긴다")
    up.add_argument("--gateway-port", type=int, default=GATEWAY_PORT)
    for p in (up, sub.add_parser("status", help="컨테이너·화면 응답 확인")):
        p.add_argument("--frontend-port", type=int, default=FRONTEND_PORT)
    sub.add_parser("down", help="bfqaui 컨테이너·네트워크·볼륨·자격 파일 삭제")
    args = parser.parse_args(argv)
    try:
        return {"up": cmd_up, "status": cmd_status, "down": cmd_down}[args.command](args)
    except e2e.SetupError as exc:
        print(f"[SETUP] {exc}")
        return 2


if __name__ == "__main__":
    sys.exit(main())
