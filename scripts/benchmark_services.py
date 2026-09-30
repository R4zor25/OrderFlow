#!/usr/bin/env python3
"""Measure packaged services with an isolated Docker dependency stack."""

import argparse
import csv
import hashlib
import http.client
import json
import platform
import re
import signal
import statistics
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VARIANTS = ('spring', 'spring-lazy', 'micronaut')
JAVA_FLAGS = ('-XX:MaxRAMPercentage=60', '-XX:+ExitOnOutOfMemoryError', '-XX:MaxMetaspaceSize=224m',
              '-XX:ActiveProcessorCount=1')
BENCHMARK_SECRET = 'benchmark-only'
# Must match KC_HOSTNAME in benchmark-compose.yaml; the published Keycloak port itself is ephemeral.
KEYCLOAK_ISSUER = 'http://localhost:8180/realms/orderflow'
KEYCLOAK_INTERNAL = 'http://keycloak:8080/realms/orderflow'
TOKEN_PATH = '/realms/orderflow/protocol/openid-connect/token'
CUSTOMER_GRANT = {'grant_type': 'password', 'client_id': 'orderflow-cli',
                  'username': 'standard', 'password': BENCHMARK_SECRET}
SERVICE_GRANT = {'grant_type': 'client_credentials', 'client_id': 'order-service',
                 'client_secret': BENCHMARK_SECRET}


def startup_seconds(log, framework):
    pattern = (r'Started \S+ in ([\d.]+) seconds' if framework == 'spring'
               else r'Startup completed in ([\d.]+)ms')
    match = re.search(pattern, log)
    if not match:
        raise ValueError(f'Missing {framework} startup log marker')
    return float(match[1]) / (1000 if framework == 'micronaut' else 1)


def memory_mib(value):
    match = re.fullmatch(r'([\d.]+)\s*(B|KiB|MiB|GiB|TiB|kB|MB|GB|TB)', value.split('/')[0].strip())
    if not match:
        raise ValueError(f'Invalid Docker memory value: {value!r}')
    factors = {'B': 1, 'KiB': 1024, 'MiB': 1024**2, 'GiB': 1024**3,
               'TiB': 1024**4, 'kB': 1000, 'MB': 1000**2, 'GB': 1000**3, 'TB': 1000**4}
    return float(match[1]) * factors[match[2]] / 1024**2


def rss_mib(status):
    match = re.search(r'^VmRSS:\s+(\d+) kB$', status, re.MULTILINE)
    if not match:
        raise ValueError('Missing VmRSS in /proc/1/status')
    return int(match[1]) / 1024


def command(*args, timeout=120, include_stderr=False):
    result = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f'Command failed: {args[0]} {args[1]}\n{result.stderr[-3000:]}')
    return (result.stdout + ('\n' + result.stderr if include_stderr else '')).strip()


def http_get(url, timeout=2, token=None):
    request = urllib.request.Request(url, headers={'Authorization': 'Bearer ' + token} if token else {})
    started = time.perf_counter()
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = response.read()
        if response.status != 200:
            raise ValueError(f'HTTP {response.status} from {url}')
    return time.perf_counter() - started, body


def keycloak_token(keycloak_url, grant, timeout=10):
    request = urllib.request.Request(keycloak_url + TOKEN_PATH, data=urllib.parse.urlencode(grant).encode(),
                                     headers={'Content-Type': 'application/x-www-form-urlencoded'})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            token = json.loads(response.read()).get('access_token')
    except urllib.error.HTTPError as error:
        raise RuntimeError(f'Keycloak token request failed with HTTP {error.code}: {error.read()[:500]!r}') from error
    if not token:
        raise ValueError('Keycloak returned no access token')
    return token


def jwt_env(prefix):
    return {prefix + '_JWT_ISSUER_URI': KEYCLOAK_ISSUER,
            prefix + '_JWT_JWK_SET_URI': KEYCLOAK_INTERNAL + '/protocol/openid-connect/certs'}


