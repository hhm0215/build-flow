#!/usr/bin/env python3
"""Exercise the archive-before-commit race using isolated MySQL and real Feign HTTP."""
import concurrent.futures
import json
import subprocess
import sys
import tempfile
import time
from decimal import Decimal

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


def mutation_race(h, schema, row_table, row_id, site, request, label, lock_table="outbox_events"):
    """Hold an existing-document mutation after its row lock and first site check, then archive the site.

    Every mutation takes SELECT ... FOR UPDATE on row_table before the first site check, so a LOCK TABLES
    on row_table would stop it before that check. lock_table is instead the table of the first statement
    after the check: outbox_events for purchase update/delete and estimate confirm (Spring Data merge of an
    assigned-id OutboxEvent), tax_payments for tax update/payment cancel, estimate_items for estimate update
    (lazy item collection load) and warranty_audit_events for warranty update (IDENTITY audit INSERT).
    The proof is the waiting session itself holding the target row's InnoDB X lock (performance_schema),
    which also shows the row-lock SELECT was not stopped by lock_table and excludes outbox dispatchers.
    """
    require(h.sql("SELECT @@performance_schema;") == "1", "performance_schema is required for the lock proof")
    lock = subprocess.Popen(["docker", *h.prefix, "exec", "-T", "mysql", "sh", "-c",
                             'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --unbuffered --skip-column-names'],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                            text=True, encoding="utf-8", env=h.env)
    pool = concurrent.futures.ThreadPoolExecutor(max_workers=2)
    try:
        lock.stdin.write(f"LOCK TABLES {schema}.{lock_table} WRITE; SELECT 'READY';\n")
        lock.stdin.flush()
        ready = pool.submit(lock.stdout.readline).result(timeout=20).strip()
        require(ready == "READY", f"Synthetic MySQL {lock_table} table lock was not acquired")
        pending = pool.submit(h.api, *request)
        until = time.monotonic() + 8
        blocked = False
        while time.monotonic() < until and not pending.done():
            blocked = int(h.sql(
                "SELECT COUNT(DISTINCT p.ID) FROM information_schema.PROCESSLIST p "
                "JOIN performance_schema.threads t ON t.PROCESSLIST_ID = p.ID "
                "JOIN performance_schema.data_locks l ON l.THREAD_ID = t.THREAD_ID "
                f"WHERE p.DB='{schema}' AND p.STATE='Waiting for table metadata lock' "
                f"AND p.INFO LIKE '%{lock_table}%' "
                f"AND l.OBJECT_SCHEMA='{schema}' AND l.OBJECT_NAME='{row_table}' AND l.INDEX_NAME='PRIMARY' "
                f"AND l.LOCK_TYPE='RECORD' AND l.LOCK_MODE LIKE 'X%' AND l.LOCK_DATA='{int(row_id)}';") or 0) > 0
            if blocked:
                break
            time.sleep(0.2)
        require(blocked, f"{label} did not wait on the {lock_table} lock while holding its row lock")
        h.api("site", f"/api/v1/sites/{site}/archive", "PATCH")
        lock.stdin.write("UNLOCK TABLES;\n")
        lock.stdin.flush()
        pending.result(timeout=15)
    finally:
        if lock.stdin:
            lock.stdin.close()
        try:
            lock.wait(timeout=5)
        except subprocess.TimeoutExpired:
            lock.terminate()
            lock.wait(timeout=5)
        pool.shutdown(wait=True, cancel_futures=True)


