package com.orderflow.inventory.infrastructure.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class OutboxLagMetric implements MeterBinder {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public OutboxLagMetric(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("outbox.lag.seconds", this, OutboxLagMetric::lagSeconds)
            .description("Age of the oldest unpublished inventory event")
            .baseUnit("seconds")
            .register(registry);
    }

    private double lagSeconds() {
        Instant oldest = jdbc.query("""
            select created_at from inventory.outbox_event
            where published_at is null
            order by created_at, id
            limit 1
            """, result -> result.next() ? result.getTimestamp(1).toInstant() : null);
        if (oldest == null) {
            return 0.0;
        }
        return Math.max(0.0, Duration.between(oldest, clock.instant()).toMillis() / 1000.0);
    }
}
