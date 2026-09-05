package com.orderflow.order.infrastructure.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.orderflow.order.domain.CustomerType;

public record OrderPlacedEvent(
    UUID eventId,
    String eventType,
    int eventVersion,
    Instant occurredAt,
    UUID aggregateId,
    Payload payload
) {
    public record Payload(UUID orderId, UUID customerId, CustomerType customerType, List<Line> lines) {
    }

    public record Line(String sku, int quantity) {
    }
}