def exercise_purchase_update(h):
    h.phase = "purchase update archive race"
    site = h.api("site", "/api/v1/sites", "POST", {"siteName": "[QA] purchase update race"}, 201)["id"]
    created = {"siteId": site, "itemName": "[QA] update race", "quantity": 1, "unitPrice": 100}
    purchase = int(h.api("purchase", "/api/v1/purchases", "POST", created, 201)["id"])
    row = f"SELECT unit_price, event_revision FROM buildflow_purchase.purchases WHERE id={purchase};"
    before_row = h.sql(row)
    outbox = "SELECT COUNT(*) FROM buildflow_purchase.outbox_events;"
    before_outbox = int(h.sql(outbox))
    change = {"itemName": "[QA] update race", "quantity": 1, "unitPrice": 200}
    mutation_race(h, "buildflow_purchase", "purchases", purchase, site,
                  ("purchase", f"/api/v1/purchases/{purchase}", "PUT", change, 409), "Purchase update")
    require(h.sql(row) == before_row, "Archived-site purchase update changed the stored row")
    require(int(h.sql(outbox)) == before_outbox, "Archived-site purchase.updated outbox was committed")
    print("[PASS] MySQL-held purchase update rechecked archived site via Feign: 409, row and outbox unchanged")
    active = h.api("site", "/api/v1/sites", "POST", {"siteName": "[QA] purchase update control"}, 201)["id"]
    control = int(h.api("purchase", "/api/v1/purchases", "POST", dict(created, siteId=active), 201)["id"])
    control_outbox = int(h.sql(outbox))
    h.api("purchase", f"/api/v1/purchases/{control}", "PUT", change, 200)
    require(Decimal(h.sql(f"SELECT unit_price FROM buildflow_purchase.purchases WHERE id={control};"))
            == Decimal("200"), "Active-site control purchase update did not commit")
    require(int(h.sql(outbox)) == control_outbox + 1, "Active-site control purchase.updated outbox did not commit")
    print("[PASS] Active-site control committed purchase update and outbox")


def exercise_estimate_confirm(h):
    h.phase = "estimate confirm archive race"
    site = h.api("site", "/api/v1/sites", "POST", {"siteName": "[QA] estimate confirm race"}, 201)["id"]
    created = {"siteId": site, "title": "[QA] confirm race", "estimateDate": "2026-01-01",
               "items": [{"itemName": "work", "unit": "ea", "quantity": 1, "unitPrice": 100}]}
    estimate = int(h.api("estimate", "/api/v1/estimates", "POST", created, 201)["id"])
    status = f"SELECT status FROM buildflow_estimate.estimates WHERE id={estimate};"
    require(h.sql(status) == "DRAFT", "Synthetic estimate did not start as DRAFT")
    outbox = "SELECT COUNT(*) FROM buildflow_estimate.outbox_events;"
    before_outbox = int(h.sql(outbox))
    mutation_race(h, "buildflow_estimate", "estimates", estimate, site,
                  ("estimate", f"/api/v1/estimates/{estimate}/confirm", "PATCH", None, 409), "Estimate confirm")
    require(h.sql(status) == "DRAFT", "Archived-site estimate confirm was committed")
    require(int(h.sql(outbox)) == before_outbox, "Archived-site estimate.parsed outbox was committed")
    print("[PASS] MySQL-held estimate confirm rechecked archived site via Feign: 409, DRAFT and outbox unchanged")
    active = h.api("site", "/api/v1/sites", "POST", {"siteName": "[QA] estimate confirm control"}, 201)["id"]
    control = int(h.api("estimate", "/api/v1/estimates", "POST", dict(created, siteId=active), 201)["id"])
    control_outbox = int(h.sql(outbox))
    h.api("estimate", f"/api/v1/estimates/{control}/confirm", "PATCH")
    require(h.sql(f"SELECT status FROM buildflow_estimate.estimates WHERE id={control};") == "CONFIRMED",
            "Active-site control estimate confirm did not commit")
    require(int(h.sql(outbox)) == control_outbox + 1, "Active-site control estimate.parsed outbox did not commit")
    print("[PASS] Active-site control committed estimate confirm and outbox")


