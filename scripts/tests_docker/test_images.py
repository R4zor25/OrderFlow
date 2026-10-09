"""Build and run all service images against disposable real dependencies."""

import json
import re
import subprocess
import sys
import unittest
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from benchmark_services import ROOT, command, wait_healthy, cleanup_resources, http_get


METASPACE_MIB = 224
RDS_CA_BUNDLE = '/application/rds-global-bundle.pem'
JVM_FLAGS = ('-XX:MaxRAMPercentage=45', '-XX:+ExitOnOutOfMemoryError', f'-XX:MaxMetaspaceSize={METASPACE_MIB}m')


class ServiceImageTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.project = 'orderflow-images-' + uuid.uuid4().hex[:10]
        cls.compose = ['docker', 'compose', '-p', cls.project, '-f', str(ROOT / 'scripts/benchmark-compose.yaml')]
        cls.containers = []
        cls.images = []
        cls.infrastructure_started = False
        cls.output = ROOT / 'target/image-verification' / cls.project
        cls.output.mkdir(parents=True)

    @classmethod
    def tearDownClass(cls):
        try:
            cleanup_resources(cls.containers, cls.compose)
            remaining = command('docker', 'ps', '-a', '--filter',
                                'label=com.docker.compose.project=' + cls.project, '--format', '{{.ID}}')
            if remaining:
                raise AssertionError('Dependency containers remain after cleanup: ' + remaining)
        finally:
            for image in cls.images:
                command('docker', 'image', 'rm', image)

    def verify_service(self, service, port, schema):
        image = self.project + ':' + service
        build_log = self.output / (service + '-build.log')
        with build_log.open('w') as log:
            build = subprocess.run(['docker', 'build', '--progress=plain', '-t', image,
                                    str(ROOT / 'services' / service)], stdout=log,
                                   stderr=subprocess.STDOUT, text=True, timeout=1800)
        self.assertEqual(build.returncode, 0, build_log.read_text()[-3000:])
        self.images.append(image)
        info = json.loads(command('docker', 'image', 'inspect', image))[0]
        (self.output / (service + '-image.json')).write_text(json.dumps(info, indent=2))
        # Two malloc arenas keep native memory from varying by tens of MiB between runs of the same workload.
        self.assertIn('MALLOC_ARENA_MAX=2', info['Config']['Env'])
        if not self.infrastructure_started:
            type(self).infrastructure_started = True
            command(*self.compose, 'up', '-d', '--wait', '--wait-timeout', '120',
                    'postgres', 'redis', 'kafka', timeout=240)
        name = self.project + '-' + service
        self.containers.append(name)
        spring = service != 'notification-service'
        # Same limits as the ECS task definitions and Compose.
        memory_mib = 768 if spring else 512
        env = ({'SPRING_DATASOURCE_URL': f'jdbc:postgresql://postgres:5432/orderflow?currentSchema={schema}',
                'SPRING_DATASOURCE_USERNAME': 'orderflow', 'SPRING_DATASOURCE_PASSWORD': 'benchmark-only',
                'SPRING_DATA_REDIS_HOST': 'redis', 'SPRING_KAFKA_BOOTSTRAP_SERVERS': 'kafka:9092'} if spring else
               {'DATASOURCES_DEFAULT_URL': f'jdbc:postgresql://postgres:5432/orderflow?currentSchema={schema}',
                'DATASOURCES_DEFAULT_USERNAME': 'orderflow', 'DATASOURCES_DEFAULT_PASSWORD': 'benchmark-only',
                'KAFKA_BOOTSTRAP_SERVERS': 'kafka:9092', 'KAFKA_HEALTH_ENABLED': 'true',
                'NOTIFICATION_MESSAGING_ENABLED': 'true', 'NOTIFICATION_CHANNEL': 'LOG'})
        if service in ('order-service', 'inventory-service'):
            prefix = 'ORDER' if service == 'order-service' else 'INVENTORY'
            env.update({prefix + '_MESSAGING_ENABLED': 'true', prefix + '_OUTBOX_RELAY_ENABLED': 'true'})
        args = ['docker', 'create', '--name', name, '--network', self.project + '_default',
                '--memory', f'{memory_mib}m', '--cpus', '1', '-p', f'127.0.0.1::{port}']
        for key, value in env.items():
            args += ['-e', f'{key}={value}']
        command(*args, image)
        try:
            command('docker', 'start', name)
            base = 'http://' + command('docker', 'port', name, f'{port}/tcp')
            health = '/actuator/health' if spring else '/health'
            wait_healthy(name, base + health + '/readiness', 120)
            _, body = http_get(base + health + '/liveness')
            self.assertEqual(json.loads(body)['status'], 'UP')
            self.assertNotEqual(command('docker', 'exec', name, 'id', '-u'), '0')
            proc = command('docker', 'exec', name, 'sh', '-c', 'tr "\\000" " " < /proc/1/cmdline')
            self.assertTrue(proc.startswith('java '), proc)
            for flag in JVM_FLAGS:
                self.assertIn(flag, proc)
            bundle = command('docker', 'exec', name, 'grep', '-c', 'BEGIN CERTIFICATE', RDS_CA_BUNDLE)
            self.assertGreater(int(bundle), 0)
            no_build_tools = subprocess.run(['docker', 'exec', '--user', '0', name, 'sh', '-c',
                'command -v javac || command -v mvn || test -e /application/mvnw || test -d /application/src || test -d /root/.m2'],
                capture_output=True, text=True)
            self.assertEqual(no_build_tools.returncode, 1, no_build_tools.stdout + no_build_tools.stderr)
            heap = command('docker', 'exec', name, 'java', *JVM_FLAGS,
                           '-XX:+PrintFlagsFinal', '-version', include_stderr=True)
            (self.output / (service + '-runtime.txt')).write_text('PID 1: ' + proc + '\n' + heap + '\n')
            maximum = re.search(r'^\s*size_t MaxHeapSize\s*=\s*(\d+)', heap, re.MULTILINE)
            self.assertIsNotNone(maximum, heap)
            # The JVM aligns the heap, so 45% of the limit lands within a few MiB of the exact value.
            self.assertLess(abs(int(maximum[1]) - 0.45 * memory_mib * 2**20), 4 * 2**20, maximum[1])
            metaspace = re.search(r'^\s*size_t MaxMetaspaceSize\s*=\s*(\d+)', heap, re.MULTILINE)
            self.assertIsNotNone(metaspace, heap)
            self.assertEqual(int(metaspace[1]), METASPACE_MIB * 2**20)
            command('docker', 'stop', '--time', '30', name, timeout=40)
            state = json.loads(command('docker', 'inspect', '-f', '{{json .State}}', name))
            self.assertIn(state['ExitCode'], (0, 143), state)
            self.assertFalse(state['OOMKilled'], state)
        finally:
            (self.output / (service + '-runtime.log')).write_text(command('docker', 'logs', name, include_stderr=True))
            command('docker', 'rm', '-f', name)
            self.containers.remove(name)

    def test_catalog_image_runs_as_non_root_java_and_stops_cleanly(self):
        self.verify_service('catalog-pricing-service', 8081, 'catalog')

    def test_order_image_runs_as_non_root_java_and_stops_cleanly(self):
        self.verify_service('order-service', 8082, 'orders')

    def test_inventory_image_runs_as_non_root_java_and_stops_cleanly(self):
        self.verify_service('inventory-service', 8083, 'inventory')

    def test_notification_image_runs_as_non_root_java_and_stops_cleanly(self):
        self.verify_service('notification-service', 8084, 'notifications')
