#!/usr/bin/env python3
"""Compare catalog quote throughput under thread and Hikari pool configurations."""

import argparse
import csv
import hashlib
import http.client
import json
import math
import signal
import statistics
import threading
import time
import uuid
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

from benchmark_services import (ROOT, SERVICE_GRANT, cleanup_resources, command, http_get, interrupt, jar_path,
                                jwt_env, keycloak_token, wait_healthy)

VARIANTS = ((False, 5), (True, 5), (False, 50), (True, 50))
JAVA_FLAGS = ('-Xms128m', '-Xmx256m', '-XX:ActiveProcessorCount=1')
# accessTokenLifespan of infra/keycloak/orderflow-realm.json; one token serves a whole load phase.
TOKEN_LIFETIME_SECONDS = 300
REQUEST = json.dumps({'customerGroup': 'B2B_GOLD',
                      'lines': [{'sku': 'CHAIR-001', 'quantity': 25}]}).encode()


def percentile(values, percent):
    if not values or not 0 < percent <= 100:
        raise ValueError('Percentile needs samples and a percent in (0, 100]')
    ordered = sorted(values)
    return ordered[math.ceil(len(ordered) * percent / 100) - 1]


def validate_quote(quote):
    if (quote.get('currency') != 'EUR' or len(quote.get('lines', [])) != 1
            or quote['lines'][0].get('sku') != 'CHAIR-001'
            or quote['lines'][0].get('quantity') != 25):
        raise ValueError('Unexpected quote shape')
    amounts = [float(quote.get(key, float('nan'))) for key in ('totalNet', 'totalVat', 'totalGross')]
    if (not all(math.isfinite(value) and value >= 0 for value in amounts)
            or amounts[0] <= 0 or not math.isclose(amounts[0] + amounts[1], amounts[2], abs_tol=0.005)):
        raise ValueError('Invalid quote totals')


def summarize_requests(latencies, errors, elapsed):
    if elapsed <= 0:
        raise ValueError('Elapsed duration must be positive')
    failures = sum(errors.values())
    return {'successful_requests': len(latencies), 'errors': failures,
            'error_categories': dict(errors), 'elapsed_seconds': elapsed,
            'success_rps': len(latencies) / elapsed,
            'attempt_rps': (len(latencies) + failures) / elapsed,
            'mean_ms': statistics.mean(latencies) * 1000 if latencies else None,
            'p50_ms': percentile(latencies, 50) * 1000 if latencies else None,
            'p95_ms': percentile(latencies, 95) * 1000 if latencies else None,
            'p99_ms': percentile(latencies, 99) * 1000 if latencies else None}


def pool_snapshot(base_url):
    values = {}
    for name in ('active', 'idle', 'pending', 'max', 'min'):
        _, body = http_get(base_url + '/actuator/metrics/hikaricp.connections.' + name)
        measurement = json.loads(body)['measurements']
        values[name] = next(item['value'] for item in measurement if item['statistic'] == 'VALUE')
    return values


def load(base_url, token, concurrency, seconds, timeout, sample_pool=False):
    target = urlsplit(base_url)
    start = threading.Barrier(concurrency + 1)
    deadline = [0.0]
    stop_sampler = threading.Event()
    pool_samples = []
    sampling_errors = []

    def sample():
        while not stop_sampler.is_set():
            try:
                pool_samples.append({'seconds': time.monotonic() - started,
                                     **pool_snapshot(base_url)})
            except (OSError, http.client.HTTPException, ValueError, KeyError, StopIteration) as error:
                sampling_errors.append(type(error).__name__)
            stop_sampler.wait(0.2)

    def worker():
        latencies = []
        failures = Counter()
        connection = http.client.HTTPConnection(target.hostname, target.port, timeout=timeout)
        start.wait()
        try:
            while time.monotonic() < deadline[0]:
                began = time.perf_counter()
                try:
                    connection.request('POST', '/api/v1/quotes', REQUEST,
                                       {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token})
                    response = connection.getresponse()
                    body = response.read()
                    if response.status != 200:
                        failures['HTTP ' + str(response.status)] += 1
                    else:
                        validate_quote(json.loads(body))
                        latencies.append(time.perf_counter() - began)
                except (OSError, http.client.HTTPException, ValueError, KeyError, TypeError) as error:
                    failures[type(error).__name__] += 1
                    connection.close()
        finally:
            connection.close()
        return latencies, failures

    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [executor.submit(worker) for _ in range(concurrency)]
        started = time.monotonic()
        deadline[0] = started + seconds
        sampler = threading.Thread(target=sample, daemon=True) if sample_pool else None
        if sampler:
            sampler.start()
        start.wait()
        try:
            completed = [future.result() for future in futures]
            elapsed = time.monotonic() - started
        finally:
            stop_sampler.set()
            if sampler:
                sampler.join(timeout=timeout * 5 + 1)
                if sampler.is_alive():
                    raise TimeoutError('Pool sampler did not finish')
    latencies = [value for values, _ in completed for value in values]
    errors = Counter()
    for _, failures in completed:
        errors.update(failures)
    result = summarize_requests(latencies, errors, elapsed)
    result.update({'pool_samples': pool_samples, 'sampling_errors': sampling_errors})
    return result, latencies


