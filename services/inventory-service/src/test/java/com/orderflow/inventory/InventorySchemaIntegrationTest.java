package com.orderflow.inventory;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class InventorySchemaIntegrationTest {

    private final JdbcTemplate jdbc;

    @Autowired
    InventorySchemaIntegrationTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Test
    void createsTheFiveInventoryTables() {
        Integer count = jdbc.queryForObject("""
            select count(*) from information_schema.tables
            where table_schema = 'inventory'
              and table_name in ('stock_item', 'reservation', 'outbox_event',
                                 'processed_event', 'shedlock')
            """, Integer.class);

        assertThat(count).isEqualTo(5);
    }

    @Test
    @Transactional
    void rejectsNegativeOnHand() {
        insertStock("TEST-NEG-HAND");

        assertThatThrownBy(() -> jdbc.update(
            "update inventory.stock_item set on_hand = -1 where sku = 'TEST-NEG-HAND'"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsNegativeReserved() {
        insertStock("TEST-NEG-RES");

        assertThatThrownBy(() -> jdbc.update(
            "update inventory.stock_item set reserved = -1 where sku = 'TEST-NEG-RES'"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsReservationsAboveOnHand() {
        insertStock("TEST-OVER-RES");

        assertThatThrownBy(() -> jdbc.update(
            "update inventory.stock_item set reserved = 11 where sku = 'TEST-OVER-RES'"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsNegativeLowStockThreshold() {
        insertStock("TEST-NEG-THRESH");

        assertThatThrownBy(() -> jdbc.update(
            "update inventory.stock_item set low_stock_threshold = -1 where sku = 'TEST-NEG-THRESH'"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsNegativeVersion() {
        insertStock("TEST-NEG-VERSION");

        assertThatThrownBy(() -> jdbc.update(
            "update inventory.stock_item set version = -1 where sku = 'TEST-NEG-VERSION'"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsReservationForUnknownSku() {
        assertThatThrownBy(() -> insertReservation(UUID.randomUUID(), "MISSING-001", 1, "RESERVED"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsDuplicateReservationForSameOrderAndSku() {
        insertStock("TEST-DUPLICATE");
        UUID orderId = UUID.randomUUID();
        insertReservation(orderId, "TEST-DUPLICATE", 1, "RESERVED");

        assertThatThrownBy(() -> insertReservation(orderId, "TEST-DUPLICATE", 2, "RESERVED"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsNonPositiveReservationQuantity() {
        insertStock("TEST-ZERO-QTY");

        assertThatThrownBy(() -> insertReservation(UUID.randomUUID(), "TEST-ZERO-QTY", 0, "RESERVED"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsUnknownReservationStatus() {
        insertStock("TEST-STATUS");

        assertThatThrownBy(() -> insertReservation(UUID.randomUUID(), "TEST-STATUS", 1, "CANCELLED"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertStock(String sku) {
        jdbc.update("""
            insert into inventory.stock_item
                (sku, on_hand, reserved, low_stock_threshold,
                 low_stock_notified, version, updated_at)
            values (?, 10, 0, 5, false, 0, now())
            """, sku);
    }

    private void insertReservation(UUID orderId, String sku, int quantity, String status) {
        jdbc.update("""
            insert into inventory.reservation
                (id, order_id, sku, quantity, status, created_at)
            values (?, ?, ?, ?, ?, now())
            """, UUID.randomUUID(), orderId, sku, quantity, status);
    }
}
