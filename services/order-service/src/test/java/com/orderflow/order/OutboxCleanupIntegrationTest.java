package com.orderflow.order;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = "order.outbox.cleanup.cron=*/1 * * * * *")
@Import({PostgresTestConfiguration.class, OutboxCleanupIntegrationTest.FixedClockConfiguration.class})
class OutboxCleanupIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void deletesOnlyEventsPublishedMoreThanSevenDaysAgo() {
        UUID expired = insert(NOW.minus(Duration.ofDays(7)).minusSeconds(1));
        UUID boundary = insert(NOW.minus(Duration.ofDays(7)));
        UUID recent = insert(NOW.minus(Duration.ofDays(6)));
        UUID unpublished = insert(null);

        await().atMost(Duration.ofSeconds(4)).untilAsserted(() ->
            assertThat(exists(expired)).isFalse());
        assertThat(exists(boundary)).isTrue();
        assertThat(exists(recent)).isTrue();
        assertThat(exists(unpublished)).isTrue();
    }

    private UUID insert(Instant publishedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into orders.outbox_event
                (id, aggregate_type, aggregate_id, event_type, payload, created_at, published_at)
            values (?, 'Order', ?, 'OrderPlaced', '{}'::jsonb, ?, ?)
            """, id, UUID.randomUUID(), Timestamp.from(NOW.minus(Duration.ofDays(10))),
            publishedAt == null ? null : Timestamp.from(publishedAt));
        return id;
    }

    private boolean exists(UUID id) {
        return jdbc.queryForObject("""
            select count(*) from orders.outbox_event where id = ?
            """, Integer.class, id) == 1;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
