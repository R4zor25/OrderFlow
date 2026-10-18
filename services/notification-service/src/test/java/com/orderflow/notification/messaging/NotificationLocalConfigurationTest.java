package com.orderflow.notification.messaging;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationLocalConfigurationTest {

    @Test
    void localKafkaBootstrapDefaultsToTheHostListener() {
        // Only the environment is started: property resolution needs no database, Kafka or beans.
        try (ApplicationContext context = ApplicationContext.builder().environments("local")
                .deduceEnvironment(false).build()) {
            var environment = context.getEnvironment();
            environment.start();

            assertThat(environment.getProperty("kafka.bootstrap.servers", String.class))
                .contains("localhost:9094");
        }
    }
}
