"""Run the Terraform topic-init command against a real single-node Kafka broker."""

import re
import shutil
import subprocess
import time
import unittest
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
IMAGE = 'apache/kafka:4.3.1'
TOPICS = ('orders.order-events.v1', 'inventory.stock-events.v1',
          'orders.order-events.v1.inventory-service.DLT', 'inventory.stock-events.v1.order-service.DLT',
          'orders.order-events.v1.notification-service.DLT', 'inventory.stock-events.v1.notification-service.DLT')
# Same single-node settings as the ECS broker, advertised on localhost like containers of one awsvpc task.
BROKER_ENV = {
    'KAFKA_NODE_ID': '1',
    'KAFKA_PROCESS_ROLES': 'broker,controller',
    'KAFKA_CONTROLLER_QUORUM_VOTERS': '1@localhost:9093',
    'KAFKA_LISTENERS': 'PLAINTEXT://:9092,CONTROLLER://:9093',
    'KAFKA_ADVERTISED_LISTENERS': 'PLAINTEXT://localhost:9092',
    'KAFKA_LISTENER_SECURITY_PROTOCOL_MAP': 'PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT',
    'KAFKA_INTER_BROKER_LISTENER_NAME': 'PLAINTEXT',
    'KAFKA_CONTROLLER_LISTENER_NAMES': 'CONTROLLER',
    'KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR': '1',
    'KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR': '1',
    'KAFKA_TRANSACTION_STATE_LOG_MIN_ISR': '1',
    'KAFKA_AUTO_CREATE_TOPICS_ENABLE': 'false',
}


def docker(*args, check=True):
    return subprocess.run(['docker', *args], check=check, capture_output=True, text=True)


def topics_command():
    source = (ROOT / 'infra/terraform/ecs-infra.tf').read_text()
    topics = re.search(r'kafka_topics\s*=\s*\[([^\]]*)\]', source)
    match = re.search(r'kafka_topics_command\s*=\s*"(.*)"\n', source)
    if topics is None or match is None:
        raise AssertionError('kafka_topics or kafka_topics_command is missing from ecs-infra.tf')
    # Terraform interpolates the topic list into the command; the test does the same.
    names = re.findall(r'"([^"]+)"', topics.group(1))
    return match.group(1).replace('${join(" ", local.kafka_topics)}', ' '.join(names))


@unittest.skipUnless(shutil.which('docker'), 'Docker is required')
class KafkaTopicsInitTest(unittest.TestCase):
    def setUp(self):
        self.broker = 'orderflow-topics-' + uuid.uuid4().hex[:10]
        env = [arg for name, value in BROKER_ENV.items() for arg in ('-e', f'{name}={value}')]
        docker('run', '-d', '--name', self.broker, *env, IMAGE)
        self.addCleanup(lambda: docker('rm', '-f', self.broker, check=False))
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            ready = docker('exec', self.broker, '/opt/kafka/bin/kafka-broker-api-versions.sh',
                           '--bootstrap-server', 'localhost:9092', check=False)
            if ready.returncode == 0:
                return
            time.sleep(2)
        self.fail('Kafka broker did not become ready')

    def run_init(self):
        # Sharing the broker's network namespace mirrors containers of one awsvpc task.
        return docker('run', '--rm', '--network', 'container:' + self.broker, '--entrypoint', 'sh',
                      IMAGE, '-c', topics_command(), check=False)

    def test_createsAllTopicsAndIsIdempotent(self):
        first = self.run_init()
        second = self.run_init()

        self.assertEqual(first.returncode, 0, first.stderr)
        self.assertEqual(second.returncode, 0, second.stderr)
        for topic in TOPICS:
            described = docker('exec', self.broker, '/opt/kafka/bin/kafka-topics.sh',
                               '--bootstrap-server', 'localhost:9092', '--describe', '--topic', topic).stdout
            self.assertIn('PartitionCount: 3', described, topic)
            self.assertIn('ReplicationFactor: 1', described, topic)
            configs = docker('exec', self.broker, '/opt/kafka/bin/kafka-configs.sh', '--bootstrap-server',
                             'localhost:9092', '--describe', '--entity-type', 'topics', '--entity-name', topic).stdout
            self.assertRegex(configs, r'(?m)^\s*retention\.ms=604800000\b', topic)
            self.assertRegex(configs, r'(?m)^\s*segment\.ms=86400000\b', topic)
