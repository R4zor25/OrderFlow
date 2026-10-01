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

    def test_result_requires_every_job(self):
        section = WORKFLOW[WORKFLOW.index('\njobs:\n'):]
        jobs = set(re.findall(r'^  ([a-z][a-z-]*):\n', section, re.MULTILINE)) - {'result'}
        needs = re.search(r'^  result:\n(?:.*\n)*?    needs: \[([^\]]*)\]', WORKFLOW, re.MULTILINE)
        self.assertIsNotNone(needs)
        self.assertEqual({job.strip() for job in needs[1].split(',')}, jobs)

    def test_docker_images_are_pinned_by_digest(self):
        images = re.findall(r'(rhysd/actionlint\S*)', WORKFLOW)
        self.assertTrue(images, 'actionlint image missing from the workflow')
        for image in images:
            self.assertIn('@sha256:', image)
