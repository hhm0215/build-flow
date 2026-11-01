"""로컬 업무 엑셀 가져오기 공통부: 원본 읽기, 대상 호스트 검사, 적재 기록(ledger), Gateway API 클라이언트.

- 원본 파일은 바이트로 한 번만 읽는다(쓰기·이동·이름 변경 없음). 해시와 파싱은 같은 바이트에서 한다.
- API 대상은 로컬 개발 호스트만 허용한다. 운영 VPS·공개 도메인은 거부한다.
- 토큰은 환경변수로만 받고 출력·저장하지 않는다. 비밀번호 입력 기능은 없다.
- 표준 라이브러리만 사용한다.
"""

from __future__ import annotations

import csv
import datetime as dt
import hashlib
import io
import json
import os
import re
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Iterable, Optional

ALLOWED_HOSTS = {"localhost", "127.0.0.1", "::1"}
DEFAULT_BASE_URL = "http://localhost:3000"
REPO_ROOT = Path(__file__).resolve().parents[2]
ACCESS_ENV = "BUILDFLOW_ACCESS_TOKEN"
REFRESH_ENV = "BUILDFLOW_REFRESH_TOKEN"
MAX_SOURCE_BYTES = 30 * 1024 * 1024
HTTP_TIMEOUT_SECONDS = 30


class ImportSetupError(Exception):
    """대상·자격·경로 준비 실패. 종료 코드 2."""


# ─────────────────────────────────────────
# 원본 읽기
# ─────────────────────────────────────────

@dataclass(frozen=True)
class SourceFile:
    path: Path
    data: bytes
    sha256: str
    mtime_date: dt.date


def read_source(path: Path) -> SourceFile:
    """원본을 읽기 전용으로 한 번 읽는다. 수정 시각은 로컬 날짜로 변환한다."""
    stat = path.stat()
    if stat.st_size > MAX_SOURCE_BYTES:
        raise ValueError(f"파일이 너무 큽니다({stat.st_size} bytes)")
    with open(path, "rb") as handle:
        data = handle.read(MAX_SOURCE_BYTES + 1)
    if len(data) > MAX_SOURCE_BYTES:
        raise ValueError("읽는 중 파일 크기가 상한을 넘었습니다")
    mtime_date = dt.datetime.fromtimestamp(stat.st_mtime).date()
    return SourceFile(path=path, data=data, sha256=hashlib.sha256(data).hexdigest(), mtime_date=mtime_date)


def is_hidden(path: Path) -> bool:
    if path.name.startswith((".", "~$")):
        return True
    attrs = getattr(path.stat(), "st_file_attributes", 0)
    return bool(attrs & 0x2)  # FILE_ATTRIBUTE_HIDDEN


def collect_files(targets: Iterable[Path], suffixes: tuple[str, ...], recursive: bool) -> tuple[list[Path], int]:
    """대상 경로에서 확장자가 맞는 파일을 모은다. 숨김·임시 잠금 파일은 건너뛰고 개수만 돌려준다."""
    found: list[Path] = []
    skipped_hidden = 0
    for target in targets:
        if target.is_file():
            candidates = [target]
        elif target.is_dir():
            iterator = target.rglob("*") if recursive else target.iterdir()
            candidates = sorted(p for p in iterator if p.is_file())
        else:
            raise ImportSetupError(f"경로를 찾을 수 없습니다: {target}")
        for path in candidates:
            if path.suffix.lower() not in suffixes:
                continue
            if is_hidden(path):
                skipped_hidden += 1
                continue
            if path not in found:
                found.append(path)
    return found, skipped_hidden


# ─────────────────────────────────────────
# 대상 호스트 검사
# ─────────────────────────────────────────

def validate_base_url(base_url: str) -> str:
    parsed = urllib.parse.urlparse(base_url)
    if parsed.scheme not in {"http", "https"}:
        raise ImportSetupError("--base-url은 http(s) 주소여야 합니다")
    host = (parsed.hostname or "").lower()
    if host not in ALLOWED_HOSTS:
        raise ImportSetupError("로컬 개발 호스트(localhost/127.0.0.1/::1)만 허용합니다. 운영 서버에는 쓰지 않습니다")
    if parsed.path not in {"", "/"} or parsed.query or parsed.fragment or parsed.username or parsed.password:
        raise ImportSetupError("--base-url에는 경로·쿼리·계정 정보를 넣지 않습니다")
    return f"{parsed.scheme}://{parsed.netloc}"


# ─────────────────────────────────────────
# 적재 기록 (ledger)
# ─────────────────────────────────────────

