package com.orderflow.notification.application;

import com.orderflow.notification.config.NotificationSettings;
import com.orderflow.notification.messaging.NotificationEvent;
import jakarta.inject.Singleton;

@Singleton
public final class NotificationComposer {

    private final NotificationSettings settings;

    public NotificationComposer(NotificationSettings settings) {
        this.settings = settings;
    }

    public NotificationDraft compose(NotificationEvent event) {
        return switch (event.payload()) {
            case NotificationEvent.OrderConfirmed confirmed -> new NotificationDraft(
                    event.eventId(), confirmed.orderId(), event.eventType(), confirmed.customerEmail(),
                    "Order confirmed " + confirmed.orderId(),
                    "Your order " + confirmed.orderId() + " was confirmed. Total: "
                            + confirmed.totalGross().toPlainString() + " " + confirmed.currency() + ".",
                    settings.channel());
            case NotificationEvent.OrderRejected rejected -> new NotificationDraft(
                    event.eventId(), rejected.orderId(), event.eventType(), rejected.customerEmail(),
                    "Order rejected " + rejected.orderId(),
                    "Your order " + rejected.orderId() + " was rejected. Reason: " + rejected.reason() + ".",
                    settings.channel());
            case NotificationEvent.OrderCancelled cancelled -> new NotificationDraft(
                    event.eventId(), cancelled.orderId(), event.eventType(), cancelled.customerEmail(),
                    "Order cancelled " + cancelled.orderId(),
                    "Your order " + cancelled.orderId() + " was cancelled.",
                    settings.channel());
            case NotificationEvent.LowStock lowStock -> new NotificationDraft(
                    event.eventId(), null, event.eventType(), settings.opsEmail(),
                    "Low stock " + lowStock.sku(),
                    "SKU " + lowStock.sku() + " has " + lowStock.available()
                            + " units available; threshold is " + lowStock.threshold() + ".",
                    settings.channel());
        };
    }
}
