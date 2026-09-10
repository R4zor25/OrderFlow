package com.orderflow.notification.messaging;

import io.micronaut.serde.annotation.Serdeable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record NotificationEvent(UUID eventId, String eventType, Instant occurredAt,
                                String aggregateId, Payload payload) {

    public sealed interface Payload permits OrderConfirmed, OrderRejected, OrderCancelled, LowStock {
    }

    @Serdeable
    public record OrderConfirmed(UUID orderId, UUID customerId, String customerEmail,
                                 BigDecimal totalGross, String currency) implements Payload {
    }

    @Serdeable
    public record OrderRejected(UUID orderId, UUID customerId, String customerEmail,
                                String reason) implements Payload {
    }

    @Serdeable
    public record OrderCancelled(UUID orderId, UUID customerId, String customerEmail,
                                 String previousStatus) implements Payload {
    }

    @Serdeable
    public record LowStock(String sku, Integer available, Integer threshold) implements Payload {
    }
}
