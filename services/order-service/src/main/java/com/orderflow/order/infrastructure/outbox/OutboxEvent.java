package com.orderflow.order.infrastructure.outbox;

import java.util.Objects;
import java.util.UUID;

import com.orderflow.order.infrastructure.events.EventEnvelope;

public record OutboxEvent(String aggregateType, UUID aggregateId, EventEnvelope<?> message) {

    public OutboxEvent {
        if (aggregateType == null || aggregateType.isBlank()) {
            throw new IllegalArgumentException("Aggregate type is required");
        }
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(message, "message");
        if (!aggregateId.toString().equals(message.aggregateId())) {
            throw new IllegalArgumentException("Outbox aggregate ID must match the event envelope");
        }
    }
}
