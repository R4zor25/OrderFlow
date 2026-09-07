package com.orderflow.inventory.reservation.infrastructure;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {

    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean reserveIfAvailable(String sku, int quantity) {
        return jdbc.update("""
            update inventory.stock_item
               set reserved = reserved + ?, version = version + 1, updated_at = now()
             where sku = ? and on_hand - reserved >= ?
            """, quantity, sku, quantity) == 1;
    }

    public void insertReservation(UUID orderId, String sku, int quantity) {
        jdbc.update("""
            insert into inventory.reservation
                (id, order_id, sku, quantity, status, created_at)
            values (?, ?, ?, ?, 'RESERVED', now())
            """, UUID.randomUUID(), orderId, sku, quantity);
    }
}
