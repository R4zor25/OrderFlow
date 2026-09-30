"""Static checks for the CI workflow that actionlint does not express."""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / '.github/workflows/ci.yml').read_text()


class CiWorkflowTest(unittest.TestCase):
    def test_only_pull_requests_cancel_runs_in_progress(self):
        self.assertIn("cancel-in-progress: ${{ github.event_name == 'pull_request' }}", WORKFLOW)

    def test_every_action_is_pinned_to_a_commit(self):
        for action in re.findall(r'uses:\s*(\S+)', WORKFLOW):
            self.assertRegex(action, r'@[0-9a-f]{40}$', action)
