package com.orderflow.notification;

import com.orderflow.notification.application.NotificationDelivery;
import com.orderflow.notification.application.NotificationDraft;
import com.orderflow.notification.application.NotificationStore;
import com.orderflow.notification.config.NotificationDeliverySettings;
import com.orderflow.notification.config.NotificationSettings.Channel;
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
        var failing = new NotificationDelivery(store, ignored -> {
            throw new IllegalStateException("smtp down");
        });
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
    }

    @Test
    void marksRowFailedAfterMaxAttempts() throws Exception {
        var draft = pending();
        makeDue(draft.eventId(), settings.maxAttempts() - 1);
        var failing = new NotificationDelivery(store, ignored -> {
            throw new IllegalStateException("smtp down");
        });

        failing.retryDue();

        assertThat(status(draft.eventId())).isEqualTo("FAILED");
        assertThat(store.claimDue()).noneMatch(claimed -> claimed.eventId().equals(draft.eventId()));
    }

    @Inject
    io.micronaut.context.ApplicationContext context;

    @Test
    void retryJobIsDisabledInTests() {
        assertThat(context.containsBean(com.orderflow.notification.infrastructure.delivery.NotificationRetryJob.class))
                .isFalse();
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
                     set next_attempt_at = now() - interval '1 second', attempts = ?
                     where event_id = ?
                     """)) {
            update.setInt(1, attempts);
            update.setObject(2, eventId);
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
