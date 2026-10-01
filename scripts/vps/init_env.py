#!/usr/bin/env python3
"""Create a fresh, owner-only .env for a private VPS pilot; never overwrite one."""

import os
import secrets
from pathlib import Path


def main() -> None:
    repo_root = Path(__file__).resolve().parents[2]
    env_path = repo_root / ".env"
    if env_path.exists():
        print("Existing .env preserved. Check its owner and permissions manually.")
        return

    database_password = secrets.token_hex(24)
    jwt_secret = secrets.token_hex(64)
    content = (
        "# Generated on the VPS. Do not commit, copy, or share this file.\n"
        f"DB_ROOT_PASSWORD={database_password}\n"
        "DB_USERNAME=root\n"
        f"DB_PASSWORD={database_password}\n"
        f"JWT_SECRET={jwt_secret}\n"
        "CLAUDE_API_KEY=\n"
    )
    fd = os.open(env_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as env_file:
            env_file.write(content)
    except BaseException:
        env_path.unlink(missing_ok=True)
        raise
    print("Created owner-only .env with new random secrets (values hidden).")


if __name__ == "__main__":
    main()
