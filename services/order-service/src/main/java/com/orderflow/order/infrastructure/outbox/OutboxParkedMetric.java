package com.orderflow.order.infrastructure.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class OutboxParkedMetric implements MeterBinder {

    private final JdbcTemplate jdbc;

    public OutboxParkedMetric(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("outbox.parked", this, OutboxParkedMetric::parkedEvents)
            .description("Number of outbox events parked as undeliverable")
            .register(registry);
    }

    private double parkedEvents() {
        Long parked = jdbc.queryForObject("""
            select count(*) from orders.outbox_event
            where parked_at is not null and published_at is null
            """, Long.class);
        return parked == null ? 0.0 : parked;
    }
}
