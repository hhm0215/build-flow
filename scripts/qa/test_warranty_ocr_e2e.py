"""warranty_ocr_e2e.py 단위 테스트 — Docker·네트워크 없이 실행한다.

python -I -X utf8 -m unittest discover -s scripts/qa -p "test_*.py"
"""

import datetime as dt
import json
import random
import unittest
from pathlib import Path

import local_e2e as e2e
import warranty_ocr_e2e as ocr

TODAY = dt.date(2026, 11, 1)


class IsolationTests(unittest.TestCase):
    def test_project_name_must_be_isolated(self):
        self.assertEqual(ocr.require_isolated_project("bfqaocr"), "bfqaocr")
        for name in ("build-flow", "buildflow", "bfqa-ocr", "BFQA", ""):
            with self.subTest(name=name), self.assertRaises(e2e.SetupError):
                ocr.require_isolated_project(name)

    def test_cleanup_targets_only_isolated_volumes(self):
        names = ocr.volume_names("bfqaocr")
        self.assertEqual(len(names), 4)
        self.assertTrue(all(n.startswith("bfqaocr_") for n in names))
        self.assertFalse(set(names) & set(ocr.PROTECTED_VOLUMES))

    def test_compose_has_no_fixed_names_secrets_or_public_ports(self):
        spec = ocr.build_compose(Path("C:/repo"), 18080)
        text = json.dumps(spec)
        self.assertNotIn("container_name", text)
        self.assertNotIn("buildflow-net", text)
        self.assertIn("${BFQA_DB_PASSWORD:?run the script}", text)
        ports = [p for svc in spec["services"].values() for p in svc.get("ports", [])]
        self.assertEqual(ports, ["127.0.0.1:18080:8080"])
        notification = spec["services"]["notification-service"]
        self.assertEqual(notification["mem_limit"], "896m")
        self.assertEqual(notification["environment"]["JAVA_TOOL_OPTIONS"], "-Xms64m -Xmx320m")
        for svc in spec["services"].values():
            for volume in svc.get("volumes", []):
                if isinstance(volume, str):
                    self.assertIn(volume.split(":")[0], ocr.VOLUMES)
                else:
                    self.assertTrue(volume["read_only"])


class SpecTests(unittest.TestCase):
    def test_specs_fall_inside_expiring_window_and_differ(self):
        text, image = ocr.make_specs(TODAY, random.Random(1))
        self.assertEqual((text.kind, image.kind), ("text", "image"))
        for spec in (text, image):
            self.assertLess(spec.start, TODAY)
            self.assertTrue(TODAY < spec.end <= TODAY + dt.timedelta(days=ocr.EXPIRING_DAYS))
            self.assertRegex(spec.policy, r"^[A-Z0-9\-]{6,30}$")  # 파서 POLICY_PATTERN 범위
        self.assertNotEqual(text.insurer, image.insurer)
        self.assertNotEqual(text.policy, image.policy)
        self.assertEqual(len(text.line().split("\t")), 6)


class EvaluateTests(unittest.TestCase):
    def setUp(self):
        self.text, self.image = ocr.make_specs(TODAY, random.Random(2))

    def ok(self, spec, **override):
        data = {"ocrStatus": "SUCCESS", "insuranceCompany": spec.insurer, "policyNumber": spec.policy,
                "startDate": spec.start.isoformat(), "endDate": spec.end.isoformat()}
        data.update(override)
        return data

    def test_text_layer_requires_exact_policy(self):
        self.assertEqual(ocr.evaluate_ocr(self.text, self.ok(self.text)), ([], "정확"))
        mismatches, _ = ocr.evaluate_ocr(self.text, self.ok(self.text, policyNumber=None))
        self.assertTrue(mismatches)

    def test_image_allows_missing_or_confusable_policy(self):
        self.assertEqual(ocr.evaluate_ocr(self.image, self.ok(self.image, policyNumber=None)), ([], "미추출"))
        confused = self.image.policy.replace("0", "O")
        mismatches, note = ocr.evaluate_ocr(self.image, self.ok(self.image, policyNumber=confused))
        self.assertEqual(mismatches, [])
        self.assertTrue(note.startswith("정규화 일치") or note == "정확")
        mismatches, note = ocr.evaluate_ocr(self.image, self.ok(self.image, policyNumber="999999"))
        self.assertEqual(mismatches, [])
        self.assertTrue(note.startswith("불일치"))

    def test_required_fields_and_status_follow_code_rule(self):
        for override in ({"ocrStatus": "FAILED"}, {"insuranceCompany": None},
                         {"startDate": "2025-01-01"}, {"endDate": None}):
            with self.subTest(override=override):
                mismatches, _ = ocr.evaluate_ocr(self.image, self.ok(self.image, **override))
                self.assertTrue(mismatches)

    def test_normalize_matches_server_confusion_table(self):
        self.assertEqual(ocr.normalize_policy("QA-2026-2001"), ocr.normalize_policy("04-2026-2001"))
        self.assertNotEqual(ocr.normalize_policy("QA-2026-2001"), ocr.normalize_policy("QA-2026-2002"))


class HelperTests(unittest.TestCase):
    def test_multipart_body_layout(self):
        body = ocr.multipart_body({"siteId": "7"}, "file", "a.pdf", b"%PDF-1.7", "B")
        self.assertIn(b'name="siteId"\r\n\r\n7\r\n', body)
        self.assertIn(b'filename="a.pdf"\r\nContent-Type: application/pdf\r\n\r\n%PDF-1.7\r\n', body)
        self.assertTrue(body.endswith(b"--B--\r\n"))

    def test_parse_text_layer(self):
        out = "generated\ttext\t12220\t131\ngenerated\timage\t37301\t0\nnoise\n"
        self.assertEqual(ocr.parse_text_layer(out), {"text": 131, "image": 0})
        self.assertEqual(ocr.parse_text_layer("generated text 1 2"), {})

    def test_parse_mem_usage(self):
        self.assertEqual(ocr.parse_mem_usage("412MiB / 896MiB"), 412 * 1024 * 1024)
        self.assertEqual(ocr.parse_mem_usage("1.5GiB / 2GiB"), int(1.5 * 1024 ** 3))
        self.assertIsNone(ocr.parse_mem_usage("--"))

    def test_audit_seconds(self):
        history = [{"action": "UPLOADED", "occurredAt": "2026-11-01T10:00:00"},
                   {"action": "OCR_APPLIED", "occurredAt": "2026-11-01T10:00:03.5"},
                   {"action": "MANUAL_UPDATED", "occurredAt": "2026-11-01T10:01:00"}]
        self.assertEqual(ocr.audit_seconds(history), 3.5)
        self.assertIsNone(ocr.audit_seconds(history[:1]))


if __name__ == "__main__":
    unittest.main()
