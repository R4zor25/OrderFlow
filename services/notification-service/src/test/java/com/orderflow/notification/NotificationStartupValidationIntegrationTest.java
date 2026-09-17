package com.orderflow.notification;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationStartupValidationIntegrationTest {

    private static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.3.1");
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-bookworm");

    @BeforeAll
    static void startInfrastructure() {
        kafka.start();
        postgres.start();
    }

    @AfterAll
    static void stopInfrastructure() {
        kafka.stop();
        postgres.stop();
    }

    @Test
    void invalidOpsEmailStopsStartupWhenMessagingIsEnabled() {
        assertThatThrownBy(() -> ApplicationContext.run(Map.of(
                "kafka.bootstrap.servers", kafka.getBootstrapServers(),
                "notification.messaging.enabled", "true",
                "notification.ops-email", "a".repeat(308) + "@example.test",
                "datasources.default.url", postgres.getJdbcUrl(),
                "datasources.default.username", postgres.getUsername(),
                "datasources.default.password", postgres.getPassword())).close())
                .hasStackTraceContaining("notification.ops-email must contain 1 to 320 characters");
    }
}
