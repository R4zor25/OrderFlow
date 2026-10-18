package com.orderflow.order.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.orderflow.order.events.EventEnvelope;
import com.orderflow.order.events.StockReservationFailedPayload;
import com.orderflow.order.events.StockReservedPayload;
import com.orderflow.order.orders.application.StockResultService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class StockResultListenerTest {

    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private final StockResultService results = mock(StockResultService.class);
    private final StockResultListener listener = new StockResultListener(json, results);
    private final UUID orderId = UUID.randomUUID();

    @Test
    void dispatchesSupportedResults() {
        UUID reservedId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();

        listener.onMessage(record(orderId.toString(), new EventEnvelope<>(reservedId, "StockReserved", 1,
            Instant.now(), orderId.toString(), new StockReservedPayload(orderId,
                List.of(new StockReservedPayload.Line("CHAIR-001", 2))))));
        listener.onMessage(record(orderId.toString(), new EventEnvelope<>(failedId,
            "StockReservationFailed", 1, Instant.now(), orderId.toString(),
            new StockReservationFailedPayload(orderId, "Insufficient stock", List.of("CHAIR-001")))));

        verify(results).reserved(reservedId, orderId);
        verify(results).failed(failedId, orderId, "Insufficient stock");
    }

    @Test
    void ignoresOtherKnownStockEvents() {
        listener.onMessage(new ConsumerRecord<>("inventory.stock-events.v1", 0, 0,
            "CHAIR-001", "{\"eventType\":\"LowStock\"}"));
        listener.onMessage(new ConsumerRecord<>("inventory.stock-events.v1", 0, 1,
            orderId.toString(), "{\"eventType\":\"StockReleased\"}"));

        verifyNoInteractions(results);
    }

    @Test
    void rejectsWrongKafkaKeyOrPayloadOrderId() {
        var event = new EventEnvelope<>(UUID.randomUUID(), "StockReserved", 1, Instant.now(),
            orderId.toString(), new StockReservedPayload(orderId,
                List.of(new StockReservedPayload.Line("CHAIR-001", 2))));

        assertThatThrownBy(() -> listener.onMessage(record(UUID.randomUUID().toString(), event)))
            .isInstanceOf(IllegalArgumentException.class);
        var wrongPayload = new EventEnvelope<>(UUID.randomUUID(), "StockReserved", 1, Instant.now(),
            orderId.toString(), new StockReservedPayload(UUID.randomUUID(),
                List.of(new StockReservedPayload.Line("CHAIR-001", 2))));
        assertThatThrownBy(() -> listener.onMessage(record(orderId.toString(), wrongPayload)))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(results);
    }

    @Test
    void skipsUnknownStockEventType() {
        listener.onMessage(raw("{\"eventType\":\"StockAudited\",\"eventVersion\":1}"));

        verifyNoInteractions(results);
    }

    @Test
    void skipsNewerStockEventVersion() {
        var future = new EventEnvelope<>(UUID.randomUUID(), "StockReserved", 2, Instant.now(),
            orderId.toString(), new StockReservedPayload(orderId,
                List.of(new StockReservedPayload.Line("CHAIR-001", 2))));

        listener.onMessage(record(orderId.toString(), future));

        verifyNoInteractions(results);
    }

    @Test
    void rejectsMissingStockEventVersion() {
        String missingVersion = """
            {"eventId":"%s","eventType":"StockReserved","occurredAt":"2026-10-01T12:00:00Z","aggregateId":"%s",
             "payload":{"orderId":"%s","lines":[{"sku":"CHAIR-001","quantity":1}]}}
            """.formatted(UUID.randomUUID(), orderId, orderId);

        assertThatThrownBy(() -> listener.onMessage(raw(missingVersion))).isInstanceOf(JacksonException.class);
        verifyNoInteractions(results);
    }

    @Test
    void rejectsMissingEventType() {
        assertThatThrownBy(() -> listener.onMessage(raw("{\"eventVersion\":1,\"payload\":{}}")))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(results);
    }

    private ConsumerRecord<String, String> record(String key, Object event) {
        return new ConsumerRecord<>("inventory.stock-events.v1", 0, 0, key, json.writeValueAsString(event));
    }

    private ConsumerRecord<String, String> raw(String value) {
        return new ConsumerRecord<>("inventory.stock-events.v1", 0, 0, orderId.toString(), value);
    }
}
