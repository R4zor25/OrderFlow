package com.orderflow.notification.infrastructure.persistence;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import io.micronaut.core.annotation.Nullable;

import java.time.Instant;
import java.util.UUID;

@MappedEntity(value = "notification", schema = "notifications")
public record NotificationRow(@Id UUID id, UUID eventId, @Nullable UUID orderId, String type,
                              String recipient, String subject, String body,
                              String channel, Instant createdAt) {
}