def exercise_purchase_delete(h):
    h.phase = "purchase delete archive race"
    site = h.api("site", "/api/v1/sites", "POST", {"siteName": "[QA] purchase delete race"}, 201)["id"]
    created = {"siteId": site, "itemName": "[QA] delete race", "quantity": 1, "unitPrice": 100}
    purchase = int(h.api("purchase", "/api/v1/purchases", "POST", created, 201)["id"])
    row = f"SELECT unit_price, event_revision FROM buildflow_purchase.purchases WHERE id={purchase};"
    before_row = h.sql(row)
    outbox = "SELECT COUNT(*) FROM buildflow_purchase.outbox_events;"
    before_outbox = int(h.sql(outbox))
    # delete: row lock -> first check -> revision bump (dirty only) -> purchase.deleted outbox merge (first SQL)
    mutation_race(h, "buildflow_purchase", "purchases", purchase, site,
                  ("purchase", f"/api/v1/purchases/{purchase}", "DELETE", None, 409), "Purchase delete")
    require(h.sql(row) == before_row, "Archived-site purchase delete removed or changed the stored row")
    require(int(h.sql(outbox)) == before_outbox, "Archived-site purchase.deleted outbox was committed")
    print("[PASS] MySQL-held purchase delete rechecked archived site via Feign: 409, row and outbox unchanged")
    active = h.api("site", "/api/v1/sites", "POST", {"siteName": "[QA] purchase delete control"}, 201)["id"]
    control = int(h.api("purchase", "/api/v1/purchases", "POST", dict(created, siteId=active), 201)["id"])
    control_outbox = int(h.sql(outbox))
    h.api("purchase", f"/api/v1/purchases/{control}", "DELETE")
    require(h.sql(f"SELECT COUNT(*) FROM buildflow_purchase.purchases WHERE id={control};") == "0",
            "Active-site control purchase delete did not commit")
    require(int(h.sql(outbox)) == control_outbox + 1, "Active-site control purchase.deleted outbox did not commit")
    print("[PASS] Active-site control committed purchase delete and outbox")


def exercise_tax_update(h):
    h.phase = "tax update archive race"
    site = h.api("site", "/api/v1/sites", "POST", {"siteName": "[QA] tax update race"}, 201)["id"]
    created = {"siteId": site, "type": "SALES", "supplyAmount": 1000, "taxAmount": 100,
               "counterparty": "[QA] 거래처", "issueDate": "2026-01-01"}
    invoice = int(h.api("tax", "/api/v1/taxes", "POST", created, 201, TAX_PORT)["id"])
    row = f"SELECT supply_amount, tax_amount, total_amount FROM buildflow_tax.tax_invoices WHERE id={invoice};"
    before_row = h.sql(row)
    outbox = "SELECT COUNT(*) FROM buildflow_tax.outbox_events;"
    before_outbox = int(h.sql(outbox))
    change = {"type": "SALES", "supplyAmount": 2000, "taxAmount": 200,
              "counterparty": "[QA] 거래처", "issueDate": "2026-01-01"}
    # update: row lock -> first check -> active payment sum on tax_payments (first SQL) -> update -> flush
    mutation_race(h, "buildflow_tax", "tax_invoices", invoice, site,
                  ("tax", f"/api/v1/taxes/{invoice}", "PUT", change, 409, TAX_PORT), "Tax invoice update",
                  lock_table="tax_payments")
    require(h.sql(row) == before_row, "Archived-site tax invoice update changed the stored row")
    require(int(h.sql(outbox)) == before_outbox, "Archived-site tax invoice update wrote an outbox event")
    print("[PASS] MySQL-held tax invoice update rechecked archived site via Feign: 409, row and outbox unchanged")
    active = h.api("site", "/api/v1/sites", "POST", {"siteName": "[QA] tax update control"}, 201)["id"]
    control = int(h.api("tax", "/api/v1/taxes", "POST", dict(created, siteId=active), 201, TAX_PORT)["id"])
    h.api("tax", f"/api/v1/taxes/{control}", "PUT", change, 200, TAX_PORT)
    require(Decimal(h.sql(f"SELECT supply_amount FROM buildflow_tax.tax_invoices WHERE id={control};"))
            == Decimal("2000"), "Active-site control tax invoice update did not commit")
    print("[PASS] Active-site control committed tax invoice update")


