"""ui_stack.py 단위 테스트 — Docker·네트워크 없이 실행한다.

python -I -X utf8 -m unittest discover -s scripts/qa -p "test_*.py"
"""

import datetime as dt
import json
import unittest
from pathlib import Path

import local_e2e as e2e
import ui_stack as ui
import warranty_ocr_e2e as ocr

REPO = Path("C:/repo")


class ComposeTests(unittest.TestCase):
    def test_only_loopback_frontend_and_gateway_published(self):
        spec = ui.build_compose(REPO)
        self.assertEqual(ui.published_ports(spec), ["127.0.0.1:13080:80", "127.0.0.1:18081:8080"])
        self.assertEqual(spec["name"], "bfqaui")

    def test_full_service_set_and_optional_chat(self):
        spec = ui.build_compose(REPO)
        for name in ("mysql", "redis", "kafka", "eureka-server", "gateway-server", "auth-service",
                     "estimate-service", "site-service", "purchase-service", "tax-service",
                     "notification-service", "chat-service", "frontend"):
            self.assertIn(name, spec["services"])
        self.assertNotIn("config-server", spec["services"])
        self.assertNotIn("chat-service", ui.build_compose(REPO, with_chat=False)["services"])
        self.assertNotIn("chat-service", ui.build_images(False))
        self.assertEqual(ui.build_images(True)[-1], "frontend")

    def test_images_use_isolated_tags_only(self):
        spec = ui.build_compose(REPO)
        images = [s["image"] for s in spec["services"].values() if "build" in s]
        self.assertEqual(len(images), 10)
        self.assertTrue(all(i.startswith("bfqaui-") and i.endswith(":local") for i in images))

    def test_no_fixed_names_shared_network_or_secrets(self):
        text = json.dumps(ui.build_compose(REPO))
        self.assertNotIn("container_name", text)
        self.assertNotIn("buildflow-net", text)
        self.assertNotIn("build-flow-", text)
        self.assertIn("${BFQA_DB_PASSWORD:?run the script}", text)
        self.assertIn("${BFQA_JWT_SECRET:?run the script}", text)
        self.assertIn('"CLAUDE_API_KEY": ""', text)

    def test_services_skip_config_server(self):
        spec = ui.build_compose(REPO)
        for name, svc in spec["services"].items():
            if name.endswith(("-service", "gateway-server")):
                with self.subTest(service=name):
                    self.assertEqual(svc["environment"].get("SPRING_CLOUD_CONFIG_ENABLED"), "false")

    def test_frontend_proxies_to_gateway_service_name(self):
        spec = ui.build_compose(REPO, frontend_port=13999)
        self.assertEqual(spec["services"]["frontend"]["build"]["context"], "C:/repo/frontend")
        self.assertIn("gateway-server", spec["services"]["frontend"]["depends_on"])
        cors = spec["services"]["gateway-server"]["environment"]["CORS_ALLOWED_ORIGINS"]
        self.assertIn("http://127.0.0.1:13999", cors)
        nginx = (Path(__file__).resolve().parents[2] / "frontend" / "nginx.conf").read_text(encoding="utf-8")
        self.assertIn("proxy_pass http://gateway-server:8080", nginx)

    def test_ocr_compose_defaults_unchanged(self):
        spec = ocr.build_compose(REPO, 18080)
        self.assertEqual(spec["name"], "bfqaocr")
        self.assertEqual(spec["services"]["auth-service"]["image"], "bfqaocr-auth-service:local")
        with self.assertRaises(e2e.SetupError):
            ocr.build_compose(REPO, 18080, project="build-flow")

    def test_volumes_are_isolated(self):
        names = ocr.volume_names(ui.PROJECT)
        self.assertTrue(all(n.startswith("bfqaui_") for n in names))
        self.assertFalse(set(names) & set(ocr.PROTECTED_VOLUMES))


class CredentialFileTests(unittest.TestCase):
    def test_round_trip_and_location_is_ignored_path(self):
        creds = e2e.Credentials("qa-admin-1234abcd", "A" * 32)
        text = ui.format_credentials_json(creds, "http://127.0.0.1:13080", "http://127.0.0.1:18081",
                                          now=dt.datetime(2026, 11, 1, 9, 0))
        self.assertEqual(ui.parse_credentials_json(text), creds)
        self.assertEqual(json.loads(text)["frontendUrl"], "http://127.0.0.1:13080")
        self.assertEqual(ui.CREDENTIAL_FILE.parent.name, ".local")

    def test_invalid_files_rejected(self):
        for text in ("", "[]", "{}", json.dumps({"loginId": "a b", "password": "x" * 20}),
                     json.dumps({"loginId": "qa-admin", "password": "short"})):
            with self.subTest(text=text), self.assertRaises(e2e.SetupError):
                ui.parse_credentials_json(text)

    def test_repr_hides_password(self):
        self.assertNotIn("secret-value", repr(e2e.Credentials("qa-admin-x", "secret-value")))


class ProbeTests(unittest.TestCase):
    def test_spa_detection(self):
        self.assertTrue(ui.looks_like_spa(200, b'<div id="root"></div>'))
        self.assertFalse(ui.looks_like_spa(502, b'<div id="root"></div>'))
        self.assertFalse(ui.looks_like_spa(200, b"Bad Gateway"))


if __name__ == "__main__":
    unittest.main()
