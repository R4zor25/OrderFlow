import sys
import unittest
import subprocess
import http.client
from unittest.mock import patch
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from benchmark_services import startup_seconds, memory_mib, rss_mib, wait_healthy, command
import benchmark_services


class MeasurementParsingTest(unittest.TestCase):
    def test_parses_distinct_framework_units(self):
        self.assertEqual(startup_seconds('Started OrderServiceApplication in 4.125 seconds (process running for 4.9)', 'spring'), 4.125)
        self.assertEqual(startup_seconds('Startup completed in 1234ms. Server Running: http://localhost:8084', 'micronaut'), 1.234)

    def test_does_not_substitute_process_uptime_for_framework_startup(self):
        with self.assertRaises(ValueError):
            startup_seconds('process running for 4.9 seconds', 'spring')

    def test_converts_binary_and_decimal_docker_units(self):
        self.assertEqual(memory_mib('256MiB / 768MiB'), 256)
        self.assertEqual(memory_mib('1GiB / 2GiB'), 1024)
        self.assertAlmostEqual(memory_mib('100MB / 768MB'), 95.367431640625)

    def test_reads_process_rss_in_kib_not_virtual_size(self):
        self.assertEqual(rss_mib('VmSize:\t999999 kB\nVmRSS:\t262144 kB\n'), 256)

    def test_rejects_missing_or_invalid_measurements(self):
        for value in ('', 'unknown', '12unknown / 1GiB'):
            with self.assertRaises(ValueError):
                memory_mib(value)
        with self.assertRaises(ValueError):
            rss_mib('VmSize: 999 kB')


class OrchestrationTest(unittest.TestCase):
    def test_waits_through_connection_closed_before_http_is_ready(self):
        with patch('benchmark_services.command', return_value='true'), \
             patch('benchmark_services.http_get', side_effect=[http.client.RemoteDisconnected(), (0.1, b'{"status":"UP"}')]), \
             patch('benchmark_services.time.sleep'):
            wait_healthy('owned-container', 'http://localhost/health', 2)

    def test_preserves_stderr_in_container_evidence(self):
        result = subprocess.CompletedProcess([], 0, 'stdout log', 'stderr diagnostic')
        with patch('benchmark_services.subprocess.run', return_value=result):
            self.assertIn('stderr diagnostic', command('docker', 'logs', 'owned-container', include_stderr=True))

    def test_cleanup_attempts_down_after_remove_failure_and_reports_failure(self):
        outcomes = [subprocess.CompletedProcess([], 1, '', 'remove failed'),
                    subprocess.CompletedProcess([], 0, '', '')]
        with patch('benchmark_services.subprocess.run', side_effect=outcomes) as run:
            with self.assertRaises(RuntimeError):
                benchmark_services.cleanup_resources(['owned-container'], ['docker', 'compose', '-p', 'owned-project'])
            self.assertEqual(run.call_count, 2)
            self.assertEqual(run.call_args.kwargs['timeout'], 120)

    def test_cleanup_timeout_does_not_skip_stack_removal(self):
        outcomes = [subprocess.TimeoutExpired('docker rm', 120), subprocess.CompletedProcess([], 0, '', '')]
        with patch('benchmark_services.subprocess.run', side_effect=outcomes) as run:
            with self.assertRaises(RuntimeError):
                benchmark_services.cleanup_resources(['owned-container'], ['docker', 'compose', '-p', 'owned-project'])
            self.assertEqual(run.call_count, 2)


if __name__ == '__main__':
    unittest.main()
