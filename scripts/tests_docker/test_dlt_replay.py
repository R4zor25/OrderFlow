"""Replay one dead-lettered record against a real single-node Kafka broker."""

import os
import shutil
import subprocess
import time
import unittest
import uuid
from pathlib import Path

from scripts.tests_docker.test_kafka_topics import BROKER_ENV, IMAGE, docker

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/dlt-replay.sh'
SOURCE = 'orders.order-events.v1'
DLT = SOURCE + '.inventory-service.DLT'
KAFKA = '/opt/kafka/bin/'


@unittest.skipUnless(shutil.which('docker'), 'Docker is required')
class DltReplayTest(unittest.TestCase):
    def setUp(self):
        self.broker = 'orderflow-replay-' + uuid.uuid4().hex[:10]
        env = [arg for name, value in BROKER_ENV.items() for arg in ('-e', f'{name}={value}')]
        docker('run', '-d', '--name', self.broker, *env, IMAGE)
        self.addCleanup(lambda: docker('rm', '-f', self.broker, check=False))
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if docker('exec', self.broker, KAFKA + 'kafka-broker-api-versions.sh',
                      '--bootstrap-server', 'localhost:9092', check=False).returncode == 0:
                break
            time.sleep(2)
        else:
            self.fail('Kafka broker did not become ready')
        # One DLT partition puts the produced record at a known partition and offset.
        for topic, partitions in ((SOURCE, '3'), (DLT, '1')):
            docker('exec', self.broker, KAFKA + 'kafka-topics.sh', '--bootstrap-server', 'localhost:9092',
                   '--create', '--topic', topic, '--partitions', partitions, '--replication-factor', '1')

    def replay(self, *args):
        env = dict(os.environ, KAFKA_EXEC='docker exec -i ' + self.broker)
        return subprocess.run([str(SCRIPT), *args], env=env, capture_output=True, text=True, timeout=120)

    def test_republishesTheRecordToItsSourceTopic(self):
        subprocess.run(['docker', 'exec', '-i', self.broker, KAFKA + 'kafka-console-producer.sh',
                        '--bootstrap-server', 'localhost:9092', '--topic', DLT,
                        '--reader-property', 'parse.key=true', '--reader-property', 'key.separator=\t'],
                       input='order-1\t{"eventType":"OrderPlaced"}\n', text=True, check=True, capture_output=True)

        result = self.replay(DLT, '0', '0')

        self.assertEqual(result.returncode, 0, result.stderr)
        replayed = docker('exec', self.broker, KAFKA + 'kafka-console-consumer.sh', '--bootstrap-server',
                          'localhost:9092', '--topic', SOURCE, '--from-beginning', '--max-messages', '1',
                          '--timeout-ms', '20000', '--formatter-property', 'print.key=true',
                          '--formatter-property', 'key.separator=\t').stdout
        self.assertEqual(replayed.strip(), 'order-1\t{"eventType":"OrderPlaced"}')

    def test_rejectsInvalidArguments(self):
        self.assertEqual(self.replay().returncode, 2)
        self.assertEqual(self.replay(SOURCE, '0', '0').returncode, 2)
        self.assertEqual(self.replay(DLT, 'one', '0').returncode, 2)
