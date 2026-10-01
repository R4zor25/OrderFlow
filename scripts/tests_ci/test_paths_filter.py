"""Exercise repository filters through the pinned dorny action and real git diffs."""

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SERVICES = {'catalog-pricing-service', 'order-service', 'inventory-service', 'notification-service'}
CHECKS = {'terraform', 'workflows', 'stack'}


@unittest.skipUnless(os.environ.get('PATHS_FILTER_ACTION'), 'Set PATHS_FILTER_ACTION to the pinned action dist/index.js')
class ChangedServicesTest(unittest.TestCase):
    def selected_services(self, files):
        lines = self.run_filter(files, '.github/filters.yaml')
        for index, line in enumerate(lines):
            if line.startswith('changes<<'):
                return set(json.loads(lines[index + 1]))
        self.fail('Action did not emit the changed-service matrix: ' + '\n'.join(lines))

    def checks(self, files):
        lines = self.run_filter(files, '.github/check-filters.yaml')
        # Every output is written as a "name<<delimiter" block; the next line holds the value.
        return {line.split('<<')[0] for index, line in enumerate(lines)
                if '<<' in line and line.split('<<')[0] in CHECKS and lines[index + 1] == 'true'}

    def run_filter(self, files, filters_file):
        filters = ROOT / filters_file
        self.assertTrue(filters.is_file(), 'Missing filter configuration ' + filters_file)
        with tempfile.TemporaryDirectory(prefix='orderflow-ci-filter-') as temporary:
            repo = Path(temporary)
            def git(*args):
                return subprocess.check_output(['git', *args], cwd=repo, text=True).strip()
            git('init', '-b', 'main')
            git('config', 'user.email', 'ci-test@example.test')
            git('config', 'user.name', 'CI test')
            (repo / 'baseline').write_text('baseline')
            git('add', '.')
            git('commit', '-m', 'baseline')
            baseline = git('rev-parse', 'HEAD')
            for name in files:
                target = repo / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text('changed')
            git('add', '.')
            git('commit', '-m', 'change')
            event = repo / 'event.json'
            event.write_text(json.dumps({'before': baseline, 'after': git('rev-parse', 'HEAD'),
                'repository': {'default_branch': 'main'}}))
            output = repo / 'action-output'
            output.touch()
            environment = dict(os.environ)
            environment.update(GITHUB_EVENT_NAME='push', GITHUB_EVENT_PATH=str(event),
                GITHUB_REF='refs/heads/main', GITHUB_SHA=git('rev-parse', 'HEAD'),
                GITHUB_REPOSITORY='test/orderflow', GITHUB_WORKSPACE=str(repo), GITHUB_OUTPUT=str(output),
                INPUT_BASE='main', INPUT_REF='', INPUT_TOKEN='', INPUT_FILTERS=filters.read_text(),
                INPUT_INITIAL_FETCH_DEPTH='100', INPUT_PREDICATE_QUANTIFIER='some', INPUT_LIST_FILES='none')
            result = subprocess.run(['node', os.environ['PATHS_FILTER_ACTION']], cwd=repo,
                                    env=environment, text=True, capture_output=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            return output.read_text().splitlines()

    def test_inventory_change_selects_only_inventory(self):
        self.assertEqual(self.selected_services(['services/inventory-service/src/main/java/Stock.java']),
                         {'inventory-service'})

    def test_contract_change_selects_every_service(self):
        self.assertEqual(self.selected_services(['contracts/events/order-placed.json']), SERVICES)

    def test_workflow_change_selects_every_service(self):
        self.assertEqual(self.selected_services(['.github/workflows/ci.yml']), SERVICES)

    def test_root_build_change_selects_every_service(self):
        self.assertEqual(self.selected_services(['pom.xml']), SERVICES)

    def test_filter_configuration_change_selects_every_service(self):
        self.assertEqual(self.selected_services(['.github/filters.yaml']), SERVICES)

    def test_infrastructure_change_selects_no_service(self):
        self.assertEqual(self.selected_services(['infra/docker-compose.yaml']), set())

    def test_script_change_selects_no_service(self):
        self.assertEqual(self.selected_services(['scripts/smoke.sh']), set())

    def test_deploy_workflow_change_selects_no_service(self):
        self.assertEqual(self.selected_services(['.github/workflows/deploy.yml']), set())

    def test_documentation_change_selects_no_service(self):
        self.assertEqual(self.selected_services(['README.md', 'docs/ARCHITECTURE.md']), set())

    def test_two_service_changes_select_exactly_those_services(self):
        self.assertEqual(self.selected_services(['services/order-service/pom.xml',
                          'services/notification-service/Dockerfile']), {'order-service', 'notification-service'})

    def test_terraform_change_selects_terraform_and_stack(self):
        self.assertEqual(self.checks(['infra/terraform/rds.tf']), {'terraform', 'stack'})

    def test_compose_change_selects_stack(self):
        self.assertEqual(self.checks(['infra/docker-compose.yaml']), {'stack'})

    def test_script_change_selects_stack(self):
        self.assertEqual(self.checks(['scripts/smoke.sh']), {'stack'})

    def test_deploy_workflow_change_selects_workflows(self):
        self.assertEqual(self.checks(['.github/workflows/deploy.yml']), {'workflows'})

    def test_ci_workflow_change_selects_every_check(self):
        self.assertEqual(self.checks(['.github/workflows/ci.yml']), CHECKS)

    def test_service_change_selects_stack(self):
        self.assertEqual(self.checks(['services/order-service/pom.xml']), {'stack'})

    def test_documentation_change_selects_no_check(self):
        self.assertEqual(self.checks(['README.md']), set())
