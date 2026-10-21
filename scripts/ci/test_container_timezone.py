"""컨테이너 JVM 기본 시간대가 KST인지 고정한다(업무 시각 +9h 불일치 회귀 방지)."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
DOCKERFILES = ["Dockerfile", "notification-service/Dockerfile"]


class ContainerTimezoneTests(unittest.TestCase):
    def test_java_services_start_with_kst_default_timezone(self):
        for relative in DOCKERFILES:
            text = (ROOT / relative).read_text(encoding="utf-8")
            runtime = text.split("FROM eclipse-temurin:17-jre-jammy", 1)[1]
            self.assertIn("ENV TZ=Asia/Seoul", runtime.splitlines(), relative)

    def test_every_backend_service_image_uses_a_checked_dockerfile(self):
        compose = (ROOT / "docker-compose.app.yml").read_text(encoding="utf-8")
        for line in compose.splitlines():
            stripped = line.strip()
            if stripped.startswith("dockerfile:"):
                path = stripped.split(":", 1)[1].strip().strip("\"'")
                if path.endswith("Dockerfile") and "frontend" not in path:
                    self.assertIn(path.lstrip("./"), DOCKERFILES, path)


if __name__ == "__main__":
    unittest.main()
