package com.orderflow.inventory.infrastructure.outbox;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;

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
        Timestamp oldest = jdbc.queryForObject("""
            select min(created_at) from inventory.outbox_event where published_at is null
            """, Timestamp.class);
        if (oldest == null) {
            return 0.0;
        }
        return Math.max(0.0, Duration.between(oldest.toInstant(), clock.instant()).toMillis() / 1000.0);
    }
}
