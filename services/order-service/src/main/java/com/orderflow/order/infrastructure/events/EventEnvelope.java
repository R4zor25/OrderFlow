package com.orderflow.order.infrastructure.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope<T>(
    UUID eventId,
    String eventType,
    int eventVersion,
    Instant occurredAt,
    String aggregateId,
    T payload
) {
    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(payload, "payload");
        if (eventType == null || eventType.isBlank() || aggregateId == null || aggregateId.isBlank()
            || eventVersion < 1) {
            throw new IllegalArgumentException("Event type, positive version and aggregate ID are required");
        }
    }
}
