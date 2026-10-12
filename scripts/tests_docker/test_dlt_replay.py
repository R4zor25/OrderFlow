"""Replay one dead-lettered record against a real single-node Kafka broker."""

import os
import shutil
import subprocess
import tempfile
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

    def produce(self, line):
        subprocess.run(['docker', 'exec', '-i', self.broker, KAFKA + 'kafka-console-producer.sh',
                        '--bootstrap-server', 'localhost:9092', '--topic', DLT,
                        '--reader-property', 'parse.key=true', '--reader-property', 'key.separator=\t'],
                       input=line + '\n', text=True, check=True, capture_output=True)

    def test_republishesExactlyTheRequestedRecordToItsSourceTopic(self):
        self.produce('order-1\t{"eventType":"OrderPlaced","n":1}')
        self.produce('order-2\t{"eventType":"OrderPlaced","n":2}')

        result = self.replay(DLT, '0', '1')

        self.assertEqual(result.returncode, 0, result.stderr)
        replayed = docker('exec', self.broker, KAFKA + 'kafka-console-consumer.sh', '--bootstrap-server',
                          'localhost:9092', '--topic', SOURCE, '--from-beginning', '--timeout-ms', '10000',
                          '--formatter-property', 'print.key=true',
                          '--formatter-property', 'key.separator=\t', check=False).stdout
        self.assertEqual(replayed.strip().splitlines(), ['order-2\t{"eventType":"OrderPlaced","n":2}'])

    def test_failsWithoutClaimingSuccessWhenTheSourceTopicCannotTakeTheRecord(self):
        docker('exec', self.broker, KAFKA + 'kafka-topics.sh', '--bootstrap-server', 'localhost:9092',
               '--delete', '--topic', SOURCE)
        self.produce('order-1\t{"eventType":"OrderPlaced","n":1}')

        result = self.replay(DLT, '0', '0')

        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('replayed', result.stdout)

    def test_refusesAnOffsetThatIsNoLongerInTheLog(self):
        self.produce('order-1\t{"eventType":"OrderPlaced","n":1}')
        self.produce('order-2\t{"eventType":"OrderPlaced","n":2}')
        docker('exec', self.broker, 'sh', '-c', "printf '%s' '{\"partitions\":[{\"topic\":\"" + DLT
               + "\",\"partition\":0,\"offset\":1}],\"version\":1}' > /tmp/delete.json && "
               + KAFKA + 'kafka-delete-records.sh --bootstrap-server localhost:9092 --offset-json-file /tmp/delete.json')

        result = self.replay(DLT, '0', '0')

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(docker('exec', self.broker, KAFKA + 'kafka-console-consumer.sh', '--bootstrap-server',
                                'localhost:9092', '--topic', SOURCE, '--from-beginning', '--timeout-ms', '5000',
                                check=False).stdout.strip(), '')

    def test_rejectsInvalidArguments(self):
        self.assertEqual(self.replay().returncode, 2)
        self.assertEqual(self.replay(SOURCE, '0', '0').returncode, 2)
        self.assertEqual(self.replay(DLT, 'one', '0').returncode, 2)
        self.assertEqual(self.replay(SOURCE + '.DLT', '0', '0').returncode, 2)
        self.assertEqual(self.replay(SOURCE + '.unknown-service.DLT', '0', '0').returncode, 2)


FAKE_DOCKER = """#!/usr/bin/env bash
printf '%s\\0' "$@" >> "$FAKE_LOG"
printf '\\n' >> "$FAKE_LOG"
case "$*" in
  *kafka-console-consumer.sh*) printf '%b' "$FAKE_STDOUT"; printf '%b' "$FAKE_STDERR" >&2; exit "$FAKE_EXIT" ;;
  *kafka-console-producer.sh*) cat >> "$FAKE_PRODUCED" ;;
esac
"""


class DltReplayCommandTest(unittest.TestCase):
    """Runs the script against a fake docker that records its arguments, from a path containing spaces."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='orderflow replay ')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / 'Order Flow'
        (self.root / 'scripts').mkdir(parents=True)
        (self.root / 'infra').mkdir()
        shutil.copy(SCRIPT, self.root / 'scripts/dlt-replay.sh')
        bin_dir = Path(self.temporary.name) / 'bin'
        bin_dir.mkdir()
        (bin_dir / 'docker').write_text(FAKE_DOCKER)
        (bin_dir / 'docker').chmod(0o755)
        self.log = Path(self.temporary.name) / 'docker.log'
        self.produced = Path(self.temporary.name) / 'produced'
        self.env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ['PATH'], FAKE_LOG=str(self.log),
                        FAKE_PRODUCED=str(self.produced), FAKE_STDOUT='', FAKE_STDERR='', FAKE_EXIT='0')
        self.env.pop('KAFKA_EXEC', None)

    def run_script(self, **fake):
        env = dict(self.env, **fake)
        return subprocess.run([str(self.root / 'scripts/dlt-replay.sh'), DLT, '0', '0'], env=env,
                              capture_output=True, text=True, timeout=30)

    def calls(self):
        return [line.split('\0')[:-1] for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def test_defaultComposeCommandKeepsAPathWithSpacesInOneArgument(self):
        result = self.run_script(FAKE_STDOUT='Partition:0\\tOffset:0\\torder-1\\t{}\\n')

        self.assertEqual(result.returncode, 0, result.stderr)
        consumer = self.calls()[0]
        self.assertEqual(consumer[:4], ['compose', '-f', str(self.root / 'infra/docker-compose.yaml'), 'exec'])
        self.assertEqual(self.produced.read_text(), 'order-1\t{}\n')

    def test_reportsConsumerErrorsInsteadOfReplayingTheirOutput(self):
        result = self.run_script(FAKE_STDOUT='usage text\\n', FAKE_STDERR='topic does not exist\\n', FAKE_EXIT='1')

        self.assertEqual(result.returncode, 1)
        self.assertIn('topic does not exist', result.stderr)
        self.assertFalse(self.produced.exists())

    def test_refusesOutputThatIsNotExactlyOneKeyedRecord(self):
        result = self.run_script(FAKE_STDOUT='Partition:0\\tOffset:0\\torder-1\\t{\\nbroken}\\n')

        self.assertEqual(result.returncode, 1)
        self.assertFalse(self.produced.exists())

    def test_refusesARecordFromAnotherOffset(self):
        result = self.run_script(FAKE_STDOUT='Partition:0\\tOffset:7\\torder-9\\t{}\\n')

        self.assertEqual(result.returncode, 1)
        self.assertIn('offset 7', result.stderr)
        self.assertFalse(self.produced.exists())

    def test_readsOnlyTheRequestedOffsetAndSendsSynchronously(self):
        result = self.run_script(FAKE_STDOUT='Partition:0\\tOffset:0\\torder-1\\t{}\\n')

        self.assertEqual(result.returncode, 0, result.stderr)

        consumer, producer = self.calls()[0], self.calls()[1]
        self.assertIn('auto.offset.reset=none', consumer)
        self.assertIn('print.offset=true', consumer)
        self.assertIn('--sync', producer)
        self.assertIn('acks=all', producer)
