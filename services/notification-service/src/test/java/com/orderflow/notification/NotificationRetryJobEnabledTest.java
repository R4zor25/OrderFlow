package com.orderflow.notification;

import com.orderflow.notification.infrastructure.delivery.NotificationRetryJob;
import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@MicronautTest(transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationRetryJobEnabledTest implements TestPropertyProvider {

    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-bookworm");

    static {
        postgres.start();
    }

    @Inject
    ApplicationContext context;

    @Override
    public Map<String, String> getProperties() {
        return Map.of(
                "datasources.default.url", postgres.getJdbcUrl(),
                "datasources.default.username", postgres.getUsername(),
                "datasources.default.password", postgres.getPassword(),
                "notification.channel", "LOG",
                "notification.delivery.retry-enabled", "true",
                "notification.delivery.retry-interval", "1h");
    }

    @Test
    void retryJobIsScheduledWhenEnabled() {
        assertThat(context.containsBean(NotificationRetryJob.class)).isTrue();
    }
}
