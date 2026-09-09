package com.orderflow.inventory.infrastructure.outbox;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

import com.orderflow.inventory.infrastructure.events.EventEnvelope;
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

    public InventoryOutboxWriter(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public void reserved(UUID orderId, List<ReservationLine> lines) {
        var quantityBySku = new TreeMap<String, Integer>();
        for (var line : lines) {
            quantityBySku.merge(line.sku(), line.quantity(), Math::addExact);
        }
        var eventLines = quantityBySku.entrySet().stream()
            .map(entry -> new StockReservedPayload.Line(entry.getKey(), entry.getValue()))
            .toList();
        write(orderId, "StockReserved", new StockReservedPayload(orderId, eventLines));
    }

    public void failed(UUID orderId, String unavailableSku) {
        write(orderId, "StockReservationFailed",
            new StockReservationFailedPayload(orderId, "Insufficient stock", List.of(unavailableSku)));
    }

    public void released(UUID orderId) {
        write(orderId, "StockReleased", new StockReleasedPayload(orderId));
    }

    private void write(UUID orderId, String eventType, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox write requires an active transaction");
        }
        var event = new EventEnvelope<>(UUID.randomUUID(), eventType, 1,
            clock.instant(), orderId.toString(), payload);
        jdbc.update("""
            insert into inventory.outbox_event
                (id, aggregate_type, aggregate_id, event_type, payload, created_at)
            values (?, 'Order', ?, ?, ?::jsonb, ?)
            """, event.eventId(), orderId, eventType,
            json.writeValueAsString(event), Timestamp.from(event.occurredAt()));
    }
}