def default_ledger_path() -> Path:
    home = Path(os.environ.get("USERPROFILE") or Path.home())
    return home / "Documents" / "Dev" / "build-flow-backups" / "import-ledger.jsonl"


def _is_within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def outside_repo(path: Path, label: str) -> Path:
    """실데이터(현장명·적재 결과)가 담길 파일은 저장소 밖에만 둔다."""
    resolved = path.expanduser().resolve()
    if _is_within(resolved, REPO_ROOT.resolve()):
        raise ImportSetupError(f"{label}은(는) 저장소 밖 경로에 둡니다")
    return resolved


# ─────────────────────────────────────────
# 현장명 정규화·별칭
# ─────────────────────────────────────────

def site_key(name: str) -> str:
    """같은 현장 후보를 묶는 키: 전각 영숫자·기호를 반각으로, 공백·괄호·기호를 없애고 소문자로.

    글자 오타(예: 받침이 다른 글자)는 고치지 않는다. 그런 경우는 --site-alias로 사용자가 확정한다.
    browser_apply.js의 siteKey와 같은 규칙이다.
    """
    chars = []
    for ch in str(name or ""):
        code = ord(ch)
        if 0xFF01 <= code <= 0xFF5E:
            ch = chr(code - 0xFEE0)
        elif code == 0x3000:
            ch = " "
        if ch.isspace() or unicodedata.category(ch)[0] in {"P", "S"}:
            continue
        chars.append(ch.lower())
    return "".join(chars)


ALIAS_HEADERS = {"원본", "원본현장명", "source", "from"}


def _collapse(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip()


def load_site_aliases(path: Path) -> dict[str, tuple[str, str]]:
    """별칭 파일 → {정규화 키: (원본 현장명, 적재 현장명)}.

    .json은 {"원본": "적재"} 객체, 그 밖(.csv/.txt)은 `원본,적재` 두 열(UTF-8, 첫 줄 머리글·# 주석 허용).
    원본은 정규화 키로 비교하므로 띄어쓰기·괄호·기호 차이까지 한 줄로 덮는다.
    """
    resolved = outside_repo(path, "현장 별칭 파일(--site-alias)")
    try:
        raw = resolved.read_bytes().decode("utf-8-sig")
    except (OSError, UnicodeDecodeError) as exc:
        raise ImportSetupError(f"현장 별칭 파일을 읽을 수 없습니다({exc.__class__.__name__})") from None
    pairs: list[tuple[int, str, str]] = []
    if resolved.suffix.lower() == ".json":
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            raise ImportSetupError("현장 별칭 JSON 형식이 올바르지 않습니다") from None
        if not isinstance(data, dict) or not all(isinstance(k, str) and isinstance(v, str) for k, v in data.items()):
            raise ImportSetupError('현장 별칭 JSON은 {"원본 현장명": "적재 현장명"} 객체여야 합니다')
        pairs = [(index, k, v) for index, (k, v) in enumerate(data.items(), start=1)]
    else:
        for line_no, row in enumerate(csv.reader(io.StringIO(raw)), start=1):
            cells = [c.strip() for c in row]
            if not any(cells) or cells[0].startswith("#"):
                continue
            if not pairs and re.sub(r"\s", "", cells[0]).lower() in ALIAS_HEADERS:
                continue
            if len(cells) < 2:
                raise ImportSetupError(f"현장 별칭 {line_no}행: '원본,적재' 두 열이 필요합니다")
            pairs.append((line_no, cells[0], cells[1]))
    aliases: dict[str, tuple[str, str]] = {}
    for line_no, source, target in pairs:
        source, target = _collapse(source), _collapse(target)
        key = site_key(source)
        if not key or not target or len(target) > 200:
            raise ImportSetupError(f"현장 별칭 {line_no}번째 항목: 원본·적재 이름이 비었거나 200자를 넘습니다")
        if key in aliases and aliases[key][1] != target:
            raise ImportSetupError(f"현장 별칭 {line_no}번째 항목: 같은 원본(공백·기호 무시)에 적재 이름이 둘입니다")
        aliases[key] = (source, target)
    return aliases


class Ledger:
    """JSONL 적재 기록. 키 = (대상 base_url, 종류, 파일 SHA-256, 시트). 저장소 안 경로는 거부한다."""

    def __init__(self, path: Path):
        resolved = outside_repo(path, "적재 기록(--ledger)")
        self.path = resolved
        self.records: list[dict[str, Any]] = []
        if resolved.exists():
            with open(resolved, "r", encoding="utf-8") as handle:
                for line_no, line in enumerate(handle, start=1):
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        self.records.append(json.loads(line))
                    except json.JSONDecodeError as exc:
                        raise ImportSetupError(f"적재 기록 {line_no}행을 읽을 수 없습니다") from exc

    @staticmethod
    def key(base_url: str, kind: str, sha256: str, sheet: str) -> tuple[str, str, str, str]:
        return (base_url, kind, sha256, sheet)

    def find(self, base_url: str, kind: str, sha256: str, sheet: str) -> Optional[dict[str, Any]]:
        wanted = self.key(base_url, kind, sha256, sheet)
        match = None
        for record in self.records:
            if self.key(record.get("baseUrl", ""), record.get("kind", ""),
                        record.get("sha256", ""), record.get("sheet", "")) == wanted:
                match = record  # 마지막 기록(확정 갱신 포함)이 이긴다
        return match

    def append(self, record: dict[str, Any]) -> None:
        record = {**record, "recordedAt": dt.datetime.now().astimezone().isoformat(timespec="seconds")}
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with open(self.path, "a", encoding="utf-8") as handle:
            handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")
            handle.flush()
            os.fsync(handle.fileno())
        self.records.append(record)


# ─────────────────────────────────────────
# Gateway API 클라이언트
# ─────────────────────────────────────────

class ApiError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(f"HTTP {status}: {message}")
        self.status = status
        self.message = message


Opener = Callable[[urllib.request.Request, float], Any]


# 환경변수 프록시(HTTP_PROXY 등)로 토큰이 새지 않게 프록시를 끄고, 리다이렉트도 따르지 않는다.
class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D401
        return None


_DIRECT = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect())


