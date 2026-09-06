package com.orderflow.order.infrastructure.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.orderflow.order.domain.Order;
import com.orderflow.order.infrastructure.events.EventEnvelope;
import com.orderflow.order.infrastructure.events.OrderPlacedPayload;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class OrderPlacedOutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OrderPlacedOutboxWriter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void write(Order order, Instant now) {
        UUID eventId = UUID.randomUUID();
        var lines = order.getLines().stream()
            .map(line -> new OrderPlacedPayload.Line(line.getSku(), line.getQuantity()))
            .toList();
        var payload = new OrderPlacedPayload(order.getId(), order.getCustomer().getId(),
            order.getCustomer().getType(), lines);
        var event = new EventEnvelope<>(eventId, "OrderPlaced", 1, now,
            order.getId().toString(), payload);
        jdbc.update("""
            insert into orders.outbox_event
                (id, aggregate_type, aggregate_id, event_type, payload, created_at)
            values (?, 'Order', ?, 'OrderPlaced', ?::jsonb, ?)
            """, eventId, order.getId(), json.writeValueAsString(event), Timestamp.from(now));
    }
}
