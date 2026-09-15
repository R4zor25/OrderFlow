import sys
import unittest
import subprocess
import http.client
import io
import json
import urllib.error
import urllib.parse
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import MagicMock, patch
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


class KeycloakTokenTest(unittest.TestCase):
    def test_requests_customer_password_grant_from_realm_token_endpoint(self):
        response = MagicMock()
        response.__enter__.return_value.read.return_value = b'{"access_token":"customer-token"}'
        with patch('benchmark_services.urllib.request.urlopen', return_value=response) as urlopen:
            token = benchmark_services.keycloak_token('http://127.0.0.1:50000', benchmark_services.CUSTOMER_GRANT)
        self.assertEqual(token, 'customer-token')
        request = urlopen.call_args.args[0]
        self.assertEqual(request.full_url, 'http://127.0.0.1:50000/realms/orderflow/protocol/openid-connect/token')
        self.assertEqual(urllib.parse.parse_qs(request.data.decode()), {
            'grant_type': ['password'], 'client_id': ['orderflow-cli'],
            'username': ['standard'], 'password': ['benchmark-only']})

    def test_service_grant_uses_order_service_client_credentials(self):
        self.assertEqual(benchmark_services.SERVICE_GRANT, {
            'grant_type': 'client_credentials', 'client_id': 'order-service', 'client_secret': 'benchmark-only'})

    def test_reports_keycloak_error_body(self):
        error = urllib.error.HTTPError('http://keycloak', 401, 'Unauthorized', {},
                                       io.BytesIO(b'{"error":"invalid_client"}'))
        with patch('benchmark_services.urllib.request.urlopen', side_effect=error):
            with self.assertRaisesRegex(RuntimeError, 'HTTP 401.*invalid_client'):
                benchmark_services.keycloak_token('http://keycloak', benchmark_services.SERVICE_GRANT)

    def test_rejects_response_without_access_token(self):
        response = MagicMock()
        response.__enter__.return_value.read.return_value = b'{}'
        with patch('benchmark_services.urllib.request.urlopen', return_value=response):
            with self.assertRaises(ValueError):
                benchmark_services.keycloak_token('http://keycloak', benchmark_services.SERVICE_GRANT)

    def test_http_get_sends_bearer_token(self):
        response = MagicMock()
        response.__enter__.return_value.status = 200
        response.__enter__.return_value.read.return_value = b'{}'
        with patch('benchmark_services.urllib.request.urlopen', return_value=response) as urlopen:
            benchmark_services.http_get('http://localhost/api/v1/orders', token='customer-token')
        self.assertEqual(urlopen.call_args.args[0].get_header('Authorization'), 'Bearer customer-token')


class AuthenticatedOrderReadTest(unittest.TestCase):
    def test_fetches_customer_token_after_health_and_sends_it_with_first_order_read(self):
        calls = MagicMock()
        calls.command.return_value = '127.0.0.1:12345'
        calls.keycloak_token.return_value = 'customer-token'
        calls.http_get.return_value = (0.1, b'{}')
        options = SimpleNamespace(timeout=10)
        with TemporaryDirectory() as directory, \
             patch('benchmark_services.command', calls.command), \
             patch('benchmark_services.wait_healthy', calls.wait_healthy), \
             patch('benchmark_services.keycloak_token', calls.keycloak_token), \
             patch('benchmark_services.http_get', calls.http_get):
            benchmark_services.run_variant('spring', 'prime', 'test', 'image', Path(directory), options, [],
                                           'http://127.0.0.1:50000', prime=True)
        order = [name for name, _, _ in calls.mock_calls if name in ('wait_healthy', 'keycloak_token', 'http_get')]
        self.assertEqual(order, ['wait_healthy', 'keycloak_token', 'http_get'])
        calls.keycloak_token.assert_called_once_with('http://127.0.0.1:50000', benchmark_services.CUSTOMER_GRANT)
        self.assertEqual(calls.http_get.call_args.kwargs['token'], 'customer-token')
        create = next(call.args for call in calls.command.call_args_list if call.args[:2] == ('docker', 'create'))
        self.assertIn('ORDER_JWT_JWK_SET_URI=http://keycloak:8080/realms/orderflow/protocol/openid-connect/certs',
                      create)
        self.assertIn('ORDER_JWT_ISSUER_URI=http://localhost:8180/realms/orderflow', create)
        self.assertIn('ORDER_TOKEN_URI=http://keycloak:8080/realms/orderflow/protocol/openid-connect/token', create)
        self.assertIn('ORDER_SERVICE_CLIENT_SECRET=benchmark-only', create)


if __name__ == '__main__':
    unittest.main()
