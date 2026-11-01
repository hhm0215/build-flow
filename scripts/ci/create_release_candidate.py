"""Record CI evidence for a tested main commit; never authorize deployment."""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess


REPOSITORY = "hhm0215/build-flow"
REQUIRED_RESULTS = {
    "backend": "RESULT_BACKEND",
    "auth-flyway": "RESULT_AUTH_FLYWAY",
    "auth-redis": "RESULT_AUTH_REDIS",
    "chat-flyway": "RESULT_CHAT_FLYWAY",
    "estimate-flyway": "RESULT_ESTIMATE_FLYWAY",
    "purchase-flyway": "RESULT_PURCHASE_FLYWAY",
    "tax-flyway": "RESULT_TAX_FLYWAY",
    "site-flyway": "RESULT_SITE_FLYWAY",
    "notification-flyway": "RESULT_NOTIFICATION_FLYWAY",
    "frontend": "RESULT_FRONTEND",
    "backup-synthetic": "RESULT_BACKUP_SYNTHETIC",
    "public-compose": "RESULT_PUBLIC_COMPOSE",
    "windows-readiness": "RESULT_WINDOWS_READINESS",
    "profit-reconcile": "RESULT_PROFIT_RECONCILE",
}
DEPLOYMENT_FILES = (
    "Dockerfile",
    "frontend/Dockerfile",
    "docker-compose.yml",
    "docker-compose.app.yml",
    "docker-compose.vps.yml",
    "docker-compose.public.yml",
    "deploy/caddy/Caddyfile",
)


def checked_file_hash(root: Path, relative: str, tracked_paths: set[str]) -> str:
    path = PurePosixPath(relative)
    if (
        relative not in DEPLOYMENT_FILES
        or path.is_absolute()
        or ".." in path.parts
        or relative not in tracked_paths
    ):
        raise ValueError(f"Unapproved or untracked release file: {relative}")

    root = root.absolute()
    if not root.is_dir() or root.is_symlink():
        raise ValueError("Invalid checkout root")
    candidate = root
    for part in path.parts:
        candidate = candidate / part
        if candidate.is_symlink():
            raise ValueError(f"Symlink in release file path: {relative}")
    if not candidate.is_file():
        raise ValueError(f"Invalid release file: {relative}")
    return hashlib.sha256(candidate.read_bytes()).hexdigest()


def build_candidate(
    env: dict[str, str], root: Path, checkout_sha: str, clean: bool, tracked_paths: set[str]
) -> dict:
    if env.get("GITHUB_EVENT_NAME") != "push" or env.get("GITHUB_REF") != "refs/heads/main":
        raise ValueError("Release evidence requires a main push run")
    if env.get("GITHUB_REPOSITORY") != REPOSITORY:
        raise ValueError("Unexpected source repository")

    source_sha = env.get("GITHUB_SHA", "")
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha) or source_sha != checkout_sha:
        raise ValueError("Checkout SHA does not match the main event SHA")
    if not clean:
        raise ValueError("Checkout has tracked modifications")

    run_id = env.get("GITHUB_RUN_ID", "")
    run_attempt = env.get("GITHUB_RUN_ATTEMPT", "")
    if not run_id.isdecimal() or int(run_id) < 1:
        raise ValueError("Invalid CI run ID")
    if not run_attempt.isdecimal() or int(run_attempt) < 1:
        raise ValueError("Invalid CI run attempt")

    results = {job: env.get(variable) for job, variable in REQUIRED_RESULTS.items()}
    if any(result != "success" for result in results.values()):
        raise ValueError("All required CI jobs must succeed")

    file_hashes = {
        relative: checked_file_hash(root, relative, tracked_paths)
        for relative in DEPLOYMENT_FILES
    }
    return {
        "schema_version": 1,
        "kind": "buildflow-release-candidate-evidence",
        "repository": REPOSITORY,
        "source_sha": source_sha,
        "ci": {
            "run_id": int(run_id),
            "run_attempt": int(run_attempt),
            "required_job_results": results,
        },
        "tracked_deployment_file_sha256": file_hashes,
        "deploy_authorized": False,
        "real_data_ready": False,
    }


def git_output(*args: str) -> str:
    result = subprocess.run(
        ["git", *args], check=True, capture_output=True, text=True, encoding="utf-8"
    )
    return result.stdout.strip()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    root = Path(git_output("rev-parse", "--show-toplevel"))
    checkout_sha = git_output("rev-parse", "HEAD")
    clean = not git_output("status", "--porcelain", "--untracked-files=no")
    tracked_paths = set(
        subprocess.run(
            ["git", "ls-files", "-z", "--", *DEPLOYMENT_FILES],
            check=True,
            capture_output=True,
        ).stdout.decode("utf-8").rstrip("\0").split("\0")
    )
    candidate = build_candidate(os.environ, root, checkout_sha, clean, tracked_paths)
    args.output.write_text(json.dumps(candidate, indent=2, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
