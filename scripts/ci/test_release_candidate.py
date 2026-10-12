import tempfile
from pathlib import Path
import unittest

from create_release_candidate import (
    DEPLOYMENT_FILES,
    REQUIRED_RESULTS,
    build_candidate,
    checked_file_hash,
)


SHA = "a" * 40


class ReleaseCandidateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for relative in DEPLOYMENT_FILES:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(relative, encoding="utf-8")
        self.tracked = set(DEPLOYMENT_FILES)
        self.env = {
            "GITHUB_EVENT_NAME": "push",
            "GITHUB_REF": "refs/heads/main",
            "GITHUB_REPOSITORY": "hhm0215/build-flow",
            "GITHUB_SHA": SHA,
            "GITHUB_RUN_ID": "42",
            "GITHUB_RUN_ATTEMPT": "1",
            **{variable: "success" for variable in REQUIRED_RESULTS.values()},
        }

    def candidate(self):
        return build_candidate(self.env, self.root, SHA, True, self.tracked)

    def test_main_success_is_evidence_only(self):
        candidate = self.candidate()
        self.assertEqual(candidate["source_sha"], SHA)
        self.assertEqual(set(candidate["tracked_deployment_file_sha256"]), self.tracked)
        self.assertFalse(candidate["deploy_authorized"])
        self.assertFalse(candidate["real_data_ready"])

    def test_rejects_non_main_events(self):
        for key, value in (("GITHUB_EVENT_NAME", "pull_request"), ("GITHUB_REF", "refs/heads/develop")):
            with self.subTest(key=key):
                self.env[key] = value
                with self.assertRaises(ValueError):
                    self.candidate()
                self.env[key] = "push" if key == "GITHUB_EVENT_NAME" else "refs/heads/main"

    def test_rejects_wrong_repository_sha_and_dirty_checkout(self):
        self.env["GITHUB_REPOSITORY"] = "other/repo"
        with self.assertRaises(ValueError):
            self.candidate()
        self.env["GITHUB_REPOSITORY"] = "hhm0215/build-flow"
        for bad_sha in ("short", "A" * 40, "b" * 40):
            with self.subTest(sha=bad_sha):
                self.env["GITHUB_SHA"] = bad_sha
                with self.assertRaises(ValueError):
                    self.candidate()
        self.env["GITHUB_SHA"] = SHA
        with self.assertRaises(ValueError):
            build_candidate(self.env, self.root, SHA, False, self.tracked)

    def test_rejects_failed_or_missing_job(self):
        for outcome in ("failure", "skipped", "cancelled", None):
            with self.subTest(outcome=outcome):
                self.env["RESULT_BACKEND"] = outcome
                with self.assertRaises(ValueError):
                    self.candidate()
        self.env["RESULT_BACKEND"] = "success"

    def test_rejects_invalid_run_numbers_and_missing_file(self):
        for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"):
            previous = self.env[key]
            for value in ("", "0", "1x"):
                with self.subTest(key=key, value=value):
                    self.env[key] = value
                    with self.assertRaises(ValueError):
                        self.candidate()
            self.env[key] = previous
        (self.root / DEPLOYMENT_FILES[0]).unlink()
        with self.assertRaises(ValueError):
            self.candidate()

    def test_rejects_untracked_secret_or_traversal(self):
        with self.assertRaises(ValueError):
            build_candidate(self.env, self.root, SHA, True, self.tracked - {DEPLOYMENT_FILES[0]})
        for relative in (".env", "../.env", "/tmp/secret"):
            with self.subTest(relative=relative), self.assertRaises(ValueError):
                checked_file_hash(self.root, relative, self.tracked | {relative})

    def test_rejects_symlink(self):
        file = self.root / DEPLOYMENT_FILES[0]
        file.unlink()
        target = self.root / "secret"
        target.write_text("do not read", encoding="utf-8")
        try:
            file.symlink_to(target)
        except OSError:
            self.skipTest("Symlink creation is unavailable")
        with self.assertRaises(ValueError):
            self.candidate()


if __name__ == "__main__":
    unittest.main()
