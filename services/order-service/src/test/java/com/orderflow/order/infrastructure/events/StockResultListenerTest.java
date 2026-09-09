package com.orderflow.order.infrastructure.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.orderflow.order.application.StockResultService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
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
    void rejectsUnknownTypeAndUnsupportedVersion() {
        var future = new EventEnvelope<>(UUID.randomUUID(), "StockReserved", 2, Instant.now(),
            orderId.toString(), new StockReservedPayload(orderId,
                List.of(new StockReservedPayload.Line("CHAIR-001", 2))));

        assertThatThrownBy(() -> listener.onMessage(record(orderId.toString(), future)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> listener.onMessage(new ConsumerRecord<>("inventory.stock-events.v1", 0,
            0, orderId.toString(), "{\"eventType\":\"Surprise\"}")))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(results);
    }

    private ConsumerRecord<String, String> record(String key, Object event) {
        return new ConsumerRecord<>("inventory.stock-events.v1", 0, 0, key, json.writeValueAsString(event));
    }
}
