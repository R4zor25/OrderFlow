package com.orderflow.notification;

import io.micronaut.data.connection.annotation.Connectable;
import io.micronaut.context.BeanContext;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import com.orderflow.notification.messaging.NotificationEventListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@MicronautTest(transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationSchemaIntegrationTest implements TestPropertyProvider {

    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-bookworm");

    static {
        postgres.start();
    }

    @Inject
    DataSource dataSource;

    @Inject
    EmbeddedServer server;

    @Inject
    BeanContext beanContext;

    @Test
    void listenerIsDisabledWithoutPersistenceStep() {
        assertThat(beanContext.findBean(NotificationEventListener.class)).isEmpty();
    }

    @Override
    public Map<String, String> getProperties() {
        return Map.of(
                "datasources.default.url", postgres.getJdbcUrl(),
                "datasources.default.username", postgres.getUsername(),
                "datasources.default.password", postgres.getPassword(),
                "datasources.default.driver-class-name", "org.postgresql.Driver"
        );
    }

    @Test
    @Connectable
    void flywayCreatesNotificationTable() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     select count(*) from information_schema.tables
                     where table_schema = 'notifications' and table_name = 'notification'
                     """);
             var rows = statement.executeQuery()) {
            rows.next();
            assertThat(rows.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    @Connectable
    void orderIdIsOptionalForNotificationsWithoutAnOrder() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     select is_nullable from information_schema.columns
                     where table_schema = 'notifications'
                       and table_name = 'notification'
                       and column_name = 'order_id'
                     """);
             var rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("YES");
        }
    }

    @Test
    @Connectable
    void orderLookupHasAnIndex() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     select count(*) from pg_indexes
                     where schemaname = 'notifications'
                       and tablename = 'notification'
                       and indexname = 'idx_notification_order_created_at'
                     """);
             var rows = statement.executeQuery()) {
            rows.next();
            assertThat(rows.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void duplicateEventIdCannotCreateAnotherNotification() throws SQLException {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try {
                var eventId = UUID.randomUUID();
                insertNotification(connection, UUID.randomUUID(), eventId);
                assertThatThrownBy(() -> insertNotification(connection, UUID.randomUUID(), eventId))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("duplicate key");
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    @Connectable
    void healthReportsUpWithPostgresAvailable() throws Exception {
        URI healthUri = server.getURI().resolve("/health");
        var request = HttpRequest.newBuilder(healthUri).GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).containsPattern("\"status\"\\s*:\\s*\"UP\"");

        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("select 1");
             var rows = statement.executeQuery()) {
            rows.next();
            assertThat(rows.getInt(1)).isEqualTo(1);
        }
    }

    private static void insertNotification(java.sql.Connection connection, UUID id, UUID eventId)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into notifications.notification
                    (id, event_id, type, recipient, subject, body, channel, created_at)
                values (?, ?, 'OrderConfirmed', 'buyer@example.test', 'Subject', 'Body', 'LOG', now())
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, eventId);
            statement.executeUpdate();
        }
    }
}
