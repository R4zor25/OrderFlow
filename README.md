# OrderFlow

OrderFlow is a B2B/B2C e-commerce backend built as a set of services. The target architecture and service responsibilities are described in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Current state

The repository contains a Maven aggregator, a Spring Boot skeleton for `catalog-pricing-service`, and local infrastructure in Docker Compose. The catalog service can connect to PostgreSQL and exposes Actuator health. Product, pricing, persistence mappings and messaging features have not been implemented yet. The other services shown in the architecture document are planned.

## Build and test

JDK 25 is required. The Maven Wrapper supplies the Maven version.

```bash
./mvnw verify
```

The root POM builds the service currently present. To build it independently, run `./mvnw verify` from `services/catalog-pricing-service/`. The integration test starts a PostgreSQL Testcontainer, so Docker must be running.

## Run locally

Create `infra/.env` using `infra/.env.example` as a template and choose a local password. The real `.env` is ignored by Git. Start the infrastructure:

```bash
docker compose -f infra/docker-compose.yaml up -d --wait
```

The Java process runs on the Mac, outside Compose. Pass the same `POSTGRES_PASSWORD` value to its environment (for example, in an IntelliJ run configuration), select the `local` profile, and start the service:

```bash
cd services/catalog-pricing-service
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
curl http://localhost:8081/actuator/health
```

The expected health status is `UP`. The local profile connects to `localhost:5433`, which Compose forwards to PostgreSQL on port 5432. The `aws` profile reads its database URL, username and password from `CATALOG_DB_URL`, `CATALOG_DB_USERNAME` and `CATALOG_DB_PASSWORD`.
