# OrderFlow

OrderFlow is a B2B/B2C e-commerce backend built as a set of services. The target architecture and service responsibilities are described in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Current state

The Maven aggregator builds three Spring Boot services and one Micronaut service. The catalog service provides products and Redis-cached price quotes. The order service creates, queries and cancels orders, using Redis for HTTP idempotency and a PostgreSQL transactional outbox for events. The inventory service consumes order events, reserves or releases stock, and publishes reservation results and low-stock events. The notification service stores notifications idempotently and attempts delivery through SMTP or logging after the database commit.

Each service owns its PostgreSQL schema and applies Flyway migrations. Kafka carries the order and inventory events; the order service confirms or rejects pending orders from inventory results. Notification delivery is a single attempt after persistence, so a failure or crash can leave an email undelivered.

## Build and test

JDK 25 is required. The Maven Wrapper supplies the Maven version.

```bash
./mvnw verify
```

The root POM builds all four services. To build one independently, run `./mvnw verify` from its directory under `services/`. Integration tests start temporary PostgreSQL, Redis and Kafka Testcontainers as needed, so Docker must be running.

## Run locally

If `infra/.env` does not exist yet, copy `infra/.env.example` to `infra/.env` and replace the placeholder with your own local password. Keep an existing working `.env` unchanged. The example is only a committed template; the real `.env` is ignored by Git. The password must match the database already stored in an existing PostgreSQL volume: changing the file does not change that database user's password.

Start the complete system from the repository root with Docker Desktop running:

```bash
docker compose --env-file infra/.env -f infra/docker-compose.yaml up --build -d --wait
docker compose --env-file infra/.env -f infra/docker-compose.yaml ps
```

Compose builds and starts all four applications together with PostgreSQL, Redis, Kafka, Kafka UI, Mailpit and Keycloak. The applications use their existing `local` profiles/environments with container-network addresses supplied at runtime. Each application has 512 MiB memory, one CPU and a 30-second stop grace period. Health checks call readiness; startup dependencies wait for `service_healthy`. These checks do not automatically repair a dependency failure after startup.

| Endpoint on the Mac | Address |
|---|---|
| Catalog / order / inventory / notification | `localhost:8081` / `:8082` / `:8083` / `:8084` |
| Kafka UI | `http://localhost:8085` |
| Mailpit inbox | `http://localhost:8025` |
| Keycloak (issuer, token endpoint, console) | `http://localhost:8180` |
| PostgreSQL / Redis / Kafka host listener | `localhost:5433` / `:6379` / `:9094` |

All published ports bind to loopback. Inside Compose, applications connect to `postgres:5432`, `redis:6379`, `kafka:9092`, `catalog-pricing-service:8081`, `mailpit:1025` and `keycloak:8080` (signing keys and tokens) instead of host addresses.

### Run applications in IntelliJ instead

To use Java processes on the Mac, stop any Compose application containers first to free their ports, then start only the dependencies:

```bash
docker compose --env-file infra/.env -f infra/docker-compose.yaml stop catalog-pricing-service order-service inventory-service notification-service
docker compose --env-file infra/.env -f infra/docker-compose.yaml up -d --wait postgres redis kafka kafka-ui mailpit keycloak
```

Pass the same `POSTGRES_PASSWORD` value to the IntelliJ run configurations and select the Spring `local` profile or Micronaut `local` environment. order-service additionally needs `ORDER_SERVICE_CLIENT_SECRET` from `infra/.env` for its pricing calls; without it the service still starts, but the token request is rejected and `POST /api/v1/orders` returns 503 `/problems/pricing-unavailable` (the gateway reports "Pricing credentials unavailable"). The curl examples below read `DEMO_USER_PASSWORD` from the same file. Compose does not supply variables to Java processes on the Mac. For example, with that variable already set in your terminal:

