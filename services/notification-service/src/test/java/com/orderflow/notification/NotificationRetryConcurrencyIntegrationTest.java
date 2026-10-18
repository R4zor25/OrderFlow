package com.orderflow.notification;

import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.orderflow.notification.delivery.NotificationDelivery;
import com.orderflow.notification.delivery.NotificationDeliverySettings;
import com.orderflow.notification.delivery.NotificationSender;
import com.orderflow.notification.notifications.NotificationDraft;
import com.orderflow.notification.notifications.NotificationRepository;
import com.orderflow.notification.notifications.NotificationSettings.Channel;
import com.orderflow.notification.notifications.NotificationStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@MicronautTest(transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationRetryConcurrencyIntegrationTest implements TestPropertyProvider {

    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-bookworm");

    static {
        postgres.start();
    }

    @Inject
    NotificationRepository repository;

    @Inject
    NotificationDeliverySettings settings;

    @Override
    public Map<String, String> getProperties() {
        return Map.of(
                "datasources.default.url", postgres.getJdbcUrl(),
                "datasources.default.username", postgres.getUsername(),
                "datasources.default.password", postgres.getPassword(),
                "notification.channel", "LOG");
    }

    @Test
    void aSecondWorkerDoesNotResendWhileTheFirstIsStillWorkingThroughItsRun() throws Exception {
        var clock = new ManualClock(Instant.now());
        var store = new NotificationStore(repository, settings, clock);
        var first = pending(store);
        var second = pending(store);
        makeDue(first.eventId());
        makeDue(second.eventId());
        Map<UUID, Integer> sends = new ConcurrentHashMap<>();
        BlockingQueue<UUID> started = new LinkedBlockingQueue<>();
        var proceed = new Semaphore(0);
        // Each send of worker A takes 0.6 of the lease, so A's run outlasts one lease but no single send does.
        NotificationSender slow = draft -> {
            sends.merge(draft.eventId(), 1, Integer::sum);
            started.add(draft.eventId());
            try {
                if (!proceed.tryAcquire(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("send was never released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        };
        NotificationSender fast = draft -> sends.merge(draft.eventId(), 1, Integer::sum);
        var workerA = new NotificationDelivery(store, settings, slow, new SimpleMeterRegistry());
        var workerB = new NotificationDelivery(store, settings, fast, new SimpleMeterRegistry());
        var lease = settings.lease();

        var runA = CompletableFuture.runAsync(workerA::retryDue);
        assertThat(started.poll(10, TimeUnit.SECONDS)).isNotNull();
        clock.advance(lease.multipliedBy(6).dividedBy(10));
        proceed.release();
        assertThat(started.poll(10, TimeUnit.SECONDS)).isNotNull();
        clock.advance(lease.multipliedBy(6).dividedBy(10));
        workerB.retryDue();
        proceed.release();
        runA.get(10, TimeUnit.SECONDS);

        assertThat(sends).containsEntry(first.eventId(), 1).containsEntry(second.eventId(), 1);
    }

    private static NotificationDraft pending(NotificationStore store) {
        var draft = new NotificationDraft(UUID.randomUUID(), UUID.randomUUID(), "OrderConfirmed",
                "buyer@example.test", "Confirmed", "Order confirmed", Channel.LOG);
        assertThat(store.insertPending(draft)).isTrue();
        return draft;
    }

    private static void makeDue(UUID eventId) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
             var update = connection.prepareStatement(
                     "update notifications.notification set next_attempt_at = ? where event_id = ?")) {
            update.setTimestamp(1, Timestamp.from(Instant.now().minusSeconds(60)));
            update.setObject(2, eventId);
            update.executeUpdate();
        }
    }

    private static final class ManualClock extends Clock {

        private final AtomicReference<Instant> now;

        ManualClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration amount) {
            now.updateAndGet(current -> current.plus(amount));
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
