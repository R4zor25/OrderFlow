package com.orderflow.notification.application;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class NotificationDelivery {

    private static final Logger log = LoggerFactory.getLogger(NotificationDelivery.class);

    private final NotificationStore store;
    private final NotificationSender sender;

    public NotificationDelivery(NotificationStore store, NotificationSender sender) {
        this.store = store;
        this.sender = sender;
    }

    public void deliver(NotificationDraft draft) {
        try {
            sender.send(draft);
        } catch (RuntimeException failure) {
            var recorded = store.recordFailure(draft.eventId(),
                    failure.getClass().getSimpleName() + ": " + failure.getMessage());
            if (recorded.isEmpty()) {
                log.info("Notification delivery failure ignored for already settled eventId={}", draft.eventId());
                return;
            }
            var outcome = recorded.get();
            if (outcome.failed()) {
                log.error("Notification delivery failed permanently for eventId={}, attempts={}, cause={}",
                        draft.eventId(), outcome.attempts(), failure.getClass().getSimpleName());
            } else {
                log.warn("Notification delivery failed for eventId={}, attempts={}, cause={}; will retry",
                        draft.eventId(), outcome.attempts(), failure.getClass().getSimpleName());
            }
            return;
        }
        store.markSent(draft.eventId());
    }

    public int retryDue() {
        var due = store.claimDue();
        due.forEach(this::deliver);
        return due.size();
    }
}
