package com.orderflow.notification.notifications;

import com.orderflow.notification.notifications.NotificationSettings.Channel;

import io.micronaut.core.annotation.Nullable;

import java.util.UUID;

public record NotificationDraft(UUID eventId, @Nullable UUID orderId, String type, String recipient,
                                String subject, String body, Channel channel) {
}
