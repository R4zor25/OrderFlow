package com.orderflow.order.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.orderflow.order.events.EventEnvelope;
import com.orderflow.order.events.OrderCancelledPayload;
import com.orderflow.order.events.OrderConfirmedPayload;
import com.orderflow.order.events.OrderPlacedPayload;
import com.orderflow.order.events.OrderRejectedPayload;
import com.orderflow.order.orders.domain.Order;
import com.orderflow.order.orders.domain.OrderStatus;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

@Component
public class OrderOutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Tracer tracer;
    private final Propagator propagator;

    public OrderOutboxWriter(JdbcTemplate jdbc, ObjectMapper json, Tracer tracer, Propagator propagator) {
        this.jdbc = jdbc;
        this.json = json;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public void placed(Order order, Instant now) {
        var lines = order.getLines().stream()
            .map(line -> new OrderPlacedPayload.Line(line.getSku(), line.getQuantity()))
            .toList();
        write(order, "OrderPlaced", new OrderPlacedPayload(order.getId(), order.getCustomer().getId(),
            order.getCustomer().getType(), lines), now);
    }

    public void confirmed(Order order, Instant now) {
        write(order, "OrderConfirmed", new OrderConfirmedPayload(order.getId(), order.getCustomer().getId(),
            order.getCustomer().getEmail(), order.getTotalGross(), order.getCurrency()), now);
    }

    public void rejected(Order order, Instant now) {
        write(order, "OrderRejected", new OrderRejectedPayload(order.getId(), order.getCustomer().getId(),
            order.getCustomer().getEmail(), order.getRejectionReason()), now);
    }

    public void cancelled(Order order, OrderStatus previousStatus, Instant now) {
        write(order, "OrderCancelled", new OrderCancelledPayload(order.getId(), order.getCustomer().getId(),
            order.getCustomer().getEmail(), previousStatus), now);
    }

    private void write(Order order, String eventType, Object payload, Instant now) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox write requires an active transaction");
        }
        var message = new EventEnvelope<>(UUID.randomUUID(), eventType, 1, now, order.getId().toString(), payload);
        var headers = new HashMap<String, String>();
        var span = tracer.currentSpan();
        if (span != null) {
            propagator.inject(span.context(), headers, Map::put);
        }
        jdbc.update("""
            insert into orders.outbox_event
                (id, aggregate_type, aggregate_id, event_type, payload, headers, created_at)
            values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
            """, message.eventId(), "Order", order.getId(), eventType,
            json.writeValueAsString(message), json.writeValueAsString(headers), Timestamp.from(now));
    }
}
