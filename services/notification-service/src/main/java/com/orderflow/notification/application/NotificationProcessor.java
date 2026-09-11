package com.orderflow.notification.application;

import com.orderflow.notification.messaging.NotificationEvent;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public final class NotificationProcessor {

    private static final Logger log = LoggerFactory.getLogger(NotificationProcessor.class);

    private final NotificationComposer composer;
    private final NotificationStore store;
    private final NotificationSender sender;

    public NotificationProcessor(NotificationComposer composer, NotificationStore store,
                                 NotificationSender sender) {
        this.composer = composer;
        this.store = store;
        this.sender = sender;
    }

    public void process(NotificationEvent event) {
        NotificationDraft draft = composer.compose(event);
        if (!store.insertIfAbsent(draft)) {
            log.info("Notification already exists for eventId={}", event.eventId());
            return;
        }
        try {
            sender.send(draft);
        } catch (RuntimeException failure) {
            log.error("Notification delivery failed after insertion for eventId={}, cause={}",
                    event.eventId(), failure.getClass().getSimpleName());
        }
    }
}
