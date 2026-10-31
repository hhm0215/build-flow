#!/usr/bin/env python3
"""Exercise the archive-before-commit race using isolated MySQL and real Feign HTTP."""
import concurrent.futures
import json
import subprocess
import sys
import tempfile
import time

from importlib.machinery import SourceFileLoader
from pathlib import Path

module = SourceFileLoader("profit_harness", str(Path(__file__).with_name("test-profit-reconcile-docker.py"))).load_module()
Harness, Failure, require = module.Harness, module.Failure, module.require
TAX_PORT = 8085
NOTIFICATION_PORT = 8086


def add_tax_service(h, temporary):
    """Add tax-service through an archive-only Compose override; the shared harness stays unchanged."""
    jars = [p.resolve() for p in (module.ROOT / "tax-service/build/libs").glob("*.jar")
            if not p.name.endswith("-plain.jar")]
    require(len(jars) == 1, "Build exactly one tax bootJar first")
    h.env["RECONCILE_TAX_JAR"] = jars[0].as_posix()
    h.jars.add(str(jars[0]))
    # Same isolation as the base app services: nonce label, no ports/volumes, readonly JAR mount.
    # tax-service has no outbox enable switch, so its dispatcher points at an unavailable broker.
    tax = {
        "image": "eclipse-temurin:17-jre-jammy",
        "labels": {module.LABEL: "${RECONCILE_NONCE:?Run the Python harness}"},
        "read_only": True,
        "tmpfs": ["/tmp"],
        "mem_limit": "768m",
        "environment": {
            "DB_HOST": "mysql", "DB_USERNAME": "root",
            "DB_PASSWORD": "${RECONCILE_DB_PASSWORD:?Run the Python harness}",
            "SPRING_CLOUD_CONFIG_ENABLED": "false", "EUREKA_CLIENT_ENABLED": "false",
            "SPRING_FLYWAY_ENABLED": "true", "SPRING_JPA_HIBERNATE_DDL_AUTO": "validate",
            "MANAGEMENT_HEALTH_REDIS_ENABLED": "false", "MANAGEMENT_TRACING_ENABLED": "false",
            "KAFKA_BOOTSTRAP_SERVERS": "unavailable:9092",
            "JAVA_TOOL_OPTIONS": "-Xms64m -Xmx384m -XX:ActiveProcessorCount=2", "TZ": "Asia/Seoul",
        },
        "command": ["java", "-jar", "/app.jar",
                    "--spring.cloud.openfeign.client.config.taxSiteReferenceClient.url=http://site:8083",
                    "--spring.cloud.openfeign.client.config.taxPurchaseEvidenceClient.url=http://purchase:8084"],
        "volumes": [{"type": "bind", "source": "${RECONCILE_TAX_JAR:?Build bootJar first}",
                     "target": "/app.jar", "read_only": True}],
    }
    override = Path(temporary) / "archive-race-tax.compose.json"
    override.write_text(json.dumps({"services": {"tax": tax}}), encoding="utf-8")
    h.prefix.extend(["-f", str(override)])


def add_notification_service(h, temporary):
    """Add notification-service (warranty direct create) through an archive-only Compose override."""
    jars = [p.resolve() for p in (module.ROOT / "notification-service/build/libs").glob("*.jar")
            if not p.name.endswith("-plain.jar")]
    require(len(jars) == 1, "Build exactly one notification bootJar first")
    h.env["RECONCILE_NOTIFICATION_JAR"] = jars[0].as_posix()
    h.jars.add(str(jars[0]))
    # Same isolation as tax: nonce label, no ports/volumes, readonly JAR mount, /tmp tmpfs only.
    # Direct create never touches the upload directory or Tesseract; listeners stay stopped, the outbox
    # dispatcher points at an unavailable broker, and the daily expiry/retention crons are disabled.
    notification = {
        "image": "eclipse-temurin:17-jre-jammy",
        "labels": {module.LABEL: "${RECONCILE_NONCE:?Run the Python harness}"},
        "read_only": True,
        "tmpfs": ["/tmp"],
        "mem_limit": "768m",
        "environment": {
            "DB_HOST": "mysql", "DB_USERNAME": "root",
            "DB_PASSWORD": "${RECONCILE_DB_PASSWORD:?Run the Python harness}",
            "SPRING_CLOUD_CONFIG_ENABLED": "false", "EUREKA_CLIENT_ENABLED": "false",
            "SPRING_FLYWAY_ENABLED": "true", "SPRING_JPA_HIBERNATE_DDL_AUTO": "validate",
            "MANAGEMENT_HEALTH_REDIS_ENABLED": "false", "MANAGEMENT_TRACING_ENABLED": "false",
            "KAFKA_BOOTSTRAP_SERVERS": "unavailable:9092", "SPRING_KAFKA_LISTENER_AUTO_STARTUP": "false",
            "UPLOAD_DIR": "/tmp/warranties",
            "JAVA_TOOL_OPTIONS": "-Xms64m -Xmx384m -XX:ActiveProcessorCount=2", "TZ": "Asia/Seoul",
        },
        "command": ["java", "-jar", "/app.jar",
                    "--spring.cloud.openfeign.client.config.notificationSiteReferenceClient.url=http://site:8083",
                    "--app.warranty.scheduler-cron=-", "--app.warranty.retention-purge-cron=-"],
        "volumes": [{"type": "bind", "source": "${RECONCILE_NOTIFICATION_JAR:?Build bootJar first}",
                     "target": "/app.jar", "read_only": True}],
    }
    override = Path(temporary) / "archive-race-notification.compose.json"
    override.write_text(json.dumps({"services": {"notification": notification}}), encoding="utf-8")
    h.prefix.extend(["-f", str(override)])


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


