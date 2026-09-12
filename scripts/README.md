# Service benchmarks

## Startup and memory

Prerequisites: Python 3.10+, Docker Desktop with Compose, and Java 25 for the build.

```bash
./mvnw verify
python3 -m unittest discover -s scripts/tests -v
python3 scripts/benchmark_services.py
```

The script runs `order-service`, `order-service` with lazy initialization, and
`notification-service` sequentially with the same Java runtime image, 1 CPU quota,
768 MiB container limit, and `-Xms128m -Xmx256m -XX:ActiveProcessorCount=1`.
The resolved image ID, repository digest, JVM version, JAR hashes and source commit
are recorded. No native image or AOT build is used.

A unique Compose project provides disposable PostgreSQL, Redis and Kafka without
publishing their ports or accessing the local Compose stack. Its database uses a
temporary filesystem and a benchmark-only password. Kafka consumption is enabled
for both services; notification delivery uses LOG. There are no business events.
One unmeasured run of each service prepares migrations and dependency caches.
Each measured run starts a new JVM; variant order rotates across rounds.

By default there are three runs per variant. After health becomes UP, the Spring
service receives its first order-list request against an empty database. Both
services receive 100 health requests, followed by a 30-second settling period and
three memory samples. This is an idle-service measurement after a small HTTP
warm-up, not a throughput test or a fully JIT-warmed production workload.

Metrics:

- `startup_log_seconds`: the framework's startup marker, not image pull or Docker creation.
- `health_seconds`: elapsed time from invoking Docker start to the first observed HTTP 200/UP.
  Includes Docker/host overhead and polling; does not assert Kafka partition assignment.
- `first_order_read_seconds`: first successful order-list request; only comparable
  between the Spring normal and lazy variants. Micronaut has no equivalent endpoint.
- `rss_mib`: JVM PID 1 `VmRSS` from `/proc/1/status` (KiB converted to MiB).
- `docker_memory_mib`: Docker CLI `MemUsage`, which on Linux excludes inactive file cache.
  This is container accounting, not process RSS or heap usage.

Raw startup logs, process status, Docker samples, metadata, per-run CSV/JSON and
an average table are written under ignored `target/benchmarks/<UTC timestamp>/`.
An incomplete run retains its available evidence but produces no final average table.
Cleanup removes only containers, network and volumes owned by this invocation.

```bash
python3 scripts/benchmark_services.py --runs 5 --warmup-seconds 60 --samples 5
python3 scripts/benchmark_services.py --image eclipse-temurin@sha256:<recorded-digest>
```

Use the same limits and packaged artifacts for repeated comparisons. These two
services have different responsibilities, libraries and persistence layers, so
the result describes this checkout, not the intrinsic cost of either framework.
Lazy initialization can move startup work to the first request. Idle memory
measurements do not establish safe ECS/Fargate sizing or a monetary cost saving.

References: [Docker stats](https://docs.docker.com/reference/cli/docker/container/stats/),
[Spring lazy initialization](https://docs.spring.io/spring-boot/reference/features/spring-application.html#features.spring-application.lazy-initialization).


## Quote throughput and pool pressure

```bash
./mvnw verify
python3 -m unittest discover -s scripts/tests -v
python3 scripts/benchmark_quote.py
```

The second script compares four catalog configurations: platform/virtual threads,
each with a fixed pool of 5 or 50 connections. Application defaults remain 10.
It uses the packaged catalog JAR, one resolved Java 25 runtime image, 1 CPU,
768 MiB container memory and `-Xms128m -Xmx256m -XX:ActiveProcessorCount=1`.
An isolated Compose project reuses the benchmark PostgreSQL/Redis definitions;
its overlay limits PostgreSQL to 1 CPU/512 MiB and Redis to 0.5 CPU/128 MiB.
Kafka is not started. No normal local database, volume or fixed host port is used.

The default is three rounds, with variant order rotated each round. Each case
starts a fresh JVM, waits for readiness and the full configured pool, warms the
real `POST /api/v1/quotes` workload for 10 seconds and measures for 10 seconds.
The request uses B2B_GOLD and 25 units of CHAIR-001. The price-tier cache is warm;
products still come from PostgreSQL. Business INFO logs are disabled for every
case to avoid per-request console overhead; root trace sampling is 10% for all.

The load generator runs on the Mac using 64 Python workers and persistent HTTP
connections. This is a closed-loop model: each worker sends its next request only
after its previous request completes. It includes client, HTTP, JSON validation,
application, Redis and PostgreSQL costs; it is not an open-loop arrival-rate
capacity test. The generator may also become a bottleneck.

Successful HTTP 200 quotes with valid shape and totals contribute to latency
samples. Errors are separately counted by category, never treated as fast successes.
Throughput uses the actual request interval including in-flight request completion,
not the later pool sampler shutdown. P95/P99 use nearest rank over successful
client request durations. Pool gauges are sampled about every 200 ms, but Hikari
caches pool statistics and gauges are read sequentially: observed peaks can miss
short bursts and a snapshot is not atomic. Sampling adds equal configured overhead,
not guaranteed identical wall-clock overhead, across cases.

Raw request durations, per-run measurements, pool samples, logs, image/JAR hashes,
source commit and dirty-tree status are kept under ignored
`target/quote-benchmarks/<UTC timestamp>/`. The summary reports the median of each
variant's run throughput and run p95, not a pooled percentile. Each case and the
owned dependency stack are removed in cleanup. Interrupted or failed measurement
keeps available evidence without producing a complete summary.

```bash
python3 scripts/benchmark_quote.py --runs 3 --concurrency 64 --warmup-seconds 10 --seconds 10
```

Change load settings deliberately and compare identical settings. Differences on
this short, warmed workload do not prove that virtual threads are always faster or
that 50 connections should replace the default 10 in production.

Warm-up errors are retained separately in each run JSON and printed during the run.
A run aborts if warm-up has no successful quote; transient overload during warm-up
does not discard the later measured phase. Measured errors remain in the results.

## Service image verification

With Docker Desktop running, execute from the repository root:

```bash
./mvnw verify
python3 -m unittest discover -s scripts/tests_docker -v
```

These four integration tests build the service Dockerfiles using service-local
contexts. Each image runs with 512 MiB and one CPU against a uniquely named
PostgreSQL/Redis/Kafka stack. Tests verify real HTTP readiness and liveness,
non-root execution, Java as PID 1, heap configuration, absence of build tools and
shutdown without OOM or forced SIGKILL. They run separately from the fast Python
measurement-helper tests. The first build downloads JDK/JRE images and Maven
artifacts; later builds reuse BuildKit's cache.

Evidence is retained under ignored `target/image-verification/<project>/`.
Owned containers, dependency volumes/network and verification image tags are
removed after the suite. Existing local infrastructure and BuildKit caches remain.
