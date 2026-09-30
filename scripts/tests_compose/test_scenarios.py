"""Execute the public scenario scripts against a disposable real stack."""

import re
import subprocess
import shutil

import test_stack

ROOT = test_stack.ROOT


class ScenarioScriptsTest(test_stack.ComposeStackTest):
    def test_stack_starts_and_confirms_order_with_stock_and_smtp_notification(self):
        for name in ('smoke.sh', 'oversell-test.sh'):
            self.assertTrue((ROOT / 'scripts' / name).is_file(), f'Missing public scenario script: {name}')
        super().test_stack_starts_and_confirms_order_with_stock_and_smtp_notification()
        environment = dict(self.env)
        environment.update(BASE_URL=self.base_url('order-service', 8082),
                           MAILPIT_URL=self.base_url('mailpit', 8025),
                           COMPOSE_FILE=str(self.export / 'infra/docker-compose.yaml'),
                           COMPOSE_ENV_FILE=str(self.export / 'test.env'),
                           COMPOSE_PROJECT_NAME=self.project, SQL_MODE='compose',
                           KEYCLOAK_URL=self.base_url('keycloak', 8080),
                           DEMO_USER_PASSWORD='password-test',
                           TIMEOUT_SECONDS='90')
        assignment = self.invoke('exec', '-T', 'kafka', '/opt/kafka/bin/kafka-consumer-groups.sh',
                                 '--bootstrap-server', 'kafka:9092', '--group', 'notification-service',
                                 '--describe', '--members', '--verbose')
        # Notification runs one consumer thread per partition of each topic; together they own every partition.
        for topic in ('orders.order-events.v1', 'inventory.stock-events.v1'):
            matches = re.findall(re.escape(topic) + r':([0-9,]+)', assignment.replace(' ', ''))
            owned = {int(partition) for match in matches for partition in match.split(',')}
            self.assertEqual(owned, {0, 1, 2}, assignment)
        self.run_script('smoke.sh', environment)
        if shutil.which('psql'):
            host, port = self.invoke('port', 'postgres', '5432').splitlines()[0].rsplit(':', 1)
            direct = dict(environment, SQL_MODE='psql', PGHOST=host, PGPORT=port,
                          PGUSER='orderflow', PGDATABASE='orderflow', PGPASSWORD=self.password)
            self.run_script('smoke.sh', direct)
        disconnected = dict(environment, BASE_URL='http://127.0.0.1:1')
        result = subprocess.run([str(ROOT / 'scripts/smoke.sh')], cwd=ROOT,
                                env=disconnected, capture_output=True, text=True, timeout=30)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('HTTP transport failure', result.stderr)
        self.invoke('exec', '-T', 'postgres', 'psql', '-U', 'orderflow', '-d', 'orderflow', '-v',
                    'ON_ERROR_STOP=1', '-c', "UPDATE inventory.stock_item SET on_hand=10 WHERE sku='CHAIR-002' AND reserved=0")
        environment.update(SKU='CHAIR-002', EXPECTED_AVAILABLE='10', REQUESTS='50')
        result = self.run_script('oversell-test.sh', environment)
        self.assertIn('confirmed=10 rejected=40', result.stdout)
        result = subprocess.run([str(ROOT / 'scripts/oversell-test.sh')], cwd=ROOT,
                                env=environment, capture_output=True, text=True, timeout=30)
        self.assertNotEqual(result.returncode, 0, 'A depleted fixture must fail before sending more orders')
        self.assertIn('Expected 10 available units, found 0', result.stderr)

    def run_script(self, name, environment):
        result = subprocess.run([str(ROOT / 'scripts' / name)], cwd=ROOT, env=environment,
                                capture_output=True, text=True, timeout=240)
        (self.output / (name + '-' + environment['SQL_MODE'] + '.log')).write_text(result.stdout + result.stderr)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result
