package com.orderflow.notification.retention;

import java.time.Duration;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("notification.retention")
public record NotificationRetentionSettings(boolean enabled, Duration period, int batchSize) {

    public NotificationRetentionSettings {
        // A zero or negative period would make every closed notification, and its deduplication, expire at once.
        if (period == null || period.isNegative() || period.isZero() || batchSize < 1) {
            throw new IllegalArgumentException("Invalid notification.retention settings");
        }
    }
}
