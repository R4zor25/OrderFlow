# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY --chmod=0755 mvnw ./
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B -ntp dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /application
COPY --from=build /build/target/notification-service-*.jar application.jar
# AWS JDBC URLs verify the RDS certificate against this bundle (sslmode=verify-full); it goes into an existing
# directory because ADD would create a missing parent with the file mode (644), which the runtime user cannot enter.
ADD --checksum=sha256:90a6f1a7978a1b1b096dca6c57511ca48d3b8cf052f8e704baf358f34ea2f21b --chmod=644 https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem /application/rds-global-bundle.pem
USER 10001:10001
EXPOSE 8084
# Two malloc arenas keep native memory stable: with the default (eight per core) it varied by up to 60 MiB
# between identical runs, enough to push a full heap past the container limit.
ENV MALLOC_ARENA_MAX=2
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=45", "-XX:+ExitOnOutOfMemoryError", "-XX:MaxMetaspaceSize=224m", "-jar", "application.jar"]
