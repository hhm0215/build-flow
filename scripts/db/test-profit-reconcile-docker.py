#!/usr/bin/env python3
"""Synthetic MySQL/Feign/Kafka reconciliation regression; Python stdlib only.

Build :site-service:bootJar :estimate-service:bootJar :purchase-service:bootJar
first. No user database, credentials, ports, volumes or .env are used. Source
dispatchers are isolated/disabled; only selected committed outbox events are
published, so this checks real consumers rather than dispatcher delivery.
"""
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time
from decimal import Decimal

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = Path(__file__).with_name("profit-reconcile-test.compose.yml")
LABEL = "com.buildflow.profit-reconcile-test"
PORTS = {"site": 8083, "estimate": 8082, "purchase": 8084}
HTTP_PROBE = """
import json, sys, urllib.request, urllib.error
r = json.load(sys.stdin)
body = json.dumps(r['body']).encode() if r['body'] is not None else None
request = urllib.request.Request(r['url'], data=body, method=r['method'],
                                 headers={'Content-Type': 'application/json'})
try:
    with urllib.request.urlopen(request, timeout=10) as response:
        status, text = response.status, response.read().decode()
except urllib.error.HTTPError as error:
    status, text = error.code, error.read().decode()
except urllib.error.URLError:
    status, text = 0, '{}'
print(json.dumps({'status': status, 'body': json.loads(text)}))
"""


class Failure(Exception):
    pass


def require(condition, message):
    if not condition:
        raise Failure(message)