def wait_healthy(name, url, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if command('docker', 'inspect', '-f', '{{.State.Running}}', name) != 'true':
            raise RuntimeError(f'{name} exited before health became UP')
        try:
            _, body = http_get(url)
            if json.loads(body).get('status') == 'UP':
                return
        except (OSError, http.client.HTTPException, ValueError):
            pass
        time.sleep(0.2)
    raise TimeoutError(f'{name} did not become healthy in {timeout}s')


def jar_path(service):
    return ROOT / 'services' / service / 'target' / f'{service}-0.0.1-SNAPSHOT.jar'


def run_variant(variant, run, project, image, output, options, containers, keycloak_url, prime=False):
    spring = variant.startswith('spring')
    service = 'order-service' if spring else 'notification-service'
    port = 8082 if spring else 8084
    name = f'{project}-{variant}-{run}'
    containers.append(name)
    env = {
        'SPRING_DATASOURCE_URL': 'jdbc:postgresql://postgres:5432/orderflow?currentSchema=orders',
        'SPRING_DATASOURCE_USERNAME': 'orderflow', 'SPRING_DATASOURCE_PASSWORD': 'benchmark-only',
        'SPRING_DATA_REDIS_HOST': 'redis', 'SPRING_KAFKA_BOOTSTRAP_SERVERS': 'kafka:9092',
        'ORDER_MESSAGING_ENABLED': 'true', 'ORDER_OUTBOX_RELAY_ENABLED': 'true',
        'SPRING_MAIN_LAZY_INITIALIZATION': str(variant == 'spring-lazy').lower(),
        **jwt_env('ORDER'), 'ORDER_TOKEN_URI': KEYCLOAK_INTERNAL + '/protocol/openid-connect/token',
        'ORDER_SERVICE_CLIENT_SECRET': BENCHMARK_SECRET,
    } if spring else {
        'DATASOURCES_DEFAULT_URL': 'jdbc:postgresql://postgres:5432/orderflow?currentSchema=notifications',
        'DATASOURCES_DEFAULT_USERNAME': 'orderflow', 'DATASOURCES_DEFAULT_PASSWORD': 'benchmark-only',
        'KAFKA_BOOTSTRAP_SERVERS': 'kafka:9092', 'KAFKA_HEALTH_ENABLED': 'true',
        'NOTIFICATION_MESSAGING_ENABLED': 'true', 'NOTIFICATION_CHANNEL': 'LOG',
    }
    args = ['docker', 'create', '--name', name, '--network', f'{project}_default',
            '--memory', '768m', '--cpus', '1', '--entrypoint', 'java',
            '-p', f'127.0.0.1::{port}', '--mount', f'type=bind,source={jar_path(service)},target=/app.jar,readonly']
    for key, value in env.items():
        args += ['-e', f'{key}={value}']
    command(*args, image, *JAVA_FLAGS, '-jar', '/app.jar')
    log_path = output / f'{variant}-{run}.log'
    try:
        started = time.perf_counter()
        command('docker', 'start', name)
        binding = command('docker', 'port', name, f'{port}/tcp')
        base_url = 'http://' + binding
        wait_healthy(name, base_url + ('/actuator/health' if spring else '/health'), options.timeout)
        health_seconds = time.perf_counter() - started
        first_api_seconds = None
        if spring:
            token = keycloak_token(keycloak_url, CUSTOMER_GRANT)
            first_api_seconds, _ = http_get(base_url + '/api/v1/orders?page=0&size=1', timeout=options.timeout,
                                            token=token)
        if prime:
            print(f'Primed schema and dependencies: {variant}', flush=True)
            return None
        for _ in range(100):
            http_get(base_url + ('/actuator/health' if spring else '/health'))
        time.sleep(options.warmup_seconds)
        samples = []
        for sample in range(options.samples):
            _, body = http_get(base_url + ('/actuator/health' if spring else '/health'))
            if json.loads(body).get('status') != 'UP':
                raise RuntimeError('Health changed during memory sampling')
            raw_stats = json.loads(command('docker', 'stats', '--no-stream', '--format', '{{json .}}', name))
            status = command('docker', 'exec', name, 'cat', '/proc/1/status')
            samples.append({'rss_mib': rss_mib(status), 'docker_memory_mib': memory_mib(raw_stats['MemUsage']),
                            'docker_stats': raw_stats})
            (output / f'{variant}-{run}-sample-{sample+1}.status').write_text(status + '\n')
        log = command('docker', 'logs', name, include_stderr=True)
        result = {'variant': variant, 'run': run,
                  'startup_log_seconds': startup_seconds(log, 'spring' if spring else 'micronaut'),
                  'health_seconds': health_seconds, 'first_order_read_seconds': first_api_seconds,
                  'rss_mib': statistics.mean(s['rss_mib'] for s in samples),
                  'docker_memory_mib': statistics.mean(s['docker_memory_mib'] for s in samples),
                  'samples': samples}
        print(f'{variant} run {run}: log={result["startup_log_seconds"]:.3f}s, '
              f'health={health_seconds:.3f}s, RSS={result["rss_mib"]:.1f}MiB, '
              f'Docker={result["docker_memory_mib"]:.1f}MiB', flush=True)
        return result
    finally:
        log_path.write_text(command('docker', 'logs', name, include_stderr=True))
        command('docker', 'rm', '-f', name)
        containers.remove(name)


def summary_markdown(results):
    lines = ['| Variant | n | Log startup mean (s) | Health mean (s) | First order read mean (s) | RSS mean (MiB) | Docker mean (MiB) |',
             '|---|---:|---:|---:|---:|---:|---:|']
    for variant in VARIANTS:
        rows = [r for r in results if r['variant'] == variant]
        mean = lambda key: statistics.mean(r[key] for r in rows)
        first = f'{mean("first_order_read_seconds"):.3f}' if variant.startswith('spring') else 'N/A'
        lines.append(f'| {variant} | {len(rows)} | {mean("startup_log_seconds"):.3f} | '
                     f'{mean("health_seconds"):.3f} | {first} | {mean("rss_mib"):.1f} | {mean("docker_memory_mib"):.1f} |')
    return '\n'.join(lines) + '\n'


def cleanup_resources(containers, compose):
    failures = []
    calls = [['docker', 'rm', '-f', name] for name in containers]
    calls.append([*compose, 'down', '--volumes', '--remove-orphans'])
    for args in calls:
        try:
            result = subprocess.run(args, capture_output=True, text=True, timeout=120)
            if result.returncode:
                failures.append(result.stderr)
        except (subprocess.TimeoutExpired, OSError) as failure:
            failures.append(str(failure))
    if failures:
        raise RuntimeError('Incomplete benchmark cleanup: ' + '; '.join(failures))


def interrupt(*_):
    raise KeyboardInterrupt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runs', type=int, default=3)
    parser.add_argument('--warmup-seconds', type=float, default=30)
    parser.add_argument('--samples', type=int, default=3)
    parser.add_argument('--timeout', type=float, default=120)
    parser.add_argument('--image', default='eclipse-temurin:25-jre')
    parser.add_argument('--output', type=Path)
    options = parser.parse_args()
    if options.runs < 1 or options.samples < 1 or options.warmup_seconds < 0 or options.timeout <= 0:
        parser.error('runs/samples/timeout must be positive; warmup-seconds must be nonnegative')
    for service in ('order-service', 'notification-service'):
        if not jar_path(service).is_file():
            parser.error(f'Missing packaged JAR: {jar_path(service)}; run ./mvnw verify first')
    timestamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    output = (options.output or ROOT / 'target' / 'benchmarks' / timestamp).resolve()
    output.mkdir(parents=True, exist_ok=False)
    project = 'orderflow-bench-' + uuid.uuid4().hex[:10]
    compose = ['docker', 'compose', '-p', project, '-f', str(ROOT / 'scripts/benchmark-compose.yaml')]
    containers = []
    results = []
    signal.signal(signal.SIGTERM, interrupt)
    try:
        command('docker', 'pull', options.image, timeout=600)
        image = command('docker', 'image', 'inspect', '-f', '{{.Id}}', options.image)
        metadata = {
            'timestamp_utc': timestamp, 'git_commit': command('git', 'rev-parse', 'HEAD'),
            'git_dirty': bool(command('git', 'status', '--porcelain', '--untracked-files=normal')),
            'host_os': platform.system(), 'host_architecture': platform.machine(),
            'docker_server': json.loads(command('docker', 'version', '--format', '{{json .Server}}')),
            'runtime_image_tag': options.image, 'runtime_image_id': image,
            'runtime_repo_digests': json.loads(command('docker', 'image', 'inspect', '-f', '{{json .RepoDigests}}', image)),
            'java_version': command('docker', 'run', '--rm', '--entrypoint', 'sh', image, '-c', 'java -version 2>&1'),
            'java_flags': JAVA_FLAGS, 'container_memory': '768m', 'container_cpus': 1,
            'runs': options.runs, 'samples_per_run': options.samples, 'warmup_seconds': options.warmup_seconds,
            'jar_sha256': {s: hashlib.sha256(jar_path(s).read_bytes()).hexdigest()
                           for s in ('order-service', 'notification-service')},
            'auth': 'CUSTOMER password-grant token fetched from the benchmark Keycloak after health and '
                    'outside timing; the first order read includes server-side JWT validation and JWKS fetch',
        }
        (output / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
        command(*compose, 'up', '-d', '--wait', '--wait-timeout', '120', timeout=240)
        keycloak_url = 'http://' + command(*compose, 'port', 'keycloak', '8080')
        command(*compose, 'exec', '-T', 'postgres', 'psql', '-U', 'orderflow', '-d', 'orderflow',
                '-v', 'ON_ERROR_STOP=1', '-c', 'CREATE SCHEMA orders; CREATE SCHEMA notifications;')
        command(*compose, 'exec', '-T', 'kafka', '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server', 'kafka:9092',
                '--create', '--if-not-exists', '--topic', 'orders.order-events.v1', '--partitions', '3', '--replication-factor', '1')
        command(*compose, 'exec', '-T', 'kafka', '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server', 'kafka:9092',
                '--create', '--if-not-exists', '--topic', 'inventory.stock-events.v1', '--partitions', '3', '--replication-factor', '1')
        for variant in ('spring', 'micronaut'):
            run_variant(variant, 'prime', project, image, output, options, containers, keycloak_url, prime=True)
        for run in range(1, options.runs + 1):
            offset = (run - 1) % len(VARIANTS)
            for variant in VARIANTS[offset:] + VARIANTS[:offset]:
                result = run_variant(variant, run, project, image, output, options, containers, keycloak_url)
                results.append(result)
                (output / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
        fields = [k for k in results[0] if k != 'samples']
        with (output / 'results.csv').open('w', newline='') as file:
            writer = csv.DictWriter(file, fieldnames=fields)
            writer.writeheader()
            writer.writerows({k: r[k] for k in fields} for r in results)
        summary = summary_markdown(results)
        (output / 'summary.md').write_text(summary)
    finally:
        cleanup_resources(containers, compose)
    print(summary, flush=True)
    print(f'Raw evidence: {output}', flush=True)


if __name__ == '__main__':
    main()
