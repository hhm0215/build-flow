#!/usr/bin/env python3
"""Exercise the archive-before-commit race using isolated MySQL and real Feign HTTP."""
import concurrent.futures
import subprocess
import sys
import tempfile
import time

from importlib.machinery import SourceFileLoader
from pathlib import Path

module = SourceFileLoader("profit_harness", str(Path(__file__).with_name("test-profit-reconcile-docker.py"))).load_module()
Harness, Failure, require = module.Harness, module.Failure, module.require


def exercise(h):
    h.validate_config()
    h.started = True
    h.phase = "archive-race infrastructure"
    h.compose("up", "-d", "--wait", "--wait-timeout", "150", "mysql", "probe", timeout=240)
    h.sql("CREATE DATABASE buildflow_site CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
          "CREATE DATABASE buildflow_purchase CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
          "CREATE DATABASE buildflow_estimate CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;")
    h.compose("up", "-d", "--no-deps", "site", "purchase", "estimate", timeout=180)
    h.wait_health("site")
    h.wait_health("purchase")
    h.wait_health("estimate")
    site = h.api("site", "/api/v1/sites", "POST", {"siteName": "[QA] archive race"}, 201)["id"]
    body = {"siteId": site, "itemName": "synthetic", "quantity": 1, "unitPrice": 100}

    # The first Feign validation completes before INSERT blocks on this isolated table lock.
    # The lock session emits READY after acquiring the lock; no timing-only sleeps decide ordering.
    lock = subprocess.Popen(["docker", *h.prefix, "exec", "-T", "mysql", "sh", "-c",
                             'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --unbuffered --skip-column-names'],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                            text=True, encoding="utf-8", env=h.env)
    pool = concurrent.futures.ThreadPoolExecutor(max_workers=2)
    try:
        lock.stdin.write("LOCK TABLES buildflow_purchase.purchases WRITE; SELECT 'READY';\n")
        lock.stdin.flush()
        ready = pool.submit(lock.stdout.readline).result(timeout=20).strip()
        require(ready == "READY", "Synthetic MySQL table lock was not acquired")
        pending = pool.submit(h.api, "purchase", "/api/v1/purchases", "POST", body, 400)
        until = time.monotonic() + 8
        blocked = False
        while time.monotonic() < until and not pending.done():
            blocked = int(h.sql("SELECT COUNT(*) FROM information_schema.PROCESSLIST "
                                "WHERE DB='buildflow_purchase' AND STATE='Waiting for table metadata lock';") or 0) > 0
            if blocked:
                break
            time.sleep(0.2)
        require(blocked, "Purchase INSERT did not reach the MySQL lock after its first site check")
        h.api("site", f"/api/v1/sites/{site}/archive", "PATCH")
        lock.stdin.write("UNLOCK TABLES;\n")
        lock.stdin.flush()
        pending.result(timeout=15)
        require(h.sql("SELECT COUNT(*) FROM buildflow_purchase.purchases;") == "0",
                "Archived-site purchase was committed")
        require(h.sql("SELECT COUNT(*) FROM buildflow_purchase.outbox_events;") == "0",
                "Archived-site outbox event was committed")
        print("[PASS] MySQL-blocked purchase rechecked archived site via Feign and rolled back purchase and outbox")
    finally:
        if lock.stdin:
            lock.stdin.close()
        try:
            lock.wait(timeout=5)
        except subprocess.TimeoutExpired:
            lock.terminate()
            lock.wait(timeout=5)
        pool.shutdown(wait=True, cancel_futures=True)
    active = h.api("site", "/api/v1/sites", "POST", {"siteName": "[QA] active control"}, 201)["id"]
    h.api("purchase", "/api/v1/purchases", "POST", dict(body, siteId=active), 201)
    require(h.sql("SELECT COUNT(*) FROM buildflow_purchase.purchases;") == "1",
            "Active-site control purchase did not commit")
    require(h.sql("SELECT COUNT(*) FROM buildflow_purchase.outbox_events;") == "1",
            "Active-site control outbox did not commit")
    print("[PASS] Active-site control committed purchase and outbox")


