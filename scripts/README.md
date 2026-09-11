# Service startup and memory benchmark

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