def exercise_payment_cancel(h):
    h.phase = "tax payment cancel archive race"
    site = h.api("site", "/api/v1/sites", "POST", {"siteName": "[QA] payment cancel race"}, 201)["id"]
    created = {"siteId": site, "type": "SALES", "supplyAmount": 1000, "taxAmount": 100,
               "counterparty": "[QA] 거래처", "issueDate": "2026-01-01"}
    payment = {"amount": 500, "paymentDate": "2026-01-02", "memo": "[QA] 부분 입금"}
    invoice = int(h.api("tax", "/api/v1/taxes", "POST", created, 201, TAX_PORT)["id"])
    paid = int(h.api("tax", f"/api/v1/taxes/{invoice}/payments", "POST", payment, 201, TAX_PORT)["id"])
    ledger = f"SELECT canceled_at IS NULL, amount FROM buildflow_tax.tax_payments WHERE id={paid};"
    state = f"SELECT payment_confirmed, payment_date FROM buildflow_tax.tax_invoices WHERE id={invoice};"
    before_ledger, before_state = h.sql(ledger), h.sql(state)
    require(before_ledger.split("\t")[0] == "1", "Synthetic payment did not start active")
    outbox = "SELECT COUNT(*) FROM buildflow_tax.outbox_events;"
    before_outbox = int(h.sql(outbox))
    # cancel: invoice row lock -> first check -> partial payment skips the legacy backfill
    # -> payment lookup on tax_payments (first SQL) -> cancel flush -> invoice state flush
    mutation_race(h, "buildflow_tax", "tax_invoices", invoice, site,
                  ("tax", f"/api/v1/taxes/{invoice}/payments/{paid}/cancel", "PATCH",
                   {"reason": "[QA] 취소"}, 409, TAX_PORT), "Tax payment cancel", lock_table="tax_payments")
    require(h.sql(ledger) == before_ledger, "Archived-site payment cancel was committed")
    require(h.sql(state) == before_state, "Archived-site payment cancel changed the invoice state")
    require(int(h.sql(outbox)) == before_outbox, "Archived-site payment cancel wrote an outbox event")
    print("[PASS] MySQL-held payment cancel rechecked archived site via Feign: 409, ledger and invoice unchanged")
    active = h.api("site", "/api/v1/sites", "POST", {"siteName": "[QA] payment cancel control"}, 201)["id"]
    control = int(h.api("tax", "/api/v1/taxes", "POST", dict(created, siteId=active), 201, TAX_PORT)["id"])
    control_paid = int(h.api("tax", f"/api/v1/taxes/{control}/payments", "POST", payment, 201, TAX_PORT)["id"])
    h.api("tax", f"/api/v1/taxes/{control}/payments/{control_paid}/cancel", "PATCH", {"reason": "[QA] 취소"},
          200, TAX_PORT)
    require(h.sql(f"SELECT canceled_at IS NOT NULL FROM buildflow_tax.tax_payments WHERE id={control_paid};")
            == "1", "Active-site control payment cancel did not commit")
    print("[PASS] Active-site control committed payment cancel")


def exercise_estimate_update(h):
    h.phase = "estimate update archive race"
    site = h.api("site", "/api/v1/sites", "POST", {"siteName": "[QA] estimate update race"}, 201)["id"]
    created = {"siteId": site, "title": "[QA] update race", "estimateDate": "2026-01-01",
               "items": [{"itemName": "work", "unit": "ea", "quantity": 1, "unitPrice": 100}]}
    estimate = int(h.api("estimate", "/api/v1/estimates", "POST", created, 201)["id"])
    row = f"SELECT title, total_amount, status FROM buildflow_estimate.estimates WHERE id={estimate};"
    items = ("SELECT COUNT(*), COALESCE(SUM(unit_price), 0) FROM buildflow_estimate.estimate_items "
             f"WHERE estimate_id={estimate};")
    before_row, before_items = h.sql(row), h.sql(items)
    outbox = "SELECT COUNT(*) FROM buildflow_estimate.outbox_events;"
    before_outbox = int(h.sql(outbox))
    change = {"title": "[QA] update race changed", "estimateDate": "2026-01-01",
              "items": [{"itemName": "work", "unit": "ea", "quantity": 1, "unitPrice": 300},
                        {"itemName": "extra", "unit": "ea", "quantity": 1, "unitPrice": 50}]}
    # update: row lock -> first check -> DRAFT check -> lazy item collection load on estimate_items
    # (first SQL, before the orphan DELETE/INSERT and the estimates UPDATE) -> flush
    mutation_race(h, "buildflow_estimate", "estimates", estimate, site,
                  ("estimate", f"/api/v1/estimates/{estimate}", "PUT", change, 409), "Estimate update",
                  lock_table="estimate_items")
    require(h.sql(row) == before_row, "Archived-site estimate update changed the stored estimate")
    require(h.sql(items) == before_items, "Archived-site estimate update changed the stored items")
    require(int(h.sql(outbox)) == before_outbox, "Archived-site estimate update wrote an outbox event")
    print("[PASS] MySQL-held estimate update rechecked archived site via Feign: 409, estimate and items unchanged")
    active = h.api("site", "/api/v1/sites", "POST", {"siteName": "[QA] estimate update control"}, 201)["id"]
    control = int(h.api("estimate", "/api/v1/estimates", "POST", dict(created, siteId=active), 201)["id"])
    h.api("estimate", f"/api/v1/estimates/{control}", "PUT", change, 200)
    require(h.sql(f"SELECT COUNT(*) FROM buildflow_estimate.estimate_items WHERE estimate_id={control};") == "2",
            "Active-site control estimate update did not commit")
    print("[PASS] Active-site control committed estimate update")