def exercise_tax(h):
    h.phase = "tax archive race"
    h.sql("CREATE DATABASE buildflow_tax CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;")
    h.compose("up", "-d", "--no-deps", "tax", timeout=180)
    h.wait_health("tax", port=TAX_PORT)
    site = h.api("site", "/api/v1/sites", "POST", {"siteName": "[QA] tax archive race"}, 201)["id"]
    body = {"siteId": site, "type": "SALES", "supplyAmount": 1000, "taxAmount": 100,
            "counterparty": "[QA] 거래처", "issueDate": "2026-01-01"}
    lock = subprocess.Popen(["docker", *h.prefix, "exec", "-T", "mysql", "sh", "-c",
                             'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --unbuffered --skip-column-names'],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                            text=True, encoding="utf-8", env=h.env)
    pool = concurrent.futures.ThreadPoolExecutor(max_workers=2)
    try:
        lock.stdin.write("LOCK TABLES buildflow_tax.tax_invoices WRITE; SELECT 'READY';\n")
        lock.stdin.flush()
        ready = pool.submit(lock.stdout.readline).result(timeout=20).strip()
        require(ready == "READY", "Synthetic MySQL table lock was not acquired")
        pending = pool.submit(h.api, "tax", "/api/v1/taxes", "POST", body, 400, TAX_PORT)
        until = time.monotonic() + 8
        blocked = False
        while time.monotonic() < until and not pending.done():
            blocked = int(h.sql("SELECT COUNT(*) FROM information_schema.PROCESSLIST "
                                "WHERE DB='buildflow_tax' AND STATE='Waiting for table metadata lock';") or 0) > 0
            if blocked:
                break
            time.sleep(0.2)
        require(blocked, "Tax invoice INSERT did not reach the MySQL lock after its first site check")
        h.api("site", f"/api/v1/sites/{site}/archive", "PATCH")
        lock.stdin.write("UNLOCK TABLES;\n")
        lock.stdin.flush()
        pending.result(timeout=15)
        require(h.sql("SELECT COUNT(*) FROM buildflow_tax.tax_invoices;") == "0",
                "Archived-site tax invoice was committed")
        require(h.sql("SELECT COUNT(*) FROM buildflow_tax.outbox_events;") == "0",
                "Archived-site outbox event was committed")
        print("[PASS] MySQL-blocked tax invoice rechecked archived site via Feign and rolled back invoice and outbox")
    finally:
        if lock.stdin:
            lock.stdin.close()
        try:
            lock.wait(timeout=5)
        except subprocess.TimeoutExpired:
            lock.terminate()
            lock.wait(timeout=5)
        pool.shutdown(wait=True, cancel_futures=True)
    active = h.api("site", "/api/v1/sites", "POST", {"siteName": "[QA] tax control"}, 201)["id"]
    h.api("tax", "/api/v1/taxes", "POST", dict(body, siteId=active), 201, TAX_PORT)
    require(h.sql("SELECT COUNT(*) FROM buildflow_tax.tax_invoices;") == "1",
            "Active-site control tax invoice did not commit")
    require(h.sql("SELECT COUNT(*) FROM buildflow_tax.outbox_events;") == "1",
            "Active-site control tax outbox did not commit")
    print("[PASS] Active-site control committed tax invoice and outbox")


