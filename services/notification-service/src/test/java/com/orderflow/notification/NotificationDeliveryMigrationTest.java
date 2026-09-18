package com.orderflow.notification;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationDeliveryMigrationTest {

    @Test
    void existingRowsBecomeSent() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17.11-bookworm")) {
            postgres.start();
            var eventId = UUID.randomUUID();
            flyway(postgres, "1").migrate();
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
                 var insert = connection.prepareStatement("""
                         insert into notifications.notification
                             (id, event_id, type, recipient, subject, body, channel, created_at)
                         values (?, ?, 'LowStock', 'ops@orderflow.test', 'Low stock', 'Low stock', 'LOG', now())
                         """)) {
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, eventId);
                insert.executeUpdate();
            }

            flyway(postgres, "latest").migrate();

            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
                 var query = connection.prepareStatement(
                         "select status, attempts from notifications.notification where event_id = ?")) {
                query.setObject(1, eventId);
                try (var rows = query.executeQuery()) {
                    rows.next();
                    assertThat(rows.getString("status")).isEqualTo("SENT");
                    assertThat(rows.getInt("attempts")).isZero();
                }
            }
        }
    }

    private static Flyway flyway(PostgreSQLContainer postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("notifications")
                .defaultSchema("notifications")
                .locations("classpath:db/migration/notifications")
                .target(target)
                .load();
    }
}
