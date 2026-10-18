package com.orderflow.order.messaging;

import java.util.Set;
import java.util.UUID;

import com.orderflow.order.events.EventEnvelope;
import com.orderflow.order.events.StockReservationFailedPayload;
import com.orderflow.order.events.StockReservedPayload;
import com.orderflow.order.orders.application.StockResultService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "order.messaging", name = "enabled", havingValue = "true")
public class StockResultListener {

    private static final Logger log = LoggerFactory.getLogger(StockResultListener.class);
    private static final Set<String> KNOWN_TYPES =
        Set.of("StockReserved", "StockReservationFailed", "StockReleased", "LowStock");
    private static final int MAX_REASON_LENGTH = 500;

    private final ObjectMapper json;
    private final StockResultService results;

    public StockResultListener(ObjectMapper json, StockResultService results) {
        this.json = json;
        this.results = results;
    }

    @KafkaListener(topics = "inventory.stock-events.v1")
    public void onMessage(ConsumerRecord<String, String> record) {
        var tree = json.readTree(record.value());
        String type = tree.path("eventType").asString();
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Event type is required");
        }
        int version = tree.path("eventVersion").asInt(0);
        if (version > 1 || !KNOWN_TYPES.contains(type)) {
            // Tolerant reader: a new type within v1 or a dual-published newer version is not an error.
            log.warn("Skipping unsupported stock event eventId={} type={} version={} topic={} partition={} offset={}",
                tree.path("eventId").asString(), type, version, record.topic(), record.partition(), record.offset());
            return;
        }
        switch (type) {
            case "StockReserved" -> reserved(record);
            case "StockReservationFailed" -> failed(record);
            default -> { }
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
        if (payload.reason() == null || payload.reason().isBlank() || payload.unavailableSkus() == null
            || payload.unavailableSkus().stream().anyMatch(sku -> sku == null || sku.isBlank())) {
            throw new IllegalArgumentException("StockReservationFailed requires a reason and valid SKUs");
        }
        String reason = payload.reason().trim();
        // A long reason must not leave the order pending; the stored reason is capped at 500 characters.
        results.failed(event.eventId(), payload.orderId(),
            reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH) : reason);
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
