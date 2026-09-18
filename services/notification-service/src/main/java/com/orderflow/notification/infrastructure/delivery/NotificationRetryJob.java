package com.orderflow.notification.infrastructure.delivery;

import com.orderflow.notification.application.NotificationDelivery;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;

@Singleton
@Requires(property = "notification.delivery.retry-enabled", value = "true", defaultValue = "true")
public class NotificationRetryJob {

    private final NotificationDelivery delivery;

    public NotificationRetryJob(NotificationDelivery delivery) {
        this.delivery = delivery;
    }

    @Scheduled(fixedDelay = "${notification.delivery.retry-interval:30s}",
            initialDelay = "${notification.delivery.retry-interval:30s}")
    void retryDue() {
        delivery.retryDue();
    }
}