def _default_opener(request: urllib.request.Request, timeout: float):
    return _DIRECT.open(request, timeout=timeout)  # 로컬 호스트만 허용됨


class ApiClient:
    """Bearer 토큰으로 Gateway를 호출한다. 401이면 리프레시 토큰으로 한 번 회전한다.

    토큰은 메모리에만 두며 예외 메시지·repr에 넣지 않는다.
    """

    def __init__(self, base_url: str, access_token: str, refresh_token: Optional[str] = None,
                 opener: Opener = _default_opener):
        self.base_url = validate_base_url(base_url)
        self._access = access_token
        self._refresh = refresh_token
        self._opener = opener
        self.rotated = False

    def __repr__(self) -> str:
        return f"ApiClient(base_url={self.base_url!r})"

    @classmethod
    def from_env(cls, base_url: str, opener: Opener = _default_opener) -> "ApiClient":
        access = os.environ.get(ACCESS_ENV, "").strip()
        if not access:
            raise ImportSetupError(f"--apply에는 환경변수 {ACCESS_ENV}가 필요합니다(값은 출력하지 않습니다)")
        refresh = os.environ.get(REFRESH_ENV, "").strip() or None
        return cls(base_url, access, refresh, opener)

    def _send(self, method: str, path: str, body: Any, token: Optional[str]) -> tuple[int, Any]:
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(self.base_url + path, data=data, method=method)
        request.add_header("Accept", "application/json")
        if data is not None:
            request.add_header("Content-Type", "application/json")
        if token:
            request.add_header("Authorization", "Bearer " + token)
        try:
            with self._opener(request, HTTP_TIMEOUT_SECONDS) as response:
                status = response.status
                raw = response.read()
        except urllib.error.HTTPError as exc:
            status = exc.code
            raw = exc.read() if exc.fp else b""
        except urllib.error.URLError as exc:
            raise ApiError(0, f"연결 실패({exc.reason.__class__.__name__})") from None
        try:
            payload = json.loads(raw.decode("utf-8")) if raw else None
        except (UnicodeDecodeError, json.JSONDecodeError):
            payload = None
        return status, payload

    def _rotate(self) -> bool:
        if not self._refresh:
            return False
        status, payload = self._send("POST", "/api/v1/auth/refresh", {"refreshToken": self._refresh}, None)
        data = payload.get("data") if isinstance(payload, dict) else None
        if status != 200 or not isinstance(data, dict) or not data.get("accessToken"):
            return False
        self._access = data["accessToken"]
        self._refresh = data.get("refreshToken") or self._refresh
        self.rotated = True
        return True

    def request(self, method: str, path: str, body: Any = None) -> Any:
        status, payload = self._send(method, path, body, self._access)
        if status == 401 and self._rotate():
            status, payload = self._send(method, path, body, self._access)
        if 200 <= status < 300 and isinstance(payload, dict) and payload.get("success", True):
            return payload.get("data")
        message = ""
        if isinstance(payload, dict):
            message = str(payload.get("error") or payload.get("message") or "")
        if status == 401:
            message = "인증 실패 — 로그인 후 새 액세스 토큰을 환경변수로 넣어 다시 실행하세요"
        raise ApiError(status, message[:300] or "응답 오류")
