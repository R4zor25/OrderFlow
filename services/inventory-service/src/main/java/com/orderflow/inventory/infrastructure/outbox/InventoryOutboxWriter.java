package com.orderflow.inventory.infrastructure.outbox;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

import com.orderflow.inventory.infrastructure.events.EventEnvelope;
import com.orderflow.inventory.infrastructure.events.LowStockPayload;
import com.orderflow.inventory.infrastructure.events.StockReservationFailedPayload;
import com.orderflow.inventory.infrastructure.events.StockReservedPayload;
import com.orderflow.inventory.infrastructure.events.StockReleasedPayload;
import com.orderflow.inventory.reservation.domain.ReservationLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

@Component
public class InventoryOutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final Tracer tracer;
    private final Propagator propagator;

    public InventoryOutboxWriter(JdbcTemplate jdbc, ObjectMapper json, Clock clock,
                                 Tracer tracer, Propagator propagator) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public void reserved(UUID orderId, List<ReservationLine> reservedLines) {
        var eventLines = reservedLines.stream()
            .map(line -> new StockReservedPayload.Line(line.sku(), line.quantity()))
            .toList();
        write("Order", orderId.toString(), "StockReserved", new StockReservedPayload(orderId, eventLines));
    }

    public void failed(UUID orderId, String reason, List<String> unavailableSkus) {
        write("Order", orderId.toString(), "StockReservationFailed",
            new StockReservationFailedPayload(orderId, reason, unavailableSkus));
    }

    public void released(UUID orderId) {
        write("Order", orderId.toString(), "StockReleased", new StockReleasedPayload(orderId));
    }

    public void lowStock(String sku, int available, int threshold) {
        write("StockItem", sku, "LowStock", new LowStockPayload(sku, available, threshold));
    }

    private void write(String aggregateType, String aggregateId, String eventType, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox write requires an active transaction");
        }
        var event = new EventEnvelope<>(UUID.randomUUID(), eventType, 1,
            clock.instant(), aggregateId, payload);
        var headers = new HashMap<String, String>();
        var currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            propagator.inject(currentSpan.context(), headers, (carrier, key, value) -> carrier.put(key, value));
        }
        jdbc.update("""
            insert into inventory.outbox_event
                (id, aggregate_type, aggregate_id, event_type, payload, headers, created_at)
            values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
            """, event.eventId(), aggregateType, aggregateId, eventType,
            json.writeValueAsString(event), headers.isEmpty() ? null : json.writeValueAsString(headers),
            Timestamp.from(event.occurredAt()));
    }
}
