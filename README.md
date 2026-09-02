# OrderFlow

OrderFlow is a B2B/B2C e-commerce backend built as a set of services. The target architecture and service responsibilities are described in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Current state

The repository contains a Maven aggregator, a Spring Boot `catalog-pricing-service`, and local infrastructure in Docker Compose. The catalog service connects to PostgreSQL, applies Flyway migrations for its `catalog` schema, exposes Actuator health, and provides paged product reads by category and SKU. Product creation, pricing logic and messaging features have not been implemented yet. The other services shown in the architecture document are planned.

## Build and test

JDK 25 is required. The Maven Wrapper supplies the Maven version.

```bash
./mvnw verify
```

The root POM builds the service currently present. To build it independently, run `./mvnw verify` from `services/catalog-pricing-service/`. The database and API tests start temporary PostgreSQL Testcontainers, so Docker must be running.

## Run locally

If `infra/.env` does not exist yet, copy `infra/.env.example` to `infra/.env` and replace the placeholder with your own local password. Keep an existing working `.env` unchanged. Compose reads `.env`, not `.env.example`; the example is only a committed template. The real `.env` is ignored by Git. Start the infrastructure:

```bash
docker compose -f infra/docker-compose.yaml up -d --wait
```

The Java process runs on the Mac, outside Compose. Pass the same `POSTGRES_PASSWORD` value to its environment (for example, in an IntelliJ run configuration), select the `local` profile, and start the service:

```bash
cd services/catalog-pricing-service
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
curl http://localhost:8081/actuator/health
curl 'http://localhost:8081/api/v1/products?category=FURNITURE&page=0&size=3'
curl http://localhost:8081/api/v1/products/CHAIR-001
```

The expected health status is `UP`. The local profile connects to `localhost:5433`, which Compose forwards to PostgreSQL on port 5432. The `aws` profile reads its database URL, username and password from `CATALOG_DB_URL`, `CATALOG_DB_USERNAME` and `CATALOG_DB_PASSWORD`.

On startup, Flyway applies `V1__catalog.sql` and `V2__seed.sql` once. The `catalog` schema then contains 20 sample products, three customer groups and 12 B2B price tiers. Applied versions are recorded in `catalog.flyway_schema_history`.

The product list accepts optional `category`, `page` (zero-based, default 0) and `size` (default 20, capped at 100) parameters. Items are sorted by SKU. A product that does not exist returns 404.
