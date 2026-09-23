package com.orderflow.notification.api;

import com.orderflow.notification.infrastructure.persistence.NotificationRow;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

@Serdeable
public record NotificationView(UUID id, UUID eventId, @Nullable UUID orderId, String type, String recipient,
                               String subject, String channel, String status, int attempts, Instant createdAt,
                               @Nullable Instant sentAt, @Nullable String lastError) {

    static NotificationView from(NotificationRow row) {
        return new NotificationView(row.id(), row.eventId(), row.orderId(), row.type(), row.recipient(),
                row.subject(), row.channel(), row.status(), row.attempts(), row.createdAt(), row.sentAt(),
                row.lastError());
    }
}