def exercise_estimate(h):
    site = h.api("site", "/api/v1/sites", "POST", {"siteName": "[QA] estimate archive race"}, 201)["id"]
    body = {"siteId": site, "title": "synthetic", "estimateDate": "2026-01-01",
            "items": [{"itemName": "work", "unit": "ea", "quantity": 1, "unitPrice": 100}]}
    lock = subprocess.Popen(["docker", *h.prefix, "exec", "-T", "mysql", "sh", "-c",
                             'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --unbuffered --skip-column-names'],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                            text=True, encoding="utf-8", env=h.env)
    pool = concurrent.futures.ThreadPoolExecutor(max_workers=2)
    try:
        lock.stdin.write("LOCK TABLES buildflow_estimate.estimates WRITE; SELECT 'READY';\n")
        lock.stdin.flush()
        ready = pool.submit(lock.stdout.readline).result(timeout=20).strip()
        require(ready == "READY", "Synthetic MySQL table lock was not acquired")
        pending = pool.submit(h.api, "estimate", "/api/v1/estimates", "POST", body, 400)
        until = time.monotonic() + 8
        blocked = False
        while time.monotonic() < until and not pending.done():
            blocked = int(h.sql("SELECT COUNT(*) FROM information_schema.PROCESSLIST "
                                "WHERE DB='buildflow_estimate' AND STATE='Waiting for table metadata lock';") or 0) > 0
            if blocked:
                break
            time.sleep(0.2)
        require(blocked, "Estimate INSERT did not reach the MySQL lock after its first site check")
        h.api("site", f"/api/v1/sites/{site}/archive", "PATCH")
        lock.stdin.write("UNLOCK TABLES;\n")
        lock.stdin.flush()
        pending.result(timeout=15)
        require(h.sql("SELECT COUNT(*) FROM buildflow_estimate.estimates;") == "0",
                "Archived-site estimate was committed")
        require(h.sql("SELECT COUNT(*) FROM buildflow_estimate.estimate_items;") == "0",
                "Archived-site estimate items were committed")
        require(h.sql("SELECT COUNT(*) FROM buildflow_estimate.outbox_events;") == "0",
                "Archived-site outbox event was committed")
        print("[PASS] MySQL-blocked estimate rechecked archived site via Feign and rolled back estimate and items")
    finally:
        if lock.stdin:
            lock.stdin.close()
        try:
            lock.wait(timeout=5)
        except subprocess.TimeoutExpired:
            lock.terminate()
            lock.wait(timeout=5)
        pool.shutdown(wait=True, cancel_futures=True)
    active = h.api("site", "/api/v1/sites", "POST", {"siteName": "[QA] estimate control"}, 201)["id"]
    h.api("estimate", "/api/v1/estimates", "POST", dict(body, siteId=active), 201)
    require(h.sql("SELECT COUNT(*) FROM buildflow_estimate.estimates;") == "1",
            "Active-site control estimate did not commit")
    print("[PASS] Active-site control estimate committed")


def main():
    failed = False
    with tempfile.TemporaryDirectory(prefix="bf-archive-race-") as temporary:
        h = None
        try:
            h = Harness(temporary)
            exercise(h)
            exercise_estimate(h)
        except (Failure, OSError, ValueError, KeyError, concurrent.futures.TimeoutError) as error:
            failed = True
            print(f"[FAIL] {error}", file=sys.stderr)
        finally:
            if h is not None:
                if failed and h.started:
                    h.diagnostics()
                try:
                    h.cleanup()
                except (Failure, OSError, ValueError, KeyError):
                    failed = True
                    print("[FAIL] Isolated cleanup could not be verified", file=sys.stderr)
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
