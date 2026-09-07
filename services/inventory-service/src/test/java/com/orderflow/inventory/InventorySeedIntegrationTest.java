package com.orderflow.inventory;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class InventorySeedIntegrationTest {

    private final JdbcTemplate jdbc;
    private final Flyway flyway;

    @Autowired
    InventorySeedIntegrationTest(JdbcTemplate jdbc, Flyway flyway) {
        this.jdbc = jdbc;
        this.flyway = flyway;
    }

    @Test
    void seedsEveryCatalogSkuWithTwoLowStockItems() {
        assertThat(jdbc.queryForList(
            "select sku from inventory.stock_item order by sku", String.class
        )).containsExactly(
            "CHAIR-001", "CHAIR-002", "CHAIR-003", "CHAIR-004", "CHAIR-005",
            "DESK-001", "DESK-002", "DESK-003",
            "KEYBOARD-001",
            "LAMP-001", "LAMP-002", "LAMP-003", "LAMP-004", "LAMP-005", "LAMP-006",
            "MONITOR-001", "MOUSE-001", "NOTEBOOK-001", "PEN-001", "STAND-001"
        );
        assertThat(jdbc.queryForList("""
            select sku from inventory.stock_item
            where on_hand between 5 and 10 order by sku
            """, String.class)).containsExactly("CHAIR-003", "LAMP-006");
        assertThat(jdbc.queryForObject("""
            select count(*) from inventory.stock_item
            where reserved = 0 and low_stock_threshold = 10
              and low_stock_notified = false and version = 0
            """, Integer.class)).isEqualTo(20);
    }

    @Test
    @Transactional
    void rerunningFlywayDoesNotResetChangedStock() {
        assertThat(jdbc.queryForObject("""
            select count(*) from inventory.flyway_schema_history
            where version = '2' and success = true
            """, Integer.class)).isEqualTo(1);

        jdbc.update("update inventory.stock_item set on_hand = 42 where sku = 'CHAIR-001'");
        flyway.migrate();

        assertThat(jdbc.queryForObject(
            "select on_hand from inventory.stock_item where sku = 'CHAIR-001'", Integer.class
        )).isEqualTo(42);
    }
}