```bash
cd services/catalog-pricing-service
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

In another terminal, try the endpoints:

```bash
curl http://localhost:8081/actuator/health
curl 'http://localhost:8081/api/v1/products?category=FURNITURE&page=0&size=3'
curl http://localhost:8081/api/v1/products/CHAIR-001
ADMIN_TOKEN=$(curl -s -d grant_type=password -d client_id=orderflow-cli -d username=admin \
  --data-urlencode "password=$DEMO_USER_PASSWORD" \
  http://localhost:8180/realms/orderflow/protocol/openid-connect/token | jq -r .access_token)
curl -i -X POST http://localhost:8081/api/v1/products \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"sku":"TEST-NEW-001","name":"New Test Product","category":"OFFICE","listPriceNet":19.90,"vatRate":0.27}'
curl -i -X PUT http://localhost:8081/api/v1/products/CHAIR-001/prices \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"customerGroup":"B2B_GOLD","tiers":[{"minQty":1,"unitPriceNet":84.90,"validFrom":"2020-01-01"}]}'
```

Product reads are public. Product creation and price replacement need an `ADMIN` bearer token from the local Keycloak (see Local identity provider); the Keycloak stack from Compose must be running. The `aws` profile reads `CATALOG_JWT_ISSUER_URI` and `CATALOG_JWT_JWK_SET_URI`. The expected health status is `UP`. For a Java process on the Mac, the local profile connects to `localhost:5433`, which Compose forwards to PostgreSQL on port 5432. In Compose, runtime environment variables override this with `postgres:5432`. The `aws` profile reads its database URL, username and password from `CATALOG_DB_URL`, `CATALOG_DB_USERNAME` and `CATALOG_DB_PASSWORD`.

On startup, Flyway applies `V1__catalog.sql` and `V2__seed.sql` once. The `catalog` schema then contains 20 sample products, three customer groups and 12 B2B price tiers. Applied versions are recorded in `catalog.flyway_schema_history`.

The product list accepts optional `category`, `page` (zero-based, default 0) and `size` (default 20, capped at 100) parameters. Items are sorted by SKU. A product that does not exist returns 404.

Product creation requires `sku`, `name`, `category`, a positive `listPriceNet`, and a `vatRate` between 0 and 1. A successful request returns 201 with a `Location` header. Invalid request fields return 400, an existing SKU returns 409, and an out-of-range VAT rate returns 422. Errors use RFC 9457 problem details.

`POST /api/v1/quotes` accepts a customer group and one or more SKU/quantity lines. It returns EUR prices using the highest applicable quantity tier, or the product list price if no tier applies. B2C lines show gross amounts; B2B lines show net and VAT separately. The response always includes net, VAT, and gross totals. Invalid input returns 400. Unknown or inactive SKUs return 422 with every missing SKU in the `skus` array. Quoting writes no data. The quote endpoint is internal: it is callable only with the order-service client-credentials token (scope `pricing.quote`), so there is no direct curl example.

The PUT example above changes the local seed price for `CHAIR-001`. `PUT /api/v1/products/{sku}/prices` replaces the full price-tier list for one customer group and SKU. An empty `tiers` array clears that group's tiers. A successful update returns 204 and invalidates the corresponding Redis cache entry after the database transaction commits. Repeating a quote for the same group and SKU reads its tiers from Redis; cache entries expire after 10 minutes. The `local` profile connects to Redis at `localhost:6379`; the `aws` profile reads `CATALOG_REDIS_HOST` and optional `CATALOG_REDIS_PORT` (default 6379).

### Local identity provider

Compose starts Keycloak at `http://localhost:8180` with the `orderflow` realm imported from
`infra/keycloak/orderflow-realm.json`. Add the four Keycloak variables from `infra/.env.example`
to your existing `infra/.env`; Compose refuses to start without them. Demo users `consumer.one`,
`consumer.two`, `standard` and `gold` map to the four seeded customers; `admin` administers the
catalog. All use `DEMO_USER_PASSWORD`. Keycloak keeps its development database inside the container, so
a restart keeps console changes; only recreating the container (for example `down` then `up`, or
`up --force-recreate keycloak`) re-imports the realm file and discards them.

## Order service

The `order-service` is a separate Spring Boot application on port 8082. It uses the `orders` PostgreSQL schema; Flyway creates `customer`, `orders`, `order_line`, `outbox_event`, and `processed_event` and seeds four sample customers. `POST /api/v1/orders` accepts an `Idempotency-Key` header and creates a priced `PENDING` order with HTTP 202. `GET /api/v1/orders/{id}` and `GET /api/v1/orders` provide reads; `POST /api/v1/orders/{id}/cancel` cancels an eligible order. Inventory result events confirm or reject pending orders, and cancellation events release reservations.

The full Compose command already starts this service. For the IntelliJ/host workflow described above, with dependency containers running and `POSTGRES_PASSWORD` and `ORDER_SERVICE_CLIENT_SECRET` set to the values in `infra/.env`, start it separately:

```bash
cd services/order-service
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Then check `http://localhost:8082/actuator/health`. The `aws` profile expects `ORDER_DB_URL`, `ORDER_DB_USERNAME`, and `ORDER_DB_PASSWORD`, plus `ORDER_JWT_ISSUER_URI`, `ORDER_JWT_JWK_SET_URI`, `ORDER_TOKEN_URI` and `ORDER_SERVICE_CLIENT_SECRET`.

Order endpoints need a bearer token from the local Keycloak. Request one with the demo user's password grant and place an order; the customer comes from the token, not the request body:

```bash
TOKEN=$(curl -s -d grant_type=password -d client_id=orderflow-cli -d username=standard \
  --data-urlencode "password=$DEMO_USER_PASSWORD" \
  http://localhost:8180/realms/orderflow/protocol/openid-connect/token | jq -r .access_token)
curl -i -X POST http://localhost:8082/api/v1/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Idempotency-Key: demo-1' -H 'Content-Type: application/json' \
  -d '{"lines":[{"sku":"CHAIR-001","quantity":2}]}'
```

A customer sees only their own orders: another customer's order ID returns 404, and listing with another customer's `customerId` returns 403. The `admin` user can read and cancel any order.


## Health probes and shutdown

With the `local` profile/environment active, use these endpoints:

| Service | Port | Liveness | Readiness | Readiness dependencies |
|---|---|---|---|---|
| catalog-pricing-service | 8081 | `/actuator/health/liveness` | `/actuator/health/readiness` | PostgreSQL, Redis |
| order-service | 8082 | `/actuator/health/liveness` | `/actuator/health/readiness` | PostgreSQL, Redis, Kafka |
| inventory-service | 8083 | `/actuator/health/liveness` | `/actuator/health/readiness` | PostgreSQL, Kafka |
| notification-service | 8084 | `/health/liveness` | `/health/readiness` | PostgreSQL, Kafka |

Healthy probes return HTTP 200 and `{"status":"UP"}`; an unavailable required dependency makes readiness return HTTP 503. Responses hide component and exception details. External dependencies are excluded from liveness. Probe results signal availability; they do not themselves block business endpoints or stop Kafka listeners.

The Spring `aws` profiles select the same readiness dependencies as `local`. The base Spring configurations include only application readiness state and the database, supporting contexts with messaging/cache disabled. The Micronaut base configuration disables Kafka consumption and Kafka health; `local` enables both. Other notification deployment configurations must enable both `notification.messaging.enabled` and `kafka.health.enabled`. For Micronaut local runs, select the environment with `MICRONAUT_ENVIRONMENTS=local`.

Kafka readiness checks broker metadata availability, not consumer partition assignment or successful event processing. Database pool waits are configured to 2 seconds, validation to 1 second, and Redis/Kafka health waits to 2 seconds. These individual limits are not a strict bound on total readiness response latency.

Spring explicitly enables graceful HTTP shutdown with a 20-second timeout **per lifecycle phase**. Micronaut enables graceful shutdown with a 20-second grace period. Integration tests verify that an active HTTP request can finish while application shutdown waits. Total process shutdown may take longer than one grace period.

For deployment, configure the load balancer to check readiness and coordinate target draining with application shutdown. The external container stop timeout must allow the measured total shutdown duration plus margin. ECS and ALB resources are not configured by this change. ALB health thresholds delay target state changes, and ALB may fail open when all targets are unhealthy.


## Structured logs and trace propagation

Spring services write ECS JSON to the console. The Micronaut notification service writes Logback JSON; its trace fields are in `mdc.trace_id` and `mdc.span_id`, while Spring uses top-level `traceId` and `spanId`. Trace IDs correlate a request across services; span IDs identify individual operations. Logs outside a current trace, such as startup messages, may omit these fields.

The order pricing client forwards W3C `traceparent`/`tracestate` using the configured Spring `RestClient.Builder`. Order and inventory outbox writers persist the active trace context in `headers` in the same database transaction as the event. Each relay restores that context before the observed Kafka send, isolating it from the scheduler's context. Consumers continue the trace; the Micronaut listener explicitly scopes processing through notification persistence and delivery.

Missing or invalid trace context starts a fresh trace without rejecting a valid business request/event. Trace metadata is diagnostic: it does not authorize requests, enforce idempotency, or change event payloads. New business logs contain IDs and counts, not emails or full request/event payloads.

Local profiles sample new root traces at 100%; base configurations use 10%, respecting the parent sampling decision. No external trace backend is required for log correlation. Spring has no span exporter dependency in this configuration; Micronaut exporters are set to `none`. A collector and trace visualization can be configured separately.

Tracing integration tests verify real HTTP and Kafka boundaries, persistence through the outbox, one canonical outgoing `traceparent`, context restoration under an unrelated scheduled observation, missing/malformed context, and scope cleanup after failures.


## Business metrics and Prometheus

Order and inventory expose `/actuator/prometheus` on their existing HTTP ports (local: 8082 and 8083). The endpoint serves Prometheus text exposition; a Prometheus server is not included. `/actuator/metrics/{name}` remains available for individual Micrometer measurements.

| Micrometer name | Labels | Increment condition |
|---|---|---|
| `orders.placed` | `customerType=B2B/B2C` | A new order and its `OrderPlaced` outbox record commit. |
| `orders.rejected` | `customerType=B2B/B2C`, `reason=stock_reservation_failed` | A stock-result event commits an actual order rejection and its outbox record. |
| `inventory.reservation.failed` | `reason=insufficient_stock` | A new failed reservation outcome commits its processed-event marker and outbox record. |

These counters increment after successful database commit. Rolled-back operations, normal HTTP idempotency replays, duplicate event IDs, and ignored late failures for cancelled orders do not increment them. Counters are pre-registered with a finite set of labels; identifiers and free-form reasons are not labels. They measure different stages of the asynchronous workflow and need not change simultaneously or have identical totals.

```bash
curl http://localhost:8082/actuator/prometheus
curl http://localhost:8083/actuator/prometheus
curl 'http://localhost:8082/actuator/metrics/orders.placed?tag=customerType:B2B'
```

Prometheus exports the counters as `orders_placed_total`, `orders_rejected_total`, and `inventory_reservation_failed_total` (reason `insufficient_stock` or `invalid_order`). The order endpoint also exposes the existing `outbox_lag_seconds` gauge, which queries the age of the oldest unpublished order event when sampled. Order and inventory also expose `outbox_parked`, the number of outbox events parked as undeliverable (see ARCHITECTURE §5.3 for re-queueing).

Counters belong to one running application instance and reset on restart. A crash between database commit and the increment can miss a measurement; an instance can also stop between scrapes. Use them for operational rates and trends, not as a durable accounting ledger. With multiple instances, scrape each instance and aggregate rates across them. The current configuration exposes the endpoints without authentication; restrict management access in the deployment network.


## Virtual threads and database concurrency

The three Spring services enable virtual threads and `spring.main.keep-alive=true`. Tests verify actual HTTP request execution on virtual threads. The Micronaut service retains its existing threading model.

All four services explicitly configure a fixed Hikari pool with maximum 10 and minimum idle 10 connections per application instance. Existing 2-second connection acquisition and 1-second validation timeouts remain unchanged. A virtual thread does not create a database connection: once all pool connections are busy, borrowers wait until a connection is returned or acquisition times out.

The Spring services expose Hikari gauges through Actuator, for example:

```bash
curl http://localhost:8081/actuator/metrics/hikaricp.connections.active
curl http://localhost:8081/actuator/metrics/hikaricp.connections.pending
curl http://localhost:8081/actuator/metrics/hikaricp.connections.max
```

Ports 8082 and 8083 provide the same measurements for order and inventory. Catalog exposes metrics JSON; Prometheus remains enabled only for order and inventory. Notification does not expose these Actuator endpoints.

Connection limits are per instance, not a global limit across the deployment. Budget the sum of all service replicas' pool maxima plus migration, monitoring and administrative connections against the database limit, allowing operational headroom.

[`scripts/benchmark_quote.py`](scripts/benchmark_quote.py) compares the real quote endpoint using platform/virtual threads and pools of 5/50 under identical resource limits. Requests carry a client-credentials token from a disposable Keycloak in the benchmark's own Compose project. The benchmark overrides are temporary; the application default remains 10. See [benchmark instructions](scripts/README.md#quote-throughput-and-pool-pressure). Local measurements describe this workload and machine; they do not establish a universally optimal pool size.

## Container images

Each service has its own multi-stage Dockerfile and an allowlist `.dockerignore`.
Build from the repository root using the service directory as the build context:

```bash
docker build -t orderflow/catalog-pricing-service:local services/catalog-pricing-service
docker build -t orderflow/order-service:local services/order-service
docker build -t orderflow/inventory-service:local services/inventory-service
docker build -t orderflow/notification-service:local services/notification-service
```

The builder uses Java 25 JDK and the service Maven wrapper. BuildKit caches Maven
artifacts separately from image layers. Image packaging skips test execution;
run `./mvnw verify` before building a release. The final image uses
`eclipse-temurin:25-jre`, runs as UID/GID 10001, and starts Java directly as PID 1
with `-XX:MaxRAMPercentage=75`. Spring images use extracted Boot layers;
notification uses its existing executable Micronaut JAR.

Set a container memory limit when running an image. The heap percentage leaves
room for native memory but does not guarantee freedom from OOM. Configuration,
including database credentials and dependency addresses, is supplied at runtime.
`EXPOSE` documents the service port; publish it explicitly with `-p` when needed.
Builds on Apple Silicon target the native Linux ARM64 platform by default, which
matches the intended ARM64 deployment. For an AMD64 target, build explicitly with
`docker buildx build --platform linux/amd64 --load ...` and test that image.

To build and verify all four images against disposable PostgreSQL, Redis and
Kafka dependencies:

```bash
python3 -m unittest discover -s scripts/tests_docker -v
```

The verifier checks readiness/liveness, the runtime user, Java PID 1, absence of
build tools, container-aware heap sizing and SIGTERM shutdown. It removes its own
containers, network and image tags; raw evidence remains under ignored
`target/image-verification/`.

To verify the complete Compose stack and one order-to-email workflow:

```bash
python3 -m unittest discover -s scripts/tests_compose -v
```

This test copies the current Git-tracked working files to a temporary directory,
uses fresh volumes and ephemeral loopback ports, and removes its own resources.
It requires Compose 2.24.4+ for the port override and does not read `infra/.env`.
Raw evidence is retained under ignored `target/compose-verification/`.
See [verification details](scripts/README.md#full-compose-verification).

To stop the normal stack while preserving its named volumes:

```bash
docker compose --env-file infra/.env -f infra/docker-compose.yaml down
```


## End-to-end scenarios

```bash
MAILPIT_URL=http://localhost:8025 scripts/smoke.sh
python3 -m unittest discover -s scripts/tests_compose -p test_scenarios.py -v
```

The smoke script checks B2C/B2B pricing, HTTP idempotency, confirmation, stock
rejection, cancellation and notification persistence. Mailpit capture checks are
optional when running the script directly. Stock/reservation and notification
read APIs are not implemented yet, so these observations use read-only SQL.

The disposable integration test also prepares a ten-unit fixture and runs
`scripts/oversell-test.sh`: 50 parallel orders must produce exactly ten
confirmations, forty rejections and ten matching reservations. The standalone
oversell script never resets stock and requires its fixture to be prepared first.
Use an exclusively owned test environment; these scripts create real orders.
See [scenario configuration and limits](scripts/README.md#order-smoke-and-oversell-scenarios).

## Continuous integration

[The CI workflow](.github/workflows/ci.yml) runs on pull requests and pushes to
`main`. Path filters select affected services, which run Java 25 Maven verification
with real Testcontainers, generate coverage reports and build ARM64 Docker images
without publishing. Common contracts/configuration changes select every service;
documentation-only changes skip service builds. `CI result` provides one stable
required status. See [CI policy and local verification](docs/CI.md).

The workflow is prepared locally; hosted execution and branch protection still
need verification after this repository is connected to GitHub.

## AWS runtime configuration

The [Terraform module](infra/terraform/README.md) defines ECR, the default network,
private RDS/SSM, seven ARM64 ECS Fargate services (including Keycloak), Service Connect and an HTTP ALB.
Runtime is disabled by default until discovery endpoints and SHA-tagged images
exist. Local mock-provider tests and the notification AWS-profile integration
check do not prove an AWS deployment. Live plan/apply, image publication and cloud
traffic remain unverified; GitHub OIDC/deployment automation is implemented and locally checked.
The AWS runtime runs the same Keycloak realm from an image built in `infra/keycloak`;
see [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) for the published images, the generated
secrets and a smoke run against the ALB.

## Manual ECS release

[Deployment runbook](docs/DEPLOYMENT.md): GitHub OIDC, ARM64 immutable image publication, verified ECS revisions and per-service rollback. Local checks pass; hosted/cloud operation awaits repository and AWS setup. First publish images, then activate Terraform; later workflow runs preserve desired counts.
