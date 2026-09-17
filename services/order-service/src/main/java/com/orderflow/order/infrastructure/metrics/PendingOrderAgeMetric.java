package com.orderflow.order.infrastructure.metrics;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PendingOrderAgeMetric implements MeterBinder {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PendingOrderAgeMetric(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("orders.pending.oldest.age.seconds", this, PendingOrderAgeMetric::oldestPendingSeconds)
            .description("Age of the oldest pending order")
            .baseUnit("seconds")
            .register(registry);
    }

    private double oldestPendingSeconds() {
        Timestamp oldest = jdbc.queryForObject("""
            select min(created_at) from orders.orders where status = 'PENDING'
            """, Timestamp.class);
        if (oldest == null) {
            return 0.0;
        }
        return Math.max(0.0, Duration.between(oldest.toInstant(), clock.instant()).toMillis() / 1000.0);
    }
}
