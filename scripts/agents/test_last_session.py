"""Synthetic fixtures only: never put real session contents in the repository."""

import json
import unittest

from last_session import actual_user_text, codex_events, visible_excerpt


class CodexUserMessageTests(unittest.TestCase):
    def test_injected_agents_is_not_a_user_request(self):
        self.assertIsNone(actual_user_text("# AGENTS.md instructions for C:/repo\n<INSTRUCTIONS>"))

    def test_ambient_context_keeps_actual_request(self):
        text = (
            '<in-app-browser-context source="ambient-ui-state">\n'
            'Current URL: https://example.invalid\n'
            '</in-app-browser-context>\n\n## My request:\n작업 계속 진행해줘'
        )
        self.assertEqual(actual_user_text(text), "작업 계속 진행해줘")

    def test_heartbeat_and_environment_are_not_user_requests(self):
        self.assertIsNone(actual_user_text("<heartbeat>scheduled</heartbeat>"))
        self.assertIsNone(actual_user_text("<environment_context>injected</environment_context>"))

    def test_last_user_is_not_injected_agents(self):
        def event(message):
            return json.dumps({
                "timestamp": "2026-10-17T03:00:00Z",
                "payload": {"type": "message", "role": "user", "content": [{"text": message}]},
            })

        lines = [
            event("# AGENTS.md instructions for C:/repo\n<INSTRUCTIONS>"),
            event("<in-app-browser-context>UI</in-app-browser-context>\n## My request:\n실제 질문"),
        ]
        users = [item for item in codex_events(lines) if item[1] == "user"]
        self.assertEqual([item[2] for item in users], ["실제 질문"])

    def test_default_output_hides_session_text(self):
        self.assertEqual(visible_excerpt("secret-like example", False, 800), "[내용 숨김: 19자]")
        self.assertEqual(visible_excerpt("ordinary text", True, 8), "ordinary")


if __name__ == "__main__":
    unittest.main()
