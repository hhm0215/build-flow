#!/usr/bin/env python3
"""Interactively bootstrap the first admin in a one-off private VPS container."""

import getpass
import json
import re
import subprocess
import sys
from pathlib import Path


def main() -> int:
    if not sys.stdin.isatty():
        print("Run this command in an interactive VPS terminal.", file=sys.stderr)
        return 2

    repo_root = Path(__file__).resolve().parents[2]
    if not (repo_root / ".env").is_file():
        print("Missing server .env. Run scripts/vps/init_env.py first.", file=sys.stderr)
        return 2

    login_id = input("Admin login ID (3-50 letters, digits, ., _, -): ").strip()
    name = input("Admin display name: ").strip()
    password = getpass.getpass("Admin password (minimum 9 characters): ")

    if not re.fullmatch(r"[A-Za-z0-9._-]{3,50}", login_id):
        print("Invalid login ID.", file=sys.stderr)
        return 2
    if not name or len(name) > 50:
        print("Display name must contain 1-50 characters.", file=sys.stderr)
        return 2
    if len(password) < 9:
        print("Password must contain at least 9 characters.", file=sys.stderr)
        return 2

    payload = json.dumps(
        {"loginId": login_id, "name": name, "password": password},
        ensure_ascii=False,
    ).encode("utf-8") + b"\n"
    password = ""

    compose = [
        "docker", "compose",
        "-f", "docker-compose.yml",
        "-f", "docker-compose.app.yml",
        "-f", "docker-compose.vps.yml",
    ]
    command = compose + [
        "run", "--rm", "--no-deps", "-T", "--entrypoint", "java",
        "auth-service", "-jar", "/app/app.jar",
        "--spring.main.web-application-type=none",
        "--eureka.client.enabled=false",
        "--app.admin-bootstrap.enabled=true",
    ]
    try:
        result = subprocess.run(command, input=payload, cwd=repo_root, check=False)
    except FileNotFoundError:
        print("Docker Compose is unavailable.", file=sys.stderr)
        return 2
    if result.returncode != 0:
        print("Admin setup failed. Confirm MySQL/Redis health and that no admin exists.", file=sys.stderr)
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
