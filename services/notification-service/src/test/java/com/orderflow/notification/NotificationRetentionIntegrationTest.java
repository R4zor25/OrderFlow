package com.orderflow.notification;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.orderflow.notification.application.NotificationRetention;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@MicronautTest(transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationRetentionIntegrationTest implements TestPropertyProvider {

    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-bookworm");

    static {
        postgres.start();
    }

    @Inject
    NotificationRetention retention;


    @Override
    public Map<String, String> getProperties() {
        return Map.of(
                "datasources.default.url", postgres.getJdbcUrl(),
                "datasources.default.username", postgres.getUsername(),
                "datasources.default.password", postgres.getPassword(),
                "datasources.default.driver-class-name", "org.postgresql.Driver",
                "notification.channel", "LOG",
                "notification.retention.period", "30d",
                "notification.retention.batch-size", "2"
        );
    }

    @BeforeEach
    void emptyTable() throws Exception {
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.execute("DELETE FROM notifications.notification");
        }
    }

    @Test
    void deletesOnlyClosedNotificationsOlderThanTheRetentionPeriod() throws Exception {
        UUID oldSent = insert("SENT", Duration.ofDays(40));
        UUID oldFailed = insert("FAILED", Duration.ofDays(40));
        UUID oldPending = insert("PENDING", Duration.ofDays(40));
        UUID recentSent = insert("SENT", Duration.ofDays(5));

        assertThat(retention.deleteExpired()).isEqualTo(2);

        assertThat(exists(oldSent)).isFalse();
        assertThat(exists(oldFailed)).isFalse();
        assertThat(exists(oldPending)).isTrue();
        assertThat(exists(recentSent)).isTrue();
    }

    @Test
    void deletesInBatchesUntilNothingIsLeft() throws Exception {
        for (int index = 0; index < 5; index++) {
            insert("SENT", Duration.ofDays(31 + index));
        }

        assertThat(retention.deleteExpired()).isEqualTo(5);
        assertThat(count()).isZero();
    }

    @Test
    void backlogCountsCanUseTheirPartialIndexes() throws Exception {
        try (var connection = connection(); var statement = connection.createStatement()) {
            // The table is empty, so the planner would rightly scan it; this asks only whether an index fits.
            statement.execute("SET enable_seqscan = off");
            assertThat(plan(statement, "SELECT count(*) FROM notifications.notification WHERE status = 'FAILED'"))
                .contains("idx_notification_failed");
            assertThat(plan(statement, "SELECT count(*) FROM notifications.notification WHERE status = 'PENDING'"))
                .contains("idx_notification_pending_due");
        }
    }

    private static String plan(Statement statement, String sql) throws SQLException {
        var plan = new StringBuilder();
        try (var rows = statement.executeQuery("EXPLAIN " + sql)) {
            while (rows.next()) {
                plan.append(rows.getString(1)).append('\n');
            }
        }
        return plan.toString();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private UUID insert(String status, Duration age) throws Exception {
        UUID id = UUID.randomUUID();
        try (var connection = connection(); var statement = connection.prepareStatement("""
                INSERT INTO notifications.notification
                    (id, event_id, order_id, type, recipient, subject, body, channel, created_at, status, attempts)
                VALUES (?, ?, NULL, 'OrderConfirmed', 'customer@example.test', 'Subject', 'Body', 'LOG', ?, ?, 1)
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, UUID.randomUUID());
            statement.setTimestamp(3, Timestamp.from(Instant.now().minus(age)));
            statement.setString(4, status);
            statement.executeUpdate();
        }
        return id;
    }

    private boolean exists(UUID id) throws Exception {
        try (var connection = connection(); var statement = connection.prepareStatement(
                "SELECT count(*) FROM notifications.notification WHERE id = ?")) {
            statement.setObject(1, id);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1) == 1;
            }
        }
    }

    private int count() throws Exception {
        try (var connection = connection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM notifications.notification")) {
            result.next();
            return result.getInt(1);
        }
    }
}
