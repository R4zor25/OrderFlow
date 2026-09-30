"""Execute the public scenario scripts against a disposable real stack."""

import json
import re
import subprocess
import shutil
import urllib.request

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
        self.assert_memory_headroom()

    def assert_memory_headroom(self):
        # After the smoke and oversell traffic; a missing cgroup v2 file fails the invoke instead of passing.
        memory = {}
        for service, port in test_stack.APPLICATIONS.items():
            peak = int(self.invoke('exec', '-T', service, 'cat', '/sys/fs/cgroup/memory.peak'))
            limit = int(self.invoke('exec', '-T', service, 'cat', '/sys/fs/cgroup/memory.max'))
            stat = dict(line.split() for line in
                        self.invoke('exec', '-T', service, 'cat', '/sys/fs/cgroup/memory.stat').splitlines())
            memory[service] = {'peak_mib': round(peak / 2**20, 1), 'limit_mib': round(limit / 2**20, 1),
                               'anon_mib': round(int(stat['anon']) / 2**20, 1),
                               'file_mib': round(int(stat['file']) / 2**20, 1),
                               'metaspace_mib': round(self.metaspace_bytes(service, port) / 2**20, 1)}
        (self.output / 'memory.json').write_text(json.dumps(memory, indent=2) + '\n')
        for service, values in memory.items():
            self.assertLessEqual(values['peak_mib'], 0.85 * values['limit_mib'], f'{service}: {values}')

    def metaspace_bytes(self, service, port):
        base = self.base_url(service, port)
        if service == 'notification-service':
            with urllib.request.urlopen(base + '/prometheus', timeout=10) as response:
                body = response.read().decode()
            metaspace = r'^jvm_memory_used_bytes\{(?=[^}]*id="Metaspace")[^}]*\} (\S+)$'
            match = re.search(metaspace, body, re.MULTILINE)
            self.assertIsNotNone(match, 'Metaspace gauge missing from notification /prometheus')
            return float(match[1])
        return self.read_json(base + '/actuator/metrics/jvm.memory.used?tag=id:Metaspace')['measurements'][0]['value']

    def run_script(self, name, environment):
        result = subprocess.run([str(ROOT / 'scripts' / name)], cwd=ROOT, env=environment,
                                capture_output=True, text=True, timeout=240)
        (self.output / (name + '-' + environment['SQL_MODE'] + '.log')).write_text(result.stdout + result.stderr)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result
