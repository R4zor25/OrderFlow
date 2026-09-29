package com.orderflow.notification;

import com.orderflow.notification.application.NotificationBacklogMetrics;
import com.orderflow.notification.application.NotificationDelivery;
import com.orderflow.notification.application.NotificationDraft;
import com.orderflow.notification.application.NotificationStore;
import com.orderflow.notification.config.NotificationDeliverySettings;
import com.orderflow.notification.config.NotificationSettings.Channel;
import com.orderflow.notification.infrastructure.persistence.NotificationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@MicronautTest(transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationRetryJobIntegrationTest implements TestPropertyProvider {

    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-bookworm");

    static {
        postgres.start();
    }

    @Inject
    NotificationStore store;

    @Inject
    NotificationDelivery delivery;

    @Inject
    NotificationDeliverySettings settings;

    @Inject
    NotificationRepository repository;

    @Override
    public Map<String, String> getProperties() {
        return Map.of(
                "datasources.default.url", postgres.getJdbcUrl(),
                "datasources.default.username", postgres.getUsername(),
                "datasources.default.password", postgres.getPassword(),
                "notification.channel", "LOG");
    }

    @Test
    void resendsDueRowWhoseImmediateDeliveryNeverCompleted() throws Exception {
        var draft = pending();
        makeDue(draft.eventId(), 0);

        delivery.retryDue();

        assertThat(status(draft.eventId())).isEqualTo("SENT");
    }

    @Test
    void doesNotClaimRowsInsideTheirLease() throws Exception {
        var draft = pending();

        assertThat(store.claimDue()).noneMatch(claimed -> claimed.eventId().equals(draft.eventId()));
        assertThat(status(draft.eventId())).isEqualTo("PENDING");
    }

    @Test
    void claimedRowIsNotClaimedAgainDuringItsLease() throws Exception {
        var draft = pending();
        makeDue(draft.eventId(), 0);

        assertThat(store.claimDue()).anyMatch(claimed -> claimed.eventId().equals(draft.eventId()));
        assertThat(store.claimDue()).noneMatch(claimed -> claimed.eventId().equals(draft.eventId()));
    }

    @Test
    void skipsRowsLockedByAnotherClaim() throws Exception {
        var locked = pending();
        var free = pending();
        makeDue(locked.eventId(), 0);
        makeDue(free.eventId(), 0);
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement(
                    "select id from notifications.notification where event_id = ? for update")) {
                lock.setObject(1, locked.eventId());
                lock.executeQuery().close();
            }

            var claimed = store.claimDue();

            assertThat(claimed).noneMatch(draft -> draft.eventId().equals(locked.eventId()));
            assertThat(claimed).anyMatch(draft -> draft.eventId().equals(free.eventId()));
            connection.rollback();
        }
    }

    @Test
    void reschedulesRowAfterFailedRetry() throws Exception {
        var draft = pending();
        makeDue(draft.eventId(), 2);
        var registry = new SimpleMeterRegistry();
        var failing = new NotificationDelivery(store, ignored -> {
            throw new IllegalStateException("smtp down");
        }, registry);
        Instant before = Instant.now();

        failing.retryDue();

        try (var connection = connection();
             var query = connection.prepareStatement(
                     "select status, attempts, next_attempt_at from notifications.notification where event_id = ?")) {
            query.setObject(1, draft.eventId());
            try (var rows = query.executeQuery()) {
                rows.next();
                assertThat(rows.getString("status")).isEqualTo("PENDING");
                assertThat(rows.getInt("attempts")).isEqualTo(3);
                assertThat(rows.getTimestamp("next_attempt_at").toInstant())
                        .isAfter(before.plus(settings.backoffAfter(3)).minusSeconds(1));
            }
        }
        assertThat(outcome(registry, "retry")).isEqualTo(1.0);
        assertThat(outcome(registry, "failed")).isZero();
        assertThat(outcome(registry, "sent")).isZero();
    }

    @Test
    void marksRowFailedAfterMaxAttempts() throws Exception {
        var draft = pending();
        makeDue(draft.eventId(), settings.maxAttempts() - 1);
        var registry = new SimpleMeterRegistry();
        var failing = new NotificationDelivery(store, ignored -> {
            throw new IllegalStateException("smtp down");
        }, registry);

        failing.retryDue();

        assertThat(status(draft.eventId())).isEqualTo("FAILED");
        assertThat(store.claimDue()).noneMatch(claimed -> claimed.eventId().equals(draft.eventId()));
        assertThat(outcome(registry, "failed")).isEqualTo(1.0);
        assertThat(outcome(registry, "retry")).isZero();
    }

    @Test
    void countsASuccessfulDelivery() throws Exception {
        var draft = pending();
        makeDue(draft.eventId(), 1);
        var registry = new SimpleMeterRegistry();

        new NotificationDelivery(store, ignored -> { }, registry).retryDue();

        assertThat(status(draft.eventId())).isEqualTo("SENT");
        assertThat(outcome(registry, "sent")).isEqualTo(1.0);
    }

    @Test
    void doesNotCountAFailureOnAnAlreadySettledRow() {
        var draft = pending();
        store.markSent(draft.eventId());
        var registry = new SimpleMeterRegistry();

        new NotificationDelivery(store, ignored -> {
            throw new IllegalStateException("smtp down");
        }, registry).deliver(draft);

        assertThat(outcome(registry, "sent")).isZero();
        assertThat(outcome(registry, "retry")).isZero();
        assertThat(outcome(registry, "failed")).isZero();
    }

    @Test
    void backlogGaugesMatchTheRowsByStatus() throws Exception {
        pending();
        var registry = new SimpleMeterRegistry();
        new NotificationBacklogMetrics(repository).bindTo(registry);

        assertThat(registry.get("notifications.pending").gauge().value()).isEqualTo(countWithStatus("PENDING"));
        assertThat(registry.get("notifications.failed").gauge().value()).isEqualTo(countWithStatus("FAILED"));
    }

    private double countWithStatus(String status) throws Exception {
        try (var connection = connection();
             var query = connection.prepareStatement(
                     "select count(*) from notifications.notification where status = ?")) {
            query.setString(1, status);
            try (var rows = query.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static double outcome(SimpleMeterRegistry registry, String outcome) {
        return registry.get("notifications.delivery").tag("outcome", outcome).counter().count();
    }

    @Inject
    io.micronaut.context.ApplicationContext context;

    @Test
    void retryJobIsDisabledInTests() {
        assertThat(context.containsBean(com.orderflow.notification.infrastructure.delivery.NotificationRetryJob.class))
                .isFalse();
    }

    @Test
    void lateFailureDoesNotReopenASentRow() throws Exception {
        var draft = pending();
        store.markSent(draft.eventId());

        store.recordFailure(draft.eventId(), "IllegalStateException: smtp timeout");

        assertThat(status(draft.eventId())).isEqualTo("SENT");
    }

    @Test
    void lateFailureDoesNotReopenAFailedRow() throws Exception {
        var draft = pending();
        makeDue(draft.eventId(), settings.maxAttempts() - 1);
        store.recordFailure(draft.eventId(), "IllegalStateException: smtp down");

        store.recordFailure(draft.eventId(), "IllegalStateException: smtp down");

        assertThat(status(draft.eventId())).isEqualTo("FAILED");
    }

    private NotificationDraft pending() {
        var draft = new NotificationDraft(UUID.randomUUID(), UUID.randomUUID(), "OrderConfirmed",
                "buyer@example.test", "Confirmed", "Order confirmed", Channel.LOG);
        assertThat(store.insertPending(draft)).isTrue();
        return draft;
    }

    private void makeDue(UUID eventId, int attempts) throws Exception {
        try (var connection = connection();
             var update = connection.prepareStatement("""
                     update notifications.notification
                     set next_attempt_at = ?, attempts = ?
                     where event_id = ?
                     """)) {
            update.setTimestamp(1, java.sql.Timestamp.from(Instant.now().minusSeconds(60)));
            update.setInt(2, attempts);
            update.setObject(3, eventId);
            update.executeUpdate();
        }
    }

    private String status(UUID eventId) throws Exception {
        try (var connection = connection();
             var query = connection.prepareStatement(
                     "select status from notifications.notification where event_id = ?")) {
            query.setObject(1, eventId);
            try (var rows = query.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private java.sql.Connection connection() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
