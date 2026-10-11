package com.orderflow.notification.application;

import com.orderflow.notification.config.NotificationDeliverySettings;
import com.orderflow.notification.config.NotificationSettings.Channel;
import com.orderflow.notification.infrastructure.persistence.NotificationRepository;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Singleton
public class NotificationStore {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final NotificationRepository repository;
    private final NotificationDeliverySettings settings;
    private final Clock clock;

    public NotificationStore(NotificationRepository repository, NotificationDeliverySettings settings, Clock clock) {
        this.repository = repository;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional
    public boolean insertPending(NotificationDraft draft) {
        Instant now = clock.instant();
        // The lease keeps the retry job away while the listener is still delivering this row.
        int inserted = repository.insertPending(UUID.randomUUID(), draft.eventId(), draft.orderId(),
                draft.type(), draft.recipient(), draft.subject(), draft.body(),
                draft.channel().name(), now, now.plus(settings.lease()));
        return inserted == 1;
    }

    @Transactional
    public void markSent(UUID eventId) {
        repository.markSent(eventId, clock.instant());
    }

    @Transactional
    public Optional<DeliveryFailure> recordFailure(UUID eventId, String error) {
        var pendingAttempts = repository.findPendingAttemptsForUpdate(eventId);
        if (pendingAttempts.isEmpty()) {
            // Another sender already settled the row; SENT and FAILED are final.
            return Optional.empty();
        }
        int attempts = pendingAttempts.get() + 1;
        boolean failed = attempts >= settings.maxAttempts();
        Instant next = failed ? null : clock.instant().plus(settings.backoffAfter(attempts));
        String lastError = error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
        repository.recordFailure(eventId, attempts, lastError, failed ? "FAILED" : "PENDING", next);
        return Optional.of(new DeliveryFailure(attempts, failed));
    }

    // One row per claim: the lease then covers a single send, not a whole run of slow SMTP conversations.
    @Transactional
    public Optional<NotificationDraft> claimNext() {
        Instant now = clock.instant();
        var due = repository.findDueForUpdate(now, 1);
        if (due.isEmpty()) {
            return Optional.empty();
        }
        var row = due.getFirst();
        repository.extendLease(List.of(row.id()), now.plus(settings.lease()));
        return Optional.of(new NotificationDraft(row.eventId(), row.orderId(), row.type(), row.recipient(),
                row.subject(), row.body(), Channel.valueOf(row.channel())));
    }

    public record DeliveryFailure(int attempts, boolean failed) {
    }
}
