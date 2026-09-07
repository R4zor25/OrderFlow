package com.orderflow.order.infrastructure.outbox;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxCleanupJob {

    private static final Duration RETENTION = Duration.ofDays(7);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public OutboxCleanupJob(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(cron = "${order.outbox.cleanup.cron}", zone = "UTC")
    public void removeExpiredPublishedEvents() {
        jdbc.update("""
            delete from orders.outbox_event
            where published_at < ?
            """, Timestamp.from(clock.instant().minus(RETENTION)));
    }
}
