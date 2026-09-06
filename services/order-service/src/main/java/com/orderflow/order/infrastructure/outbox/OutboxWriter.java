package com.orderflow.order.infrastructure.outbox;

import java.sql.Timestamp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

@Component
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void write(OutboxEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox write requires an active transaction");
        }
        var message = event.message();
        jdbc.update("""
            insert into orders.outbox_event
                (id, aggregate_type, aggregate_id, event_type, payload, created_at)
            values (?, ?, ?, ?, ?::jsonb, ?)
            """, message.eventId(), event.aggregateType(), event.aggregateId(),
            message.eventType(), json.writeValueAsString(message), Timestamp.from(message.occurredAt()));
    }
}
