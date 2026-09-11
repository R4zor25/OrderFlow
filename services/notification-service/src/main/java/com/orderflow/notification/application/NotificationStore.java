package com.orderflow.notification.application;

import com.orderflow.notification.infrastructure.persistence.NotificationRepository;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import java.time.Clock;
import java.util.UUID;

@Singleton
public class NotificationStore {

    private final NotificationRepository repository;
    private final Clock clock;

    public NotificationStore(NotificationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public boolean insertIfAbsent(NotificationDraft draft) {
        int inserted = repository.insertIfAbsent(UUID.randomUUID(), draft.eventId(), draft.orderId(),
                draft.type(), draft.recipient(), draft.subject(), draft.body(),
                draft.channel().name(), clock.instant());
        return inserted == 1;
    }
}
