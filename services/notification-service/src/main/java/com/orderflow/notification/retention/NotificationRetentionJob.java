package com.orderflow.notification.retention;

import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;

@Singleton
@Requires(property = "notification.retention.enabled", value = "true")
public class NotificationRetentionJob {

    private final NotificationRetention retention;

    public NotificationRetentionJob(NotificationRetention retention) {
        this.retention = retention;
    }

    // Deleting is idempotent, so concurrent runs on several instances need no lock.
    @Scheduled(cron = "${notification.retention.cron}", zoneId = "UTC")
    void deleteExpired() {
        retention.deleteExpired();
    }
}