class Harness:
    def __init__(self, temporary):
        self.nonce = secrets.token_hex(16)
        self.project = "bfprofit" + secrets.token_hex(8)
        self.env = {k: v for k, v in os.environ.items()
                    if not k.startswith(("COMPOSE_", "RECONCILE_"))}
        self.env.update(RECONCILE_NONCE=self.nonce,
                        RECONCILE_DB_PASSWORD=secrets.token_hex(32),
                        RECONCILE_LISTENERS="false", RECONCILE_PURCHASE_PORT="8084")
        self.jars = set()
        for service in PORTS:
            jars = [p.resolve() for p in (ROOT / f"{service}-service/build/libs").glob("*.jar")
                    if not p.name.endswith("-plain.jar")]
            require(len(jars) == 1, f"Build exactly one {service} bootJar first")
            self.env[f"RECONCILE_{service.upper()}_JAR"] = jars[0].as_posix()
            self.jars.add(str(jars[0]))
        empty_env = Path(temporary) / "empty.env"
        empty_env.write_text("", encoding="utf-8")
        self.prefix = ["compose", "--project-directory", temporary, "--env-file",
                       str(empty_env), "--project-name", self.project, "-f", str(COMPOSE)]
        self.deadline = time.monotonic() + 900
        self.started = False
        self.phase = "configuration"

    def run(self, args, text=None, timeout=60, cleanup=False):
        remaining = 90 if cleanup else int(self.deadline - time.monotonic())
        require(remaining > 0, "Integration time limit exceeded")
        try:
            result = subprocess.run(["docker", *args], input=text, encoding="utf-8",
                                    errors="replace", capture_output=True, env=self.env,
                                    timeout=min(timeout, remaining), check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise Failure("Docker operation unavailable or timed out") from None
        # Never print subprocess output: app/config output can include credentials.
        require(result.returncode == 0, f"Docker operation failed during {self.phase}")
        return result.stdout.strip()

    def compose(self, *args, text=None, timeout=60, cleanup=False):
        return self.run([*self.prefix, *args], text, timeout, cleanup)

    def validate_config(self):
        config = json.loads(self.compose("config", "--format", "json"))
        require(config.get("name") == self.project, "Unexpected Compose project")
        require(not config.get("volumes"), "Persistent volumes are forbidden")
        for service in config["services"].values():
            require(not service.get("ports") and not service.get("container_name"),
                    "Host ports and fixed container names are forbidden")
            require(service.get("labels", {}).get(LABEL) == self.nonce, "Missing nonce label")
            for mount in service.get("volumes", []):
                require(mount.get("type") == "bind" and mount.get("read_only") is True
                        and str(Path(mount["source"]).resolve()) in self.jars,
                        "Only readonly test JAR mounts are allowed")

    def sql(self, statement):
        return self.compose("exec", "-T", "mysql", "sh", "-c",
                            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot '
                            '--batch --raw --skip-column-names', text=statement)

    def api(self, service, path, method="GET", body=None, status=200, port=None):
        request = {"url": f"http://{service}:{port or PORTS[service]}{path}",
                   "method": method, "body": body}
        response = json.loads(self.compose("exec", "-T", "probe", "python", "-c",
                                           HTTP_PROBE, text=json.dumps(request), timeout=20))
        require(response["status"] == status,
                f"Unexpected synthetic HTTP result: {service} {method} {path}: {response['status']}")
        if path == "/actuator/health":
            return response["body"]
        require(response["body"].get("success") is (status < 400), "Unexpected API envelope")
        return response["body"].get("data")

    def wait_health(self, service, port=None):
        until = time.monotonic() + 150
        while time.monotonic() < until:
            try:
                if self.api(service, "/actuator/health", port=port).get("status") == "UP":
                    return
            except Failure:
                pass
            time.sleep(2)
        raise Failure(f"Synthetic {service} readiness timed out")

    def replay(self, database, where="1=1"):
        rows = self.sql(f"SELECT topic, record_key, payload_json FROM {database}.outbox_events "
                        f"WHERE {where} ORDER BY created_at, event_id;")
        counts = {}
        for row in rows.splitlines():
            topic, key, payload = row.split("\t", 2)
            require(topic in {"purchase.registered", "purchase.updated", "purchase.deleted",
                              "estimate.parsed", "estimate.deleted"}, "Unexpected test topic")
            self.compose("exec", "-T", "kafka", "kafka-console-producer.sh",
                         "--bootstrap-server", "localhost:9092", "--topic", topic,
                         "--property", "parse.key=true", "--property", "key.separator=|",
                         text=f"{key}|{payload}\n", timeout=35)
            counts[topic] = counts.get(topic, 0) + 1
        require(bool(counts), "No synthetic events selected for replay")
        return counts

    def wait_consumed(self, expected):
        until = time.monotonic() + 100
        while time.monotonic() < until:
            try:
                output = self.compose("exec", "-T", "kafka", "kafka-consumer-groups.sh",
                                      "--bootstrap-server", "localhost:9092", "--group",
                                      "site-service-group", "--describe", timeout=20)
            except Failure:
                # The listener can be healthy before its consumer group has joined.
                time.sleep(2)
                continue
            offsets = {}
            for row in output.splitlines():
                fields = row.split()
                if len(fields) >= 6 and fields[0] == "site-service-group" and fields[1] in expected:
                    if fields[3].isdigit() and fields[5] == "0":
                        offsets[fields[1]] = offsets.get(fields[1], 0) + int(fields[3])
            if all(offsets.get(topic, -1) >= count for topic, count in expected.items()):
                return
            time.sleep(2)
        raise Failure("Kafka consumer did not acknowledge the replayed events")

    def profit(self, site_id, estimate, purchase):
        result = self.api("site", f"/api/v1/sites/{site_id}/profit")
        require(Decimal(str(result["totalEstimateAmount"])) == Decimal(estimate)
                and Decimal(str(result["totalPurchaseAmount"])) == Decimal(purchase)
                and Decimal(str(result["margin"])) == Decimal(estimate) - Decimal(purchase),
                "Synthetic profit disagrees with committed sources")

    def state(self):
        return self.sql("SELECT site_id,total_estimate_amount,total_purchase_amount,margin,margin_rate "
                        "FROM buildflow_site.site_profits ORDER BY site_id; "
                        "SELECT event_id,event_type,site_id FROM buildflow_site.processed_profit_events "
                        "ORDER BY event_id; SELECT purchase_id,last_revision,current_amount,deleted "
                        "FROM buildflow_site.purchase_profit_projections ORDER BY purchase_id;")

    def exercise(self):
        self.validate_config()
        self.started = True
        self.phase = "isolated infrastructure startup"
        print("[RUN] Starting isolated MySQL, Kafka and HTTP probe", flush=True)
        # Hosted runners occasionally fail an image pull/start transiently (2026-10-28 PR #208);
        # one retry of the infra-only step keeps the overall 900s deadline and output policy.
        for attempt in (1, 2):
            try:
                # The retry recreates containers: restarting the same exited Kafka failed identically (2026-10-31).
                extra = ("--force-recreate",) if attempt == 2 else ()
                self.compose("up", "-d", *extra, "--wait", "--wait-timeout", "150", "mysql", "kafka", "probe",
                             timeout=240)
                break
            except Failure:
                self.infra_diagnostics(attempt)
                if attempt == 2:
                    raise
                print("[RUN] Infrastructure startup failed once; retrying", flush=True)
                time.sleep(10)
        self.sql("\n".join(f"CREATE DATABASE buildflow_{name} CHARACTER SET utf8mb4 "
                           "COLLATE utf8mb4_unicode_ci;" for name in PORTS))
        for topic in ("purchase.registered", "purchase.updated", "purchase.deleted",
                      "estimate.parsed", "estimate.deleted"):
            self.compose("exec", "-T", "kafka", "kafka-topics.sh", "--bootstrap-server",
                         "localhost:9092", "--create", "--if-not-exists", "--topic", topic,
                         "--partitions", "1", "--replication-factor", "1")
        self.phase = "application startup"
        print("[RUN] Starting application jars with Flyway validation", flush=True)
        self.compose("up", "-d", "--no-deps", "site", "estimate", "purchase", timeout=180)
        for service in PORTS:
            self.wait_health(service)
        self.phase = "synthetic source and reconciliation APIs"
        site = self.api("site", "/api/v1/sites", "POST", {"siteName": "[QA] isolated reconciliation"}, 201)["id"]
        purchase_body = {"siteId": site, "itemName": "synthetic material", "quantity": 1, "unitPrice": 100}
        deleted = self.api("purchase", "/api/v1/purchases", "POST", purchase_body, 201)["id"]
        self.api("purchase", f"/api/v1/purchases/{deleted}", "PUT", dict(purchase_body, unitPrice=200))
        self.api("purchase", f"/api/v1/purchases/{deleted}", "DELETE")
        source = self.api("purchase", f"/api/v1/purchases/profit-source?siteId={site}")
        require(not source["purchases"] and len(source["profitEvents"]) == 3,
                "Deleted purchase events are missing from source snapshot")
        estimate_body = {"siteId": site, "title": "synthetic estimate", "estimateDate": "2026-01-01",
                         "items": [{"itemName": "work", "unit": "ea", "quantity": 1, "unitPrice": 1000}]}
        original = self.api("estimate", "/api/v1/estimates", "POST", estimate_body, 201)["id"]
        self.api("estimate", f"/api/v1/estimates/{original}/confirm", "PATCH")
        revision_body = dict(estimate_body, kind="REVISION", revisionOfId=original,
                             items=[dict(estimate_body["items"][0], unitPrice=1200)])
        revision = self.api("estimate", "/api/v1/estimates", "POST", revision_body, 201)["id"]
        self.api("estimate", f"/api/v1/estimates/{revision}/confirm", "PATCH")
        estimate_source = self.api("estimate", f"/api/v1/estimates/profit-source?siteId={site}")
        require(Decimal(str(estimate_source["confirmedTotalAmount"])) == Decimal("1200")
                and len(estimate_source["profitEvents"]) == 3,
                "Estimate revision source snapshot disagrees with committed rows")
        before = self.state()
        path = f"/api/v1/sites/{site}/profit/reconcile"
        dry = self.api("site", path, "POST")
        require(not dry["applied"] and dry["purchaseEventsToMark"] == 3
                and self.state() == before, "Default dry-run changed stored state")
        applied = self.api("site", path + "?apply=true", "POST")
        require(applied["applied"] and applied["purchaseEventsToMark"] == 3,
                "Apply did not record deleted-purchase events")
        self.profit(site, "1200", "0")
        require(self.sql("SELECT COUNT(*) FROM buildflow_site.processed_profit_events;") == "6",
                "Apply omitted source event receipts")
        print("[PASS] Real API/Feign snapshots, dry-run, revision replacement and atomic apply")
        self.phase = "delayed Kafka replay"
        self.env["RECONCILE_LISTENERS"] = "true"
        self.compose("up", "-d", "--no-deps", "--force-recreate", "site", timeout=120)
        self.wait_health("site")
        expected = self.replay("buildflow_purchase", "topic != 'purchase.deleted'")
        expected.update(self.replay("buildflow_estimate"))
        self.wait_consumed(expected)
        self.profit(site, "1200", "0")
        require(self.sql("SELECT COUNT(*) FROM buildflow_site.purchase_profit_projections;") == "0",
                "Delayed deleted-purchase event created a projection")
        print("[PASS] Kafka acknowledged late registered/updated events; deleted cost stayed zero")
        created = self.api("purchase", "/api/v1/purchases", "POST", dict(purchase_body, unitPrice=75), 201)["id"]
        new_counts = self.replay("buildflow_purchase", f"record_key='{created}'")
        for topic, count in new_counts.items():
            expected[topic] = expected.get(topic, 0) + count
        self.wait_consumed(expected)
        self.profit(site, "1200", "75")
        print("[PASS] Kafka applied a new purchase after reconciliation")
        self.phase = "source outage"
        before = self.state()
        # Move only the isolated source listener; Feign's original port becomes unavailable.
        self.env["RECONCILE_PURCHASE_PORT"] = "18084"
        self.compose("up", "-d", "--no-deps", "--force-recreate", "purchase", timeout=120)
        self.wait_health("purchase", port=18084)
        self.api("site", path + "?apply=true", "POST", status=503)
        require(self.state() == before, "Source outage changed profit or receipts")
        print("[PASS] Unavailable source returned 503 without changing stored state")

    def infra_diagnostics(self, attempt):
        # Container state/exit code/health carry no secrets; infra logs are filtered to error lines
        # and the generated DB password is redacted (2026-10-29: repeated fast startup failures).
        try:
            states = self.compose("ps", "--all", "--format",
                                  "{{.Service}} state={{.State}} health={{.Health}} status={{.Status}} image={{.Image}}",
                                  cleanup=True)
            for line in states.splitlines()[:10]:
                print(f"[DIAG infra#{attempt}] {line[:300]}", file=sys.stderr)
        except Failure:
            print(f"[DIAG infra#{attempt}] Container state unavailable", file=sys.stderr)
        for service in ("mysql", "kafka"):
            try:
                logs = self.compose("logs", "--no-color", "--tail", "200", service, cleanup=True)
                lines = logs.splitlines()
                selected = [line for line in lines
                            if any(marker in line for marker in ("ERROR", "FATAL", "Exception", "denied", "No space"))]
                # The fatal line alone hid the cause (2026-10-31 Kafka "Error starting LogManager"):
                # also keep the stack/cause lines that follow the first fatal fault, still bounded.
                fatal = next((i for i, line in enumerate(lines) if "fatal fault" in line), None)
                if fatal is not None:
                    selected = lines[fatal:fatal + 15]
                for line in selected[-15:]:
                    safe = line.replace(self.env["RECONCILE_DB_PASSWORD"], "[redacted]")
                    print(f"[DIAG infra#{attempt} {service}] {safe[:400]}", file=sys.stderr)
            except Failure:
                print(f"[DIAG infra#{attempt} {service}] Bounded logs unavailable", file=sys.stderr)

    def diagnostics(self):
        # These applications contain only the generated synthetic fixtures above.
        # Emit bounded error categories, never config/env dumps or arbitrary output.
        for service in PORTS:
            try:
                logs = self.compose("logs", "--no-color", "--tail", "100", service, cleanup=True)
                selected = [line for line in logs.splitlines()
                            if any(marker in line for marker in (" ERROR ", "Caused by:", "손익 원천"))]
                for line in selected[-10:]:
                    safe = line.replace(self.env["RECONCILE_DB_PASSWORD"], "[redacted]")
                    print(f"[DIAG {service}] {safe[:800]}", file=sys.stderr)
            except Failure:
                print(f"[DIAG {service}] Bounded diagnostics unavailable", file=sys.stderr)

    def cleanup(self):
        if not self.started:
            return
        containers = self.compose("ps", "--all", "--quiet", cleanup=True).splitlines()
        for container in containers:
            labels = json.loads(self.run(["inspect", "--format", "{{json .Config.Labels}}", container], cleanup=True))
            require(labels.get(LABEL) == self.nonce
                    and labels.get("com.docker.compose.project") == self.project,
                    "Cleanup refused: container ownership changed")
        networks = self.run(["network", "ls", "--filter", f"label=com.docker.compose.project={self.project}",
                             "--format", "{{.ID}}"], cleanup=True).splitlines()
        for network in networks:
            labels = json.loads(self.run(["network", "inspect", "--format", "{{json .Labels}}", network], cleanup=True))
            require(labels.get(LABEL) == self.nonce, "Cleanup refused: network ownership changed")
        self.compose("down", "--timeout", "10", timeout=60, cleanup=True)
        require(not self.compose("ps", "--all", "--quiet", cleanup=True),
                "Isolated containers remain after cleanup")
        require(not self.run(["network", "ls", "--filter",
                              f"label=com.docker.compose.project={self.project}",
                              "--format", "{{.ID}}"], cleanup=True),
                "Isolated network remains after cleanup")


def main():
    failed = False
    with tempfile.TemporaryDirectory(prefix="bf-profit-test-") as temporary:
        harness = None
        try:
            harness = Harness(temporary)
            harness.exercise()
        except Failure as error:
            failed = True
            print(f"[FAIL] {error}; subprocess output withheld", file=sys.stderr)
        except (ValueError, KeyError, TypeError):
            failed = True
            print("[FAIL] Isolated reconciliation integration did not pass; subprocess output withheld", file=sys.stderr)
        finally:
            if harness is not None:
                if failed and harness.started:
                    harness.diagnostics()
                try:
                    harness.cleanup()
                except (Failure, ValueError, KeyError, TypeError):
                    failed = True
                    print("[FAIL] Isolated cleanup could not be verified; no broader cleanup attempted", file=sys.stderr)
    if not failed:
        print("[PASS] Isolated project removed; no user volumes or host ports were used")
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
