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


## Full Compose verification

Prerequisites: Python 3.10+ and running Docker Desktop with Compose 2.24.4+.
Run from the repository root:

```bash
python3 -m unittest discover -s scripts/tests_compose -v
```

The test copies the current contents of Git-tracked files, including uncommitted
tracked edits, to a temporary directory. It excludes local credentials, learning
materials and build output. A unique Compose project builds the four application
images and starts all nine services with fresh named volumes. A generated port
overlay uses `!override` to replace fixed host ports with ephemeral loopback ports;
Kafka clients use the internal listener. The normal development stack is untouched.

The test waits for healthy containers and checks each application readiness endpoint.
It submits a seeded B2C order for two CHAIR-001 units, checks HTTP 202/PENDING,
polls for CONFIRMED, verifies a committed inventory reservation, and observes
one SMTP notification row and the matching email in Mailpit. This is one positive
integration scenario, not comprehensive smoke or oversell coverage.

Ignored `target/compose-verification/<project>/` retains startup/container logs and
`result.json`. `up_build_wait_seconds` measures the complete Compose build/start/wait
command; it includes build and Docker overhead. Fresh database volumes do not mean
cold image downloads or empty BuildKit caches. The source commit identifies the
baseline; the test may include uncommitted tracked working-tree changes.

Cleanup removes only the invocation's containers, network, volumes and locally
named Compose image tags, and checks that its labelled resources are gone. It does
not remove the normal development stack or BuildKit caches. See Docker's
[Compose merge documentation](https://docs.docker.com/reference/compose-file/merge/)
for the version requirement of `!override`.

## Order smoke and oversell scenarios

The scripts require Bash 3.2+, curl and jq. The default SQL inspection uses Docker
Compose and the existing local `infra/.env`; no host PostgreSQL client is needed.
Start the full stack first. Run only against an exclusively used test environment
with the seeded customers, products and unchanged prices:

```bash
scripts/smoke.sh
MAILPIT_URL=http://localhost:8025 scripts/smoke.sh
```

`smoke.sh` checks B2C list pricing, B2B GOLD quantity-tier pricing, an exact
idempotent replay, confirmation, rejection on insufficient stock, cancellation,
released reservations and restored availability. Notification rows are checked;
setting `MAILPIT_URL` additionally checks captured SMTP subjects. Database
persistence alone does not prove email delivery. Smoke creates three orders and
cancels the two confirmed orders on success. A failed or interrupted run can
leave its orders/reservations behind; it does not delete historical data.

`oversell-test.sh` sends 50 concurrent one-unit orders to CHAIR-002 by default.
It requires exactly ten available units before sending any request. Normal seed
stock is 100, so the default script deliberately fails its precondition against
an unprepared database. It never resets stock. On success it requires exactly
10 CONFIRMED and 40 REJECTED orders, zero remaining availability, matching
per-order reservations and a valid stock invariant. The ten reservations remain
in that test environment after the script finishes.

The repeatable integration test prepares ten units only in its own disposable
stack, exercises both scripts and removes that stack:

```bash
python3 -m unittest discover -s scripts/tests_compose -p test_scenarios.py -v
```

It also verifies notification partition assignment, refusal of a depleted
oversell fixture, and failure for an unreachable HTTP endpoint. Evidence is kept
under ignored `target/compose-verification/<project>/`, alongside the earlier
stack-verification evidence. The integration fixture uses ephemeral loopback
ports and an invocation-specific password, never the normal development volumes.

| Variable | Default / purpose |
|---|---|
| `BASE_URL` | `http://localhost:8082`; order-service base URL, without `/api/v1` |
| `TIMEOUT_SECONDS` | `60`; each SQL/event wait; one shared final-state deadline for oversell |
| `SQL_MODE` | `compose`, or `psql` for a separately reachable PostgreSQL database |
| `COMPOSE_FILE` / `COMPOSE_ENV_FILE` | Repository `infra/docker-compose.yaml` / `infra/.env` |
| `COMPOSE_PROJECT_NAME` | `orderflow`; selects the database container for inspection |
| `MAILPIT_URL` | Optional HTTP base URL for SMTP capture inspection |
| `SKU` / `EXPECTED_AVAILABLE` / `REQUESTS` | Oversell: `CHAIR-002` / `10` / `50` |

For a remote order API, use `BASE_URL` and explicitly configure SQL inspection
against the **same environment**. In `SQL_MODE=psql`, the PostgreSQL client reads
normal libpq settings such as `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSFILE`
and `PGSSLMODE`. Supply credentials outside the command line/repository. This
mode requires an installed `psql` client and network access to that database;
changing only `BASE_URL` is insufficient. AWS execution has not been verified.

Every SQL inspection runs inside `BEGIN READ ONLY`, with a five-second statement
timeout. `psql` does not prompt for a password; the host-client mode also has a
five-second connection timeout. HTTP requests have connection and total timeouts.
Poll intervals are 250 ms; these are condition waits, not fixed sleeps pretending
that processing completed. The configured wait deadline is not a strict overall
script-duration limit because individual calls and other stages consume time.
Any transport, assertion, SQL or deadline failure returns a non-zero exit status.

Fresh Kafka startup disables broker automatic topic creation. The existing Spring
`NewTopic` declarations create the business/DLT topics with three partitions,
preventing a consumer from auto-creating a one-partition topic before the owners
initialize it. An existing Kafka volume retains its existing topics; this setting
does not migrate or recreate them.

## CI-filter regression tests

The suite uses the actual pinned `dorny/paths-filter` action with temporary Git
repositories. It checks inventory-only, two-service, contract, infrastructure,
root-build, workflow and documentation-only changes. It does not reimplement the
filter matcher in Python or call GitHub's pull-request API. Node 24 is used in CI.

To obtain the same action locally in a temporary directory:

```bash
ci_filter_dir=$(mktemp -d)
git clone --depth 1 --branch v4.0.3 https://github.com/dorny/paths-filter.git "$ci_filter_dir/paths-filter"
test "$(git -C "$ci_filter_dir/paths-filter" rev-parse HEAD)" = ceb8a2b8f2d89434be7ff52d3de7ec3738c5cc9d
PATHS_FILTER_ACTION="$ci_filter_dir/paths-filter/dist/index.js" python3 -m unittest discover -s scripts/tests_ci -v
```

Without `PATHS_FILTER_ACTION`, the suite reports skipped tests rather than claiming
it verified the filters. The CI detector checks out the pinned action and runs this
suite before service selection. These tests require only Git, Node and Python;
no Docker containers or normal application data are accessed.

## ECS release helper

`deploy.py` uses Python standard library, AWS CLI v2 and Docker buildx. The GitHub workflow provides the five target variables and short-lived credentials; it builds images, while the helper validates targets/images and manages checked application revision changes. CLI operations: `validate`, `image-status`, `verify-images`, `deploy`, `rollback`. See [DEPLOYMENT.md](../docs/DEPLOYMENT.md) for the manual release and bootstrap sequence.

```bash
python3 -m unittest discover -s scripts/tests_deploy -v
```

Behavior tests fake external CLI responses without cloud access; workflow contract tests use Ruby's standard-library YAML parser. They do not establish live OIDC/IAM success.
