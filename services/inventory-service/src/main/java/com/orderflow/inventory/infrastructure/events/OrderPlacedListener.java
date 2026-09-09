package com.orderflow.inventory.infrastructure.events;

import com.orderflow.inventory.application.OrderPlacedProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "inventory.messaging", name = "enabled", havingValue = "true")
public class OrderPlacedListener {

    private final ObjectMapper json;
    private final OrderPlacedProcessor processor;

    public OrderPlacedListener(ObjectMapper json, OrderPlacedProcessor processor) {
        this.json = json;
        this.processor = processor;
    }

    @KafkaListener(topics = "orders.order-events.v1")
    public void onMessage(ConsumerRecord<String, String> record) {
        String type = json.readTree(record.value()).path("eventType").asString();
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Event type is required");
        }
        switch (type) {
            case "OrderPlaced" -> { }
            case "OrderCancelled", "OrderConfirmed", "OrderRejected" -> { return; }
            default -> throw new IllegalArgumentException("Unsupported order event type: " + type);
        }
        EventEnvelope<OrderPlacedPayload> event = json.readValue(
            record.value(), new TypeReference<>() {});
        if (!event.aggregateId().equals(record.key())) {
            throw new IllegalArgumentException("Kafka key does not match OrderPlaced aggregate ID");
        }
        processor.process(event);
    }
}
