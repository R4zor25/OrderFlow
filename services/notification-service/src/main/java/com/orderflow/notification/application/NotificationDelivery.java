package com.orderflow.notification.application;

import com.orderflow.notification.config.NotificationDeliverySettings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class NotificationDelivery {

    private static final Logger log = LoggerFactory.getLogger(NotificationDelivery.class);

    private final NotificationStore store;
    private final NotificationSender sender;
    private final Counter sent;
    private final Counter retry;
    private final Counter failed;
    private final int batchSize;

    public NotificationDelivery(NotificationStore store, NotificationDeliverySettings settings, NotificationSender sender,
                                MeterRegistry registry) {
        this.store = store;
        this.batchSize = settings.batchSize();
        this.sender = sender;
        this.sent = outcome(registry, "sent");
        this.retry = outcome(registry, "retry");
        this.failed = outcome(registry, "failed");
    }

    public void deliver(NotificationDraft draft) {
        try {
            sender.send(draft);
        } catch (RuntimeException failure) {
            handleFailure(draft, failure);
            return;
        }
        store.markSent(draft.eventId());
        sent.increment();
    }

    private void handleFailure(NotificationDraft draft, RuntimeException failure) {
        var recorded = store.recordFailure(draft.eventId(),
                failure.getClass().getSimpleName() + ": " + failure.getMessage());
        if (recorded.isEmpty()) {
            log.info("Notification delivery failure ignored for already settled eventId={}", draft.eventId());
            return;
        }
        var outcome = recorded.get();
        if (outcome.failed()) {
            failed.increment();
            log.error("Notification delivery failed permanently for eventId={}, attempts={}, cause={}",
                    draft.eventId(), outcome.attempts(), failure.getClass().getSimpleName());
        } else {
            retry.increment();
            log.warn("Notification delivery failed for eventId={}, attempts={}, cause={}; will retry",
                    draft.eventId(), outcome.attempts(), failure.getClass().getSimpleName());
        }
    }

    private static Counter outcome(MeterRegistry registry, String outcome) {
        return Counter.builder("notifications.delivery").tag("outcome", outcome)
                .description("Notification delivery attempts by outcome").register(registry);
    }

    public int retryDue() {
        int processed = 0;
        while (processed < batchSize) {
            var next = store.claimNext();
            if (next.isEmpty()) {
                break;
            }
            deliver(next.get());
            processed++;
        }
        return processed;
    }
}
