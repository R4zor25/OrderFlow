package com.orderflow.inventory.infrastructure.events;

import java.util.Set;
import java.util.UUID;

import com.orderflow.inventory.application.OrderPlacedProcessor;
import com.orderflow.inventory.application.OrderCancellationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "inventory.messaging", name = "enabled", havingValue = "true")
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);
    private static final Set<String> KNOWN_TYPES =
        Set.of("OrderPlaced", "OrderCancelled", "OrderConfirmed", "OrderRejected");

    private final ObjectMapper json;
    private final OrderPlacedProcessor placed;
    private final OrderCancellationService cancellation;

    public OrderEventListener(ObjectMapper json, OrderPlacedProcessor placed,
                              OrderCancellationService cancellation) {
        this.json = json;
        this.placed = placed;
        this.cancellation = cancellation;
    }

    @KafkaListener(topics = "orders.order-events.v1")
    public void onMessage(ConsumerRecord<String, String> record) {
        var tree = json.readTree(record.value());
        String type = tree.path("eventType").asString();
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Event type is required");
        }
        int version = tree.path("eventVersion").asInt(0);
        if (version > 1 || !KNOWN_TYPES.contains(type)) {
            // Tolerant reader: a new type within v1 or a dual-published newer version is not an error.
            log.warn("Skipping unsupported order event eventId={} type={} version={} topic={} partition={} offset={}",
                tree.path("eventId").asString(), type, version, record.topic(), record.partition(), record.offset());
            return;
        }
        switch (type) {
            case "OrderPlaced" -> {
                EventEnvelope<OrderPlacedPayload> event = json.readValue(
                    record.value(), new TypeReference<>() {});
                validateIdentity(record, event, event.payload().orderId());
                log.info("Processing OrderPlaced eventId={} orderId={}", event.eventId(), event.aggregateId());
                placed.process(event);
            }
            case "OrderCancelled" -> {
                EventEnvelope<OrderCancelledPayload> event = json.readValue(
                    record.value(), new TypeReference<>() {});
                UUID orderId = event.payload().orderId();
                validateIdentity(record, event, orderId);
                log.info("Processing OrderCancelled eventId={} orderId={}", event.eventId(), orderId);
                cancellation.release(event.eventId(), orderId);
            }
            default -> { }
        }
    }

    private static void validateIdentity(ConsumerRecord<String, String> record, EventEnvelope<?> event,
                                         UUID orderId) {
        if (orderId == null || !orderId.toString().equals(event.aggregateId())
            || !event.aggregateId().equals(record.key())) {
            throw new IllegalArgumentException("Order ID, aggregate ID and Kafka key must match");
        }
    }
}
