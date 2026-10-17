package com.orderflow.order.infrastructure.outbox;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class OutboxMetrics implements MeterBinder {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public OutboxMetrics(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("outbox.lag.seconds", this, OutboxMetrics::lagSeconds)
            .description("Age of the oldest unpublished order event")
            .baseUnit("seconds")
            .register(registry);
        Gauge.builder("outbox.parked", this, OutboxMetrics::parkedEvents)
            .description("Number of outbox events parked as undeliverable")
            .register(registry);
    }

    private double lagSeconds() {
        Timestamp oldest = jdbc.queryForObject("""
            select min(created_at) from orders.outbox_event where published_at is null
            """, Timestamp.class);
        if (oldest == null) {
            return 0.0;
        }
        return Math.max(0.0, Duration.between(oldest.toInstant(), clock.instant()).toMillis() / 1000.0);
    }

    private double parkedEvents() {
        Long parked = jdbc.queryForObject("""
            select count(*) from orders.outbox_event
            where parked_at is not null and published_at is null
            """, Long.class);
        return parked == null ? 0.0 : parked;
    }
}
