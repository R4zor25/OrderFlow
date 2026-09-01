# OrderFlow

OrderFlow is a B2B/B2C e-commerce backend built as a set of services. The target architecture and service responsibilities are described in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Current state

The repository contains a Maven aggregator and a Spring Boot skeleton for `catalog-pricing-service`. Product, pricing, persistence and messaging features have not been implemented yet. The other services and infrastructure shown in the architecture document are planned.

## Build and test

JDK 25 is required. The Maven Wrapper supplies the Maven version.

```bash
./mvnw verify
```

The root POM builds the service currently present. To build it independently, run `./mvnw verify` from `services/catalog-pricing-service/`. The current test checks that the Spring application context loads.
