package com.orderflow.notification.delivery;

import com.orderflow.notification.messaging.NotificationEvent;
import com.orderflow.notification.notifications.NotificationDraft;
import com.orderflow.notification.notifications.NotificationStore;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public final class NotificationProcessor {

    private static final Logger log = LoggerFactory.getLogger(NotificationProcessor.class);

    private final NotificationComposer composer;
    private final NotificationStore store;
    private final NotificationDelivery delivery;

    public NotificationProcessor(NotificationComposer composer, NotificationStore store,
                                 NotificationDelivery delivery) {
        this.composer = composer;
        this.store = store;
        this.delivery = delivery;
    }

    public void process(NotificationEvent event) {
        NotificationDraft draft = composer.compose(event);
        if (!store.insertPending(draft)) {
            log.info("Notification already exists for eventId={}", event.eventId());
            return;
        }
        delivery.deliver(draft);
    }
}
