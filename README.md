# OrderFlow

OrderFlow is a B2B/B2C e-commerce backend built as a set of services. The target architecture and service responsibilities are described in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Current state

The repository contains a Maven aggregator, Spring Boot `catalog-pricing-service` and `order-service` modules, and local infrastructure in Docker Compose. The catalog service connects to PostgreSQL, applies Flyway migrations for its `catalog` schema, exposes Actuator health, and provides product reads, creation, price quotes, and price-list updates. The order service has its own `orders` schema, four sample customers, and an `Order` aggregate with persisted lines; order HTTP endpoints come in later steps. Frequently read price tiers are cached in Redis. Messaging features and the other services shown in the architecture document are planned.

## Build and test

JDK 25 is required. The Maven Wrapper supplies the Maven version.

```bash
./mvnw verify
```

The root POM builds both services. To build either independently, run `./mvnw verify` from its directory under `services/`. The database and API tests start temporary PostgreSQL and Redis Testcontainers, so Docker must be running.

## Run locally

If `infra/.env` does not exist yet, copy `infra/.env.example` to `infra/.env` and replace the placeholder with your own local password. Keep an existing working `.env` unchanged. Compose reads `.env`, not `.env.example`; the example is only a committed template. The real `.env` is ignored by Git. Start the infrastructure:

```bash
docker compose -f infra/docker-compose.yaml up -d --wait
```

The Java processes run on the Mac, outside Compose. Pass the same `POSTGRES_PASSWORD` value to their environments (for example, in IntelliJ run configurations), select the `local` profile, and start the service you need. In a terminal, keep this command running:

```bash
cd services/catalog-pricing-service
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

In another terminal, try the endpoints:

```bash
curl http://localhost:8081/actuator/health
curl 'http://localhost:8081/api/v1/products?category=FURNITURE&page=0&size=3'
curl http://localhost:8081/api/v1/products/CHAIR-001
curl -i -X POST http://localhost:8081/api/v1/products \
  -H 'Content-Type: application/json' \
  -d '{"sku":"TEST-NEW-001","name":"New Test Product","category":"OFFICE","listPriceNet":19.90,"vatRate":0.27}'
curl -i -X POST http://localhost:8081/api/v1/quotes \
  -H 'Content-Type: application/json' \
  -d '{"customerGroup":"B2B_GOLD","lines":[{"sku":"CHAIR-001","quantity":25}]}'
curl -i -X PUT http://localhost:8081/api/v1/products/CHAIR-001/prices \
  -H 'Content-Type: application/json' \
  -d '{"customerGroup":"B2B_GOLD","tiers":[{"minQty":1,"unitPriceNet":84.90,"validFrom":"2020-01-01"}]}'
```

The expected health status is `UP`. The local profile connects to `localhost:5433`, which Compose forwards to PostgreSQL on port 5432. The `aws` profile reads its database URL, username and password from `CATALOG_DB_URL`, `CATALOG_DB_USERNAME` and `CATALOG_DB_PASSWORD`.

On startup, Flyway applies `V1__catalog.sql` and `V2__seed.sql` once. The `catalog` schema then contains 20 sample products, three customer groups and 12 B2B price tiers. Applied versions are recorded in `catalog.flyway_schema_history`.

The product list accepts optional `category`, `page` (zero-based, default 0) and `size` (default 20, capped at 100) parameters. Items are sorted by SKU. A product that does not exist returns 404.

Product creation requires `sku`, `name`, `category`, a positive `listPriceNet`, and a `vatRate` between 0 and 1. A successful request returns 201 with a `Location` header. Invalid request fields return 400, an existing SKU returns 409, and an out-of-range VAT rate returns 422. Errors use RFC 9457 problem details.

`POST /api/v1/quotes` accepts a customer group and one or more SKU/quantity lines. It returns EUR prices using the highest applicable quantity tier, or the product list price if no tier applies. B2C lines show gross amounts; B2B lines show net and VAT separately. The response always includes net, VAT, and gross totals. Invalid input returns 400. Unknown or inactive SKUs return 422 with every missing SKU in the `skus` array. Quoting writes no data.

The PUT example above changes the local seed price for `CHAIR-001`. `PUT /api/v1/products/{sku}/prices` replaces the full price-tier list for one customer group and SKU. An empty `tiers` array clears that group's tiers. A successful update returns 204 and invalidates the corresponding Redis cache entry after the database transaction commits. Repeating a quote for the same group and SKU reads its tiers from Redis; cache entries expire after 10 minutes. The `local` profile connects to Redis at `localhost:6379`; the `aws` profile reads `CATALOG_REDIS_HOST` and optional `CATALOG_REDIS_PORT` (default 6379).

## Order service skeleton

The `order-service` is a separate Spring Boot application on port 8082. It uses the `orders` PostgreSQL schema; Flyway creates `customer`, `orders`, `order_line`, `outbox_event`, and `processed_event` and seeds four sample customers. The domain model can create a `PENDING` order with its priced lines, but no HTTP order creation endpoint exists yet. The state transition methods are added in the next learning step.

With Compose running and `POSTGRES_PASSWORD` set to the value in `infra/.env`, start it separately:

```bash
cd services/order-service
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Then check `http://localhost:8082/actuator/health`. The `aws` profile expects `ORDER_DB_URL`, `ORDER_DB_USERNAME`, and `ORDER_DB_PASSWORD`.
