"""Verify the public Compose stack from a tracked-files-only copy."""

import json
import os
import shutil
import subprocess
import tempfile
import time
import unittest
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APPLICATIONS = {'catalog-pricing-service': 8081, 'order-service': 8082,
                'inventory-service': 8083, 'notification-service': 8084}


class ComposeStackTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='orderflow-compose-')
        self.export = Path(self.temporary.name)
        tracked = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode().split('\0')
        for relative in filter(None, tracked):
            source = ROOT / relative
            if source.is_file():
                destination = self.export / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, destination)
        self.project = 'orderflow-compose-' + uuid.uuid4().hex[:10]
        self.output = ROOT / 'target/compose-verification' / self.project
        self.output.mkdir(parents=True)
        self.env = dict(os.environ)
        self.env.pop('POSTGRES_PASSWORD', None)
        self.env.pop('COMPOSE_PROFILES', None)
        self.password = 'test-' + uuid.uuid4().hex
        envfile = self.export / 'test.env'
        envfile.write_text('POSTGRES_PASSWORD=' + self.password + '\n')
        override = self.export / 'ports.yaml'
        ports = {'postgres': [5432], 'redis': [6379], 'kafka': [], 'kafka-ui': [8080], 'mailpit': [8025]}
        ports.update({service: [port] for service, port in APPLICATIONS.items()})
        override.write_text('services:\n' + ''.join(
            f'  {service}:\n    ports: !override ' + json.dumps([f'127.0.0.1::{port}' for port in numbers]) + '\n'
            for service, numbers in ports.items()))
        self.compose = ['docker', 'compose', '--env-file', str(envfile), '-p', self.project,
                        '-f', str(self.export / 'infra/docker-compose.yaml')]
        self.started = False

    def invoke(self, *args, timeout=120):
        result = subprocess.run([*self.compose, *args], cwd=self.export, env=self.env,
                                capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(result.stderr[-3000:].replace(self.password, '<redacted>'))
        return result.stdout.strip()

    def tearDown(self):
        try:
            if self.started:
                logs = self.invoke('logs', '--no-color')
                (self.output / 'containers.log').write_text(logs.replace(self.password, '<redacted>'))
        finally:
            try:
                if self.started:
                    self.invoke('down', '--volumes', '--remove-orphans', '--rmi', 'local', timeout=180)
                    for kind, arguments in [('containers', ['ps', '-a']), ('networks', ['network', 'ls']),
                                             ('volumes', ['volume', 'ls'])]:
                        remaining = subprocess.check_output(['docker', *arguments, '--filter',
                            'label=com.docker.compose.project=' + self.project, '--format', '{{.ID}}' if kind == 'containers' else '{{.Name}}'], text=True).strip()
                        self.assertFalse(remaining, f'{kind} remain after cleanup: {remaining}')
            finally:
                self.temporary.cleanup()

    def base_url(self, service, port):
        return 'http://' + self.invoke('port', service, str(port)).splitlines()[0]

    def read_json(self, url):
        with urllib.request.urlopen(url, timeout=5) as response:
            return json.load(response)

    def await_result(self, probe, timeout=60):
        deadline = time.monotonic() + timeout
        last = None
        while time.monotonic() < deadline:
            last = probe()
            if last:
                return last
            time.sleep(0.25)
        self.fail('Expected asynchronous result not observed: ' + str(last))

    def test_stack_starts_and_confirms_order_with_stock_and_smtp_notification(self):
        model = json.loads(self.invoke('config', '--format', 'json'))
        self.assertTrue(APPLICATIONS.keys() <= model['services'].keys(), 'Compose is missing application services')
        self.compose += ['-f', str(self.export / 'ports.yaml')]
        self.started = True
        started = time.perf_counter()
        with (self.output / 'startup.log').open('w') as log:
            result = subprocess.run([*self.compose, 'up', '--build', '-d', '--wait', '--wait-timeout', '300'],
                                    cwd=self.export, env=self.env, stdout=log, stderr=subprocess.STDOUT, timeout=1800)
        self.assertEqual(result.returncode, 0, (self.output / 'startup.log').read_text()[-3000:])
        elapsed = time.perf_counter() - started
        for service, port in APPLICATIONS.items():
            health = '/health' if service == 'notification-service' else '/actuator/health'
            self.assertEqual(self.read_json(self.base_url(service, port) + health + '/readiness')['status'], 'UP')
        order_base = self.base_url('order-service', 8082)
        request = urllib.request.Request(order_base + '/api/v1/orders', method='POST',
            data=json.dumps({'customerId': '11111111-1111-4111-8111-111111111111',
                             'lines': [{'sku': 'CHAIR-001', 'quantity': 2}]}).encode(),
            headers={'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4())})
        with urllib.request.urlopen(request, timeout=10) as response:
            self.assertEqual(response.status, 202)
            placed = json.load(response)
        order_id = str(uuid.UUID(placed['id']))
        self.assertEqual(placed['status'], 'PENDING')
        confirmed = self.await_result(lambda: (details if (details := self.read_json(
            order_base + '/api/v1/orders/' + order_id))['status'] == 'CONFIRMED' else None))
        self.assertEqual(confirmed['lines'][0]['quantity'], 2)
        reservation = self.invoke('exec', '-T', 'postgres', 'psql', '-U', 'orderflow', '-d', 'orderflow', '-Atc',
            f"select quantity || ':' || status from inventory.reservation where order_id='{order_id}'::uuid")
        self.assertEqual(reservation, '2:RESERVED')
        mail_base = self.base_url('mailpit', 8025)
        mail = self.await_result(lambda: next((message for message in self.read_json(mail_base + '/api/v1/messages')['messages']
            if message['Subject'] == 'Order confirmed ' + order_id), None))
        self.assertEqual(mail['To'][0]['Address'], 'consumer.one@example.test')
        rows = self.invoke('exec', '-T', 'postgres', 'psql', '-U', 'orderflow', '-d', 'orderflow', '-Atc',
            f"select count(*) from notifications.notification where order_id='{order_id}'::uuid and type='OrderConfirmed' and channel='SMTP'")
        self.assertEqual(rows, '1')
        report = {'source_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                  'up_build_wait_seconds': elapsed, 'order_id': order_id, 'initial_status': placed['status'],
                  'final_status': confirmed['status'], 'reservation': reservation, 'mail_subject': mail['Subject'],
                  'notification_rows': int(rows), 'conditions': 'tracked-files-only copy, fresh volumes, local Docker build caches may be warm'}
        (self.output / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
