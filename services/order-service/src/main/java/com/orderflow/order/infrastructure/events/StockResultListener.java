package com.orderflow.order.infrastructure.events;

import java.util.UUID;

import com.orderflow.order.application.StockResultService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "order.messaging", name = "enabled", havingValue = "true")
public class StockResultListener {

    private final ObjectMapper json;
    private final StockResultService results;

    public StockResultListener(ObjectMapper json, StockResultService results) {
        this.json = json;
        this.results = results;
    }

    @KafkaListener(topics = "inventory.stock-events.v1")
    public void onMessage(ConsumerRecord<String, String> record) {
        String type = json.readTree(record.value()).path("eventType").asString();
        switch (type) {
            case "StockReserved" -> reserved(record);
            case "StockReservationFailed" -> failed(record);
            case "StockReleased", "LowStock" -> { }
            default -> throw new IllegalArgumentException("Unsupported stock event type: " + type);
        }
    }

    private void reserved(ConsumerRecord<String, String> record) {
        EventEnvelope<StockReservedPayload> event = json.readValue(record.value(), new TypeReference<>() {});
        StockReservedPayload payload = event.payload();
        validateIdentity(record, event, payload.orderId());
        if (payload.lines() == null || payload.lines().isEmpty() || payload.lines().stream()
            .anyMatch(line -> line == null || line.sku() == null || line.sku().isBlank()
                || line.quantity() < 1)) {
            throw new IllegalArgumentException("StockReserved requires positive quantities and SKUs");
        }
        results.reserved(event.eventId(), payload.orderId());
    }

    private void failed(ConsumerRecord<String, String> record) {
        EventEnvelope<StockReservationFailedPayload> event = json.readValue(record.value(),
            new TypeReference<>() {});
        StockReservationFailedPayload payload = event.payload();
        validateIdentity(record, event, payload.orderId());
        if (payload.reason() == null || payload.reason().isBlank() || payload.reason().trim().length() > 500
            || payload.unavailableSkus() == null || payload.unavailableSkus().isEmpty()
            || payload.unavailableSkus().stream().anyMatch(sku -> sku == null || sku.isBlank())) {
            throw new IllegalArgumentException("StockReservationFailed requires a reason and unavailable SKUs");
        }
        results.failed(event.eventId(), payload.orderId(), payload.reason());
    }

    private static void validateIdentity(ConsumerRecord<String, String> record, EventEnvelope<?> event,
                                         UUID orderId) {
        if (event.eventVersion() != 1 || orderId == null
            || !orderId.toString().equals(event.aggregateId())
            || !event.aggregateId().equals(record.key())) {
            throw new IllegalArgumentException("Unsupported stock event version or mismatched order identity");
        }
    }
}
