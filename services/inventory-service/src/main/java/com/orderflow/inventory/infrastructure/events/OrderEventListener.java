package com.orderflow.inventory.infrastructure.events;

import com.orderflow.inventory.application.OrderPlacedProcessor;
import com.orderflow.inventory.application.OrderCancelledProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "inventory.messaging", name = "enabled", havingValue = "true")
public class OrderEventListener {

    private final ObjectMapper json;
    private final OrderPlacedProcessor placed;
    private final OrderCancelledProcessor cancelled;

    public OrderEventListener(ObjectMapper json, OrderPlacedProcessor placed,
                              OrderCancelledProcessor cancelled) {
        this.json = json;
        this.placed = placed;
        this.cancelled = cancelled;
    }

    @KafkaListener(topics = "orders.order-events.v1")
    public void onMessage(ConsumerRecord<String, String> record) {
        String type = json.readTree(record.value()).path("eventType").asString();
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Event type is required");
        }
        switch (type) {
            case "OrderPlaced" -> {
                EventEnvelope<OrderPlacedPayload> event = json.readValue(
                    record.value(), new TypeReference<>() {});
                validateKey(record, event);
                placed.process(event);
            }
            case "OrderCancelled" -> {
                EventEnvelope<OrderCancelledPayload> event = json.readValue(
                    record.value(), new TypeReference<>() {});
                validateKey(record, event);
                cancelled.process(event);
            }
            case "OrderConfirmed", "OrderRejected" -> { }
            default -> throw new IllegalArgumentException("Unsupported order event type: " + type);
        }
    }

    private static void validateKey(ConsumerRecord<String, String> record, EventEnvelope<?> event) {
        if (!event.aggregateId().equals(record.key())) {
            throw new IllegalArgumentException("Kafka key does not match order aggregate ID");
        }
    }
}
