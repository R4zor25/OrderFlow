import sys
import unittest
import json
import threading
import time
from types import SimpleNamespace
from unittest.mock import patch
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import benchmark_quote
from benchmark_quote import percentile, summarize_requests, validate_quote


class QuoteMeasurementTest(unittest.TestCase):
    def test_p95_uses_nearest_rank_and_does_not_silently_drop_tail(self):
        self.assertEqual(percentile(list(range(1, 101)), 95), 95)
        self.assertEqual(percentile([9, 1, 3], 95), 9)

    def test_errors_are_excluded_from_success_latency_but_included_in_total_rate(self):
        result = summarize_requests([0.1, 0.2], {"HTTP 503": 2}, 2)
        self.assertEqual(result["successful_requests"], 2)
        self.assertEqual(result["errors"], 2)
        self.assertEqual(result["success_rps"], 1)
        self.assertEqual(result["attempt_rps"], 2)
        self.assertEqual(result["p95_ms"], 200)

    def test_failed_run_does_not_invent_zero_latency(self):
        result = summarize_requests([], {"timeout": 1}, 1)
        self.assertIsNone(result["p95_ms"])
        with self.assertRaises(ValueError):
            summarize_requests([], {}, 0)

    def test_http_200_with_wrong_quote_is_not_a_success(self):
        valid = {"currency": "EUR", "lines": [{"sku": "CHAIR-001", "quantity": 25}],
                 "totalNet": 2000, "totalVat": 540, "totalGross": 2540}
        validate_quote(valid)
        for broken in ({**valid, "currency": "USD"}, {**valid, "lines": []},
                       {**valid, "totalGross": 1}, {**valid, "totalNet": float("nan")}):
            with self.assertRaises(ValueError):
                validate_quote(broken)


class WarmupMeasurementTest(unittest.TestCase):
    def run_measurement(self, warmup):
        measured = summarize_requests([0.01], {}, 1)
        measured.update(pool_samples=[{'active': 5, 'pending': 1}], sampling_errors=[])
        options = SimpleNamespace(startup_timeout=10, concurrency=64, warmup_seconds=10,
                                  seconds=10, request_timeout=5)
        from tempfile import TemporaryDirectory
        with TemporaryDirectory() as directory, \
             patch.object(benchmark_quote, 'command', return_value='127.0.0.1:12345') as command, \
             patch.object(benchmark_quote, 'wait_healthy'), \
             patch.object(benchmark_quote, 'pool_snapshot', return_value={'max': 5, 'active': 0, 'idle': 5}), \
             patch.object(benchmark_quote, 'keycloak_token', side_effect=['warmup-token', 'measured-token']) as token, \
             patch.object(benchmark_quote, 'load', side_effect=[(warmup, []), (measured, [0.01])]) as load:
            self.command, self.token, self.load = command, token, load
            return benchmark_quote.measure(False, 5, 1, 'test', 'image', Path(directory), options, [],
                                           'http://127.0.0.1:50000')

    def test_fetches_fresh_service_token_for_each_load_phase(self):
        self.run_measurement(summarize_requests([0.1], {}, 10))
        self.assertEqual(self.token.call_count, 2)
        for call in self.token.call_args_list:
            self.assertEqual(call.args, ('http://127.0.0.1:50000', benchmark_quote.SERVICE_GRANT))
        self.assertEqual([call.args[1] for call in self.load.call_args_list], ['warmup-token', 'measured-token'])
        create = next(call.args for call in self.command.call_args_list if call.args[:2] == ('docker', 'create'))
        self.assertIn('CATALOG_JWT_JWK_SET_URI=http://keycloak:8080/realms/orderflow/protocol/openid-connect/certs',
                      create)
        self.assertIn('CATALOG_JWT_ISSUER_URI=http://localhost:8180/realms/orderflow', create)

    def test_warmup_overload_errors_are_retained_without_discarding_measured_phase(self):
        warmup = summarize_requests([0.1], {'HTTP 500': 2}, 10)
        result = self.run_measurement(warmup)
        self.assertEqual(result['warmup']['errors'], 2)
        self.assertEqual(result['errors'], 0)

    def test_no_successful_warmup_aborts_measurement(self):
        with self.assertRaises(RuntimeError):
            self.run_measurement(summarize_requests([], {'HTTP 500': 2}, 10))


class OptionValidationTest(unittest.TestCase):
    def test_rejects_load_phase_longer_than_access_token_lifetime(self):
        from tempfile import TemporaryDirectory
        with TemporaryDirectory() as directory, \
             patch.object(sys, 'argv', ['benchmark_quote.py', '--seconds', '296',
                                        '--output', str(Path(directory) / 'out')]), \
             patch.object(benchmark_quote, 'jar_path', return_value=Path(__file__)), \
             patch.object(benchmark_quote, 'command', side_effect=AssertionError('benchmark started')), \
             patch.object(benchmark_quote, 'cleanup_resources'), \
             patch('sys.stderr'), self.assertRaises(SystemExit) as exit:
            benchmark_quote.main()
        self.assertEqual(exit.exception.code, 2)


class SamplerTimingTest(unittest.TestCase):
    def test_sampler_shutdown_does_not_change_request_throughput(self):
        clock = [0.0]
        class Sampler:
            def __init__(self, **kwargs):
                pass
            def start(self):
                pass
            def join(self, **kwargs):
                clock[0] = 20.0
            def is_alive(self):
                return False
        sent_headers = []
        class Connection:
            def __init__(self, *args, **kwargs):
                pass
            def request(self, method, path, body, headers):
                sent_headers.append(headers)
            def getresponse(self):
                return self
            status = 200
            def read(self):
                clock[0] = 2.0
                return json.dumps({"currency": "EUR", "lines": [{"sku": "CHAIR-001", "quantity": 25}],
                                   "totalNet": 2000, "totalVat": 540, "totalGross": 2540}).encode()
            def close(self):
                pass
        local_threading = SimpleNamespace(Barrier=threading.Barrier, Event=threading.Event, Thread=Sampler)
        local_time = SimpleNamespace(monotonic=lambda: clock[0], perf_counter=time.perf_counter)
        with patch.object(benchmark_quote, "threading", local_threading), \
             patch.object(benchmark_quote, "time", local_time), \
             patch.object(benchmark_quote.http.client, "HTTPConnection", Connection):
            result, _ = benchmark_quote.load("http://localhost:8081", "service-token", 1, 1, 5, sample_pool=True)
        self.assertEqual(sent_headers[0]["Authorization"], "Bearer service-token")
        self.assertEqual(result["elapsed_seconds"], 2.0)
        self.assertEqual(result["success_rps"], 0.5)
