package com.orderflow.notification.notifications;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import jakarta.inject.Singleton;

@Singleton
public class NotificationBacklogMetrics implements MeterBinder {

    private final NotificationRepository repository;

    public NotificationBacklogMetrics(NotificationRepository repository) {
        this.repository = repository;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("notifications.pending", repository, notifications -> notifications.countPending())
                .description("Notifications waiting for a delivery attempt").register(registry);
        Gauge.builder("notifications.failed", repository, notifications -> notifications.countFailed())
                .description("Notifications that exhausted their delivery attempts").register(registry);
    }
}