def exercise_warranty(h):
    h.phase = "warranty archive race"
    h.sql("CREATE DATABASE buildflow_notification CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;")
    h.compose("up", "-d", "--no-deps", "notification", timeout=180)
    h.wait_health("notification", port=NOTIFICATION_PORT)
    site = h.api("site", "/api/v1/sites", "POST", {"siteName": "[QA] warranty archive race"}, 201)["id"]
    # Direct registration (not PDF upload). The far end date keeps it out of any expiry scan.
    body = {"siteId": site, "insuranceCompany": "[QA] 보증보험", "policyNumber": "QA-0001",
            "startDate": "2026-01-01", "endDate": "2030-12-31", "coverageAmount": 1000}
    lock = subprocess.Popen(["docker", *h.prefix, "exec", "-T", "mysql", "sh", "-c",
                             'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --unbuffered --skip-column-names'],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                            text=True, encoding="utf-8", env=h.env)
    pool = concurrent.futures.ThreadPoolExecutor(max_workers=2)
    try:
        lock.stdin.write("LOCK TABLES buildflow_notification.defect_warranties WRITE; SELECT 'READY';\n")
        lock.stdin.flush()
        ready = pool.submit(lock.stdout.readline).result(timeout=20).strip()
        require(ready == "READY", "Synthetic MySQL table lock was not acquired")
        pending = pool.submit(h.api, "notification", "/api/v1/warranties", "POST", body, 400, NOTIFICATION_PORT)
        until = time.monotonic() + 8
        blocked = False
        while time.monotonic() < until and not pending.done():
            blocked = int(h.sql("SELECT COUNT(*) FROM information_schema.PROCESSLIST "
                                "WHERE DB='buildflow_notification' AND STATE='Waiting for table metadata lock';") or 0) > 0
            if blocked:
                break
            time.sleep(0.2)
        require(blocked, "Warranty INSERT did not reach the MySQL lock after its first site check")
        h.api("site", f"/api/v1/sites/{site}/archive", "PATCH")
        lock.stdin.write("UNLOCK TABLES;\n")
        lock.stdin.flush()
        pending.result(timeout=15)
        require(h.sql("SELECT COUNT(*) FROM buildflow_notification.defect_warranties;") == "0",
                "Archived-site warranty was committed")
        require(h.sql("SELECT COUNT(*) FROM buildflow_notification.warranty_audit_events;") == "0",
                "Archived-site warranty audit event was committed")
        require(h.sql("SELECT COUNT(*) FROM buildflow_notification.outbox_events;") == "0",
                "Archived-site warranty outbox event was committed")
        print("[PASS] MySQL-blocked warranty rechecked archived site via Feign and rolled back warranty and audit")
    finally:
        if lock.stdin:
            lock.stdin.close()
        try:
            lock.wait(timeout=5)
        except subprocess.TimeoutExpired:
            lock.terminate()
            lock.wait(timeout=5)
        pool.shutdown(wait=True, cancel_futures=True)
    active = h.api("site", "/api/v1/sites", "POST", {"siteName": "[QA] warranty control"}, 201)["id"]
    h.api("notification", "/api/v1/warranties", "POST", dict(body, siteId=active), 201, NOTIFICATION_PORT)
    require(h.sql("SELECT COUNT(*) FROM buildflow_notification.defect_warranties;") == "1",
            "Active-site control warranty did not commit")
    require(h.sql("SELECT COUNT(*) FROM buildflow_notification.warranty_audit_events;") == "1",
            "Active-site control warranty audit event did not commit")
    print("[PASS] Active-site control committed warranty and audit event")


def service_diagnostics(h, service):
    # Synthetic fixtures only; bounded error lines with the generated DB password redacted.
    try:
        logs = h.compose("logs", "--no-color", "--tail", "100", service, cleanup=True)
        selected = [line for line in logs.splitlines() if " ERROR " in line or "Caused by:" in line]
        for line in selected[-10:]:
            safe = line.replace(h.env["RECONCILE_DB_PASSWORD"], "[redacted]")
            print(f"[DIAG {service}] {safe[:800]}", file=sys.stderr)
    except Failure:
        print(f"[DIAG {service}] Bounded diagnostics unavailable", file=sys.stderr)


def main():
    failed = False
    with tempfile.TemporaryDirectory(prefix="bf-archive-race-") as temporary:
        h = None
        try:
            h = Harness(temporary)
            add_tax_service(h, temporary)
            add_notification_service(h, temporary)
            exercise(h)
            exercise_estimate(h)
            exercise_tax(h)
            exercise_warranty(h)
        except (Failure, OSError, ValueError, KeyError, concurrent.futures.TimeoutError) as error:
            failed = True
            print(f"[FAIL] {error}", file=sys.stderr)
        finally:
            if h is not None:
                if failed and h.started:
                    h.diagnostics()
                    service_diagnostics(h, "tax")
                    service_diagnostics(h, "notification")
                try:
                    h.cleanup()
                except (Failure, OSError, ValueError, KeyError):
                    failed = True
                    print("[FAIL] Isolated cleanup could not be verified", file=sys.stderr)
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
