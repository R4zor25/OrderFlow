package com.orderflow.inventory;

import java.sql.DriverManager;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class InventoryOutboxMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-bookworm");

    @Test
    void migrationKeepsExistingOrderIdAsText() throws Exception {
        migrateTo("2");
        UUID orderId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var insert = connection.prepareStatement("""
                 insert into inventory.outbox_event
                     (id, aggregate_type, aggregate_id, event_type, payload, created_at)
                 values (?, 'Order', ?, 'StockReserved', '{}'::jsonb, now())
                 """)) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, orderId);
            insert.executeUpdate();
        }

        migrateTo("3");

        try (var connection = DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var query = connection.prepareStatement("""
                 select event.aggregate_id::text, column_info.data_type
                 from inventory.outbox_event event
                 cross join information_schema.columns column_info
                 where column_info.table_schema = 'inventory'
                   and column_info.table_name = 'outbox_event'
                   and column_info.column_name = 'aggregate_id'
                 """)) {
            try (var result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo(orderId.toString());
                assertThat(result.getString(2)).isEqualTo("character varying");
                assertThat(result.next()).isFalse();
            }
        }
    }

    private void migrateTo(String version) {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas("inventory")
            .defaultSchema("inventory")
            .locations("classpath:db/migration/inventory")
            .target(MigrationVersion.fromVersion(version))
            .load()
            .migrate();
    }
}
