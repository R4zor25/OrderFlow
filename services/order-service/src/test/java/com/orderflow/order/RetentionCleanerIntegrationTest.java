package com.orderflow.order;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.orderflow.order.infrastructure.retention.RetentionCleaner;
import com.orderflow.order.infrastructure.retention.RetentionJob;
import net.javacrumbs.shedlock.core.LockAssert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import({PostgresTestConfiguration.class, RetentionCleanerIntegrationTest.FixedClockConfiguration.class})
class RetentionCleanerIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    @Autowired
    private RetentionCleaner cleaner;

    @Autowired
    private RetentionJob job;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> outbox = new ArrayList<>();
    private final List<UUID> processed = new ArrayList<>();

    @AfterEach
    void removeRows() {
        jdbc.update("delete from orders.outbox_event where id = any(?)", (Object) outbox.toArray(UUID[]::new));
        jdbc.update("delete from orders.processed_event where event_id = any(?)", (Object) processed.toArray(UUID[]::new));
    }

    @Test
    void removesPublishedOutboxRowsOlderThanRetentionInBatches() {
        for (int i = 0; i < 2500; i++) {
            insertOutbox(NOW.minus(Duration.ofDays(8)), null);
        }
        UUID boundary = insertOutbox(NOW.minus(Duration.ofDays(7)), null);
        UUID recent = insertOutbox(NOW.minus(Duration.ofDays(6)), null);
        UUID unpublished = insertOutbox(null, null);
        UUID parked = insertOutbox(null, NOW.minus(Duration.ofDays(30)));

        cleaner.clean();

        assertThat(jdbc.queryForObject("""
            select count(*) from orders.outbox_event where published_at < ?
            """, Integer.class, Timestamp.from(NOW.minus(Duration.ofDays(7))))).isZero();
        assertThat(outboxExists(boundary)).isTrue();
        assertThat(outboxExists(recent)).isTrue();
        assertThat(outboxExists(unpublished)).isTrue();
        assertThat(outboxExists(parked)).isTrue();
    }

    @Test
    void removesProcessedEventsOlderThanFourteenDays() {
        UUID expired = insertProcessed(NOW.minus(Duration.ofDays(14)).minusSeconds(1));
        UUID boundary = insertProcessed(NOW.minus(Duration.ofDays(14)));
        UUID recent = insertProcessed(NOW.minus(Duration.ofDays(13)));

        cleaner.clean();

        assertThat(processedExists(expired)).isFalse();
        assertThat(processedExists(boundary)).isTrue();
        assertThat(processedExists(recent)).isTrue();
    }

    @Test
    void jobRunsOnlyUnderTheSchedulerLock() {
        assertThatNoException().isThrownBy(job::run);
        assertThat(jdbc.queryForObject("select count(*) from orders.shedlock where name = 'order-retention'",
            Integer.class)).isEqualTo(1);
        assertThatThrownBy(LockAssert::assertLocked)
            .isInstanceOf(IllegalStateException.class);
    }

    private UUID insertOutbox(Instant publishedAt, Instant parkedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into orders.outbox_event
                (id, aggregate_type, aggregate_id, event_type, payload, created_at, published_at, parked_at)
            values (?, 'Order', ?, 'OrderPlaced', '{}'::jsonb, ?, ?, ?)
            """, id, UUID.randomUUID(), Timestamp.from(NOW.minus(Duration.ofDays(40))),
            publishedAt == null ? null : Timestamp.from(publishedAt),
            parkedAt == null ? null : Timestamp.from(parkedAt));
        outbox.add(id);
        return id;
    }

    private UUID insertProcessed(Instant processedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into orders.processed_event (event_id, processed_at) values (?, ?)",
            id, Timestamp.from(processedAt));
        processed.add(id);
        return id;
    }

    private boolean outboxExists(UUID id) {
        return jdbc.queryForObject("select count(*) from orders.outbox_event where id = ?", Integer.class, id) == 1;
    }

    private boolean processedExists(UUID id) {
        return jdbc.queryForObject("select count(*) from orders.processed_event where event_id = ?",
            Integer.class, id) == 1;
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