def measure(virtual, pool_size, run, project, image, output, options, containers, keycloak_url):
    variant = ('virtual' if virtual else 'platform') + '-pool' + str(pool_size)
    name = f'{project}-{variant}-{run}'
    containers.append(name)
    args = ['docker', 'create', '--name', name, '--network', project + '_default',
            '--memory', '768m', '--cpus', '1', '--entrypoint', 'java',
            '-p', '127.0.0.1::8081', '--mount',
            f'type=bind,source={jar_path("catalog-pricing-service")},target=/app.jar,readonly']
    env = {'SPRING_DATASOURCE_URL': 'jdbc:postgresql://postgres:5432/orderflow?currentSchema=catalog',
           'SPRING_DATASOURCE_USERNAME': 'orderflow', 'SPRING_DATASOURCE_PASSWORD': 'benchmark-only',
           'SPRING_DATA_REDIS_HOST': 'redis',
           'SPRING_THREADS_VIRTUAL_ENABLED': str(virtual).lower(),
           'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE': str(pool_size),
           'SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE': str(pool_size),
           'MANAGEMENT_TRACING_SAMPLING_PROBABILITY': '0.1',
           'LOGGING_LEVEL_COM_ORDERFLOW': 'WARN', **jwt_env('CATALOG')}
    for key, value in env.items():
        args.extend(['-e', f'{key}={value}'])
    command(*args, image, *JAVA_FLAGS, '-jar', '/app.jar')
    try:
        command('docker', 'start', name)
        base_url = 'http://' + command('docker', 'port', name, '8081/tcp')
        wait_healthy(name, base_url + '/actuator/health/readiness', options.startup_timeout)
        ready_deadline = time.monotonic() + options.startup_timeout
        while True:
            initial = pool_snapshot(base_url)
            if initial['max'] == pool_size and initial['active'] + initial['idle'] == pool_size:
                break
            if time.monotonic() >= ready_deadline:
                raise TimeoutError(f'Pool {pool_size} did not become ready: {initial}')
            time.sleep(0.1)
        warmup, _ = load(base_url, keycloak_token(keycloak_url, SERVICE_GRANT), options.concurrency,
                         options.warmup_seconds, options.request_timeout)
        if not warmup['successful_requests']:
            raise RuntimeError('No successful warm-up requests: ' + str(warmup['error_categories']))
        if warmup['errors']:
            print(f'{variant} run {run}: warm-up errors={warmup["error_categories"]}', flush=True)
        result, latencies = load(base_url, keycloak_token(keycloak_url, SERVICE_GRANT), options.concurrency,
                                 options.seconds, options.request_timeout, sample_pool=True)
        result.update({'variant': variant, 'run': run, 'virtual_threads': virtual,
                       'pool_size': pool_size, 'warmup': warmup,
                       'max_active': max((s['active'] for s in result['pool_samples']), default=None),
                       'max_pending': max((s['pending'] for s in result['pool_samples']), default=None)})
        (output / f'{variant}-{run}.json').write_text(json.dumps(result, indent=2) + '\n')
        (output / f'{variant}-{run}-latencies.json').write_text(json.dumps(latencies) + '\n')
        if result['sampling_errors'] or not result['pool_samples']:
            raise RuntimeError('Missing/incomplete pool measurements: ' + str(result['sampling_errors']))
        if not result['successful_requests']:
            raise RuntimeError('No successful measured requests')
        print(f'{variant} run {run}: {result["success_rps"]:.1f} success/s, '
              f'p95={result["p95_ms"]:.1f}ms, errors={result["errors"]}, '
              f'active={result["max_active"]}, pending={result["max_pending"]}', flush=True)
        return result
    finally:
        try:
            (output / f'{variant}-{run}.log').write_text(
                command('docker', 'logs', name, include_stderr=True))
        finally:
            command('docker', 'rm', '-f', name)
            containers.remove(name)


