package com.orderflow.order.infrastructure.outbox;

import java.sql.Timestamp;
import java.util.HashMap;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

@Component
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    private final Tracer tracer;
    private final Propagator propagator;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper json, Tracer tracer, Propagator propagator) {
        this.jdbc = jdbc;
        this.json = json;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public void write(OutboxEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox write requires an active transaction");
        }
        var message = event.message();
        var headers = new HashMap<String, String>();
        var span = tracer.currentSpan();
        if (span != null) {
            propagator.inject(span.context(), headers, java.util.Map::put);
        }
        jdbc.update("""
            insert into orders.outbox_event
                (id, aggregate_type, aggregate_id, event_type, payload, headers, created_at)
            values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
            """, message.eventId(), event.aggregateType(), event.aggregateId(),
            message.eventType(), json.writeValueAsString(message), json.writeValueAsString(headers), Timestamp.from(message.occurredAt()));
    }
}
