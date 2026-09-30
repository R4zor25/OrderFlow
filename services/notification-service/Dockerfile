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
USER 10001:10001
EXPOSE 8084
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=45", "-XX:+ExitOnOutOfMemoryError", "-XX:MaxMetaspaceSize=224m", "-jar", "application.jar"]
