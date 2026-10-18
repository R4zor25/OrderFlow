package com.orderflow.notification.retention;

import java.time.Clock;
import java.time.Instant;

import com.orderflow.notification.notifications.NotificationRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class NotificationRetention {

    private static final Logger log = LoggerFactory.getLogger(NotificationRetention.class);

    private final NotificationRepository repository;
    private final NotificationRetentionSettings settings;
    private final Clock clock;

    public NotificationRetention(NotificationRepository repository, NotificationRetentionSettings settings,
                                 Clock clock) {
        this.repository = repository;
        this.settings = settings;
        this.clock = clock;
    }

    public int deleteExpired() {
        Instant cutoff = clock.instant().minus(settings.period());
        int total = 0;
        int deleted;
        // Small batches keep each delete short, so it never holds locks the listener or the retry job need.
        do {
            deleted = repository.deleteClosedBefore(cutoff, settings.batchSize());
            total += deleted;
        } while (deleted == settings.batchSize());
        log.info("Deleted expired notifications count={}", total);
        return total;
    }
}