def exercise_warranty_update(h):
    h.phase = "warranty update archive race"
    site = h.api("site", "/api/v1/sites", "POST", {"siteName": "[QA] warranty update race"}, 201)["id"]
    created = {"siteId": site, "insuranceCompany": "[QA] 보증보험", "policyNumber": "QA-0002",
               "startDate": "2026-01-01", "endDate": "2030-12-31", "coverageAmount": 1000}
    warranty = int(h.api("notification", "/api/v1/warranties", "POST", created, 201, NOTIFICATION_PORT)["id"])
    row = ("SELECT insurance_company, coverage_amount, end_date FROM buildflow_notification.defect_warranties "
           f"WHERE id={warranty};")
    before_row = h.sql(row)
    audit = "SELECT COUNT(*) FROM buildflow_notification.warranty_audit_events;"
    outbox = "SELECT COUNT(*) FROM buildflow_notification.outbox_events;"
    before_audit, before_outbox = int(h.sql(audit)), int(h.sql(outbox))
    change = {"insuranceCompany": "[QA] 보증보험 수정", "startDate": "2026-01-01", "endDate": "2031-12-31",
              "coverageAmount": 2000}
    # update: row lock -> first check -> OCR/period/amount checks -> MANUAL_UPDATED audit IDENTITY INSERT
    # on warranty_audit_events (first SQL; this path has no outbox write) -> warranty UPDATE flush
    mutation_race(h, "buildflow_notification", "defect_warranties", warranty, site,
                  ("notification", f"/api/v1/warranties/{warranty}", "PUT", change, 409, NOTIFICATION_PORT),
                  "Warranty update", lock_table="warranty_audit_events")
    require(h.sql(row) == before_row, "Archived-site warranty update changed the stored row")
    require(int(h.sql(audit)) == before_audit, "Archived-site warranty MANUAL_UPDATED audit was committed")
    require(int(h.sql(outbox)) == before_outbox, "Archived-site warranty update wrote an outbox event")
    print("[PASS] MySQL-held warranty update rechecked archived site via Feign: 409, row and audit unchanged")
    active = h.api("site", "/api/v1/sites", "POST", {"siteName": "[QA] warranty update control"}, 201)["id"]
    control = int(h.api("notification", "/api/v1/warranties", "POST", dict(created, siteId=active), 201,
                        NOTIFICATION_PORT)["id"])
    control_audit = int(h.sql(audit))
    h.api("notification", f"/api/v1/warranties/{control}", "PUT", change, 200, NOTIFICATION_PORT)
    require(h.sql(f"SELECT coverage_amount FROM buildflow_notification.defect_warranties WHERE id={control};")
            == "2000", "Active-site control warranty update did not commit")
    require(int(h.sql(audit)) == control_audit + 1, "Active-site control warranty audit did not commit")
    print("[PASS] Active-site control committed warranty update and audit event")

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
            exercise_purchase_update(h)
            exercise_estimate_confirm(h)
            exercise_purchase_delete(h)
            exercise_tax_update(h)
            exercise_payment_cancel(h)
            exercise_estimate_update(h)
            exercise_warranty_update(h)
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