def summary_markdown(results):
    lines = ['# Quote load measurement', '',
             '| Threads | Pool | Runs | Median success/s | Median run p95 ms | Errors | Max active | Max pending |',
             '|---|---:|---:|---:|---:|---:|---:|---:|']
    for virtual, pool_size in VARIANTS:
        rows = [row for row in results if row['virtual_threads'] == virtual and row['pool_size'] == pool_size]
        median_rps = statistics.median(row['success_rps'] for row in rows)
        median_p95 = statistics.median(row['p95_ms'] for row in rows)
        lines.append(f'| {"virtual" if virtual else "platform"} | {pool_size} | {len(rows)} | '
                     f'{median_rps:.1f} | {median_p95:.1f} | {sum(row["errors"] for row in rows)} | '
                     f'{max(row["max_active"] for row in rows):.0f} | '
                     f'{max(row["max_pending"] for row in rows):.0f} |')
    return '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runs', type=int, default=3)
    parser.add_argument('--concurrency', type=int, default=64)
    parser.add_argument('--seconds', type=float, default=10)
    parser.add_argument('--warmup-seconds', type=float, default=10)
    parser.add_argument('--request-timeout', type=float, default=5)
    parser.add_argument('--startup-timeout', type=float, default=120)
    parser.add_argument('--image', default='eclipse-temurin:25-jre')
    parser.add_argument('--output', type=Path)
    options = parser.parse_args()
    if (options.runs < 1 or options.concurrency < 1 or
            min(options.seconds, options.warmup_seconds, options.request_timeout, options.startup_timeout) <= 0):
        parser.error('All run counts and durations must be positive')
    if max(options.seconds, options.warmup_seconds) + options.request_timeout >= TOKEN_LIFETIME_SECONDS:
        parser.error(f'Each load phase plus request timeout must stay below the {TOKEN_LIFETIME_SECONDS}s token lifetime')
    jar = jar_path('catalog-pricing-service')
    if not jar.is_file():
        parser.error('Missing catalog JAR; run ./mvnw verify first')
    output = (options.output or ROOT / 'target/quote-benchmarks' /
              datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')).resolve()
    output.mkdir(parents=True, exist_ok=False)
    project = 'orderflow-quote-' + uuid.uuid4().hex[:10]
    compose = ['docker', 'compose', '-p', project, '-f', str(ROOT / 'scripts/benchmark-compose.yaml'),
               '-f', str(ROOT / 'scripts/benchmark-quote-compose.yaml')]
    containers = []
    results = []
    signal.signal(signal.SIGTERM, interrupt)
    try:
        command('docker', 'pull', options.image, timeout=600)
        image = command('docker', 'image', 'inspect', '-f', '{{.Id}}', options.image)
        metadata = {'source_commit': command('git', 'rev-parse', 'HEAD'),
                    'working_tree_status': command('git', 'status', '--short'),
                    'jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
                    'runtime_image_tag': options.image, 'runtime_image_id': image,
                    'runtime_repo_digests': json.loads(command('docker', 'image', 'inspect',
                        '-f', '{{json .RepoDigests}}', image)),
                    'java_version': command('docker', 'run', '--rm', '--entrypoint', 'java', image, '-version',
                        include_stderr=True),
                    'docker_server': json.loads(command('docker', 'version', '--format', '{{json .Server}}')),
                    'java_flags': JAVA_FLAGS, 'application_cpu': 1, 'application_memory_mib': 768,
                    'postgres_cpu': 1, 'postgres_memory_mib': 512, 'redis_cpu': 0.5, 'redis_memory_mib': 128,
                    'options': {k: str(v) if isinstance(v, Path) else v for k, v in vars(options).items()},
                    'load_model': 'closed-loop persistent HTTP connections from Mac Python threads',
                    'cache': 'price tiers warmed; product reads still query PostgreSQL',
                    'tracing_root_sampling': 0.1, 'business_log_level': 'WARN',
                    'auth': 'order-service client-credentials token fetched before each load phase, outside '
                            'timing; every quote request includes RS256 JWT validation'}
        (output / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
        command(*compose, 'up', '-d', '--wait', '--wait-timeout', '120', 'postgres', 'redis', 'keycloak', timeout=240)
        keycloak_url = 'http://' + command(*compose, 'port', 'keycloak', '8080')
        for run in range(1, options.runs + 1):
            offset = (run - 1) % len(VARIANTS)
            for virtual, pool_size in VARIANTS[offset:] + VARIANTS[:offset]:
                result = measure(virtual, pool_size, run, project, image, output, options, containers,
                                 keycloak_url)
                results.append(result)
                (output / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
        scalar_fields = ['variant', 'run', 'virtual_threads', 'pool_size', 'successful_requests', 'errors',
                         'elapsed_seconds', 'success_rps', 'attempt_rps', 'mean_ms', 'p50_ms', 'p95_ms', 'p99_ms',
                         'max_active', 'max_pending']
        with (output / 'results.csv').open('w', newline='') as file:
            writer = csv.DictWriter(file, fieldnames=scalar_fields)
            writer.writeheader()
            writer.writerows({key: row[key] for key in scalar_fields} for row in results)
        summary = summary_markdown(results)
        (output / 'summary.md').write_text(summary)
        print(summary, flush=True)
    finally:
        cleanup_resources(containers, compose)


if __name__ == '__main__':
    main()
