package com.orderflow.notification.messaging;

import io.micronaut.json.JsonMapper;
import io.micronaut.json.tree.JsonNode;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Singleton
public final class NotificationEventDecoder {

    private static final int MAX_EMAIL_LENGTH = 320;
    private static final int MAX_SKU_LENGTH = 32;

    public static final String ORDER_TOPIC = "orders.order-events.v1";
    public static final String STOCK_TOPIC = "inventory.stock-events.v1";

    private final JsonMapper jsonMapper;

    public NotificationEventDecoder(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public Optional<NotificationEvent> decode(String topic, String key, String json) {
        try {
            RawEnvelope envelope = jsonMapper.readValue(json, RawEnvelope.class);
            if (envelope == null || blank(envelope.eventType())) {
                throw new InvalidNotificationEventException("Missing eventType");
            }
            Class<? extends NotificationEvent.Payload> payloadType = switch (envelope.eventType()) {
                case "OrderConfirmed" -> NotificationEvent.OrderConfirmed.class;
                case "OrderRejected" -> NotificationEvent.OrderRejected.class;
                case "OrderCancelled" -> NotificationEvent.OrderCancelled.class;
                case "LowStock" -> NotificationEvent.LowStock.class;
                default -> null;
            };
            if (payloadType == null) {
                return Optional.empty();
            }
            String expectedTopic = payloadType == NotificationEvent.LowStock.class ? STOCK_TOPIC : ORDER_TOPIC;
            if (!expectedTopic.equals(topic) || envelope.eventId() == null || envelope.eventVersion() != 1
                    || envelope.occurredAt() == null || blank(envelope.aggregateId())
                    || !envelope.aggregateId().equals(key) || envelope.payload() == null) {
                throw new InvalidNotificationEventException("Invalid envelope for " + envelope.eventType());
            }
            NotificationEvent.Payload payload = jsonMapper.readValueFromTree(envelope.payload(), payloadType);
            validatePayload(envelope.aggregateId(), payload);
            return Optional.of(new NotificationEvent(envelope.eventId(), envelope.eventType(),
                    envelope.occurredAt(), envelope.aggregateId(), payload));
        } catch (IOException | IllegalArgumentException exception) {
            throw new InvalidNotificationEventException("Cannot decode notification event", exception);
        }
    }

    private static void validatePayload(String aggregateId, NotificationEvent.Payload payload) {
        if (payload instanceof NotificationEvent.OrderConfirmed event) {
            orderFields(aggregateId, event.orderId(), event.customerId(), event.customerEmail());
            if (event.totalGross() == null || blank(event.currency())) {
                throw new InvalidNotificationEventException("Missing OrderConfirmed fields");
            }
        } else if (payload instanceof NotificationEvent.OrderRejected event) {
            orderFields(aggregateId, event.orderId(), event.customerId(), event.customerEmail());
            if (blank(event.reason())) {
                throw new InvalidNotificationEventException("Missing OrderRejected reason");
            }
        } else if (payload instanceof NotificationEvent.OrderCancelled event) {
            orderFields(aggregateId, event.orderId(), event.customerId(), event.customerEmail());
            if (blank(event.previousStatus())) {
                throw new InvalidNotificationEventException("Missing OrderCancelled previousStatus");
            }
        } else if (payload instanceof NotificationEvent.LowStock event) {
            if (blank(event.sku()) || event.sku().length() > MAX_SKU_LENGTH
                    || !aggregateId.equals(event.sku()) || event.available() == null || event.threshold() == null
                    || event.available() < 0 || event.threshold() < 0) {
                throw new InvalidNotificationEventException("Invalid LowStock payload");
            }
        }
    }

    private static void orderFields(String aggregateId, UUID orderId, UUID customerId, String email) {
        if (orderId == null || !aggregateId.equals(orderId.toString()) || customerId == null || blank(email)
                || email.length() > MAX_EMAIL_LENGTH) {
            throw new InvalidNotificationEventException("Invalid order payload");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @Serdeable
    record RawEnvelope(UUID eventId, String eventType, int eventVersion, Instant occurredAt,
                       String aggregateId, JsonNode payload) {
    }
}
