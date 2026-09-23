package com.orderflow.inventory.reservation.infrastructure;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.orderflow.inventory.reservation.domain.ReservationLine;
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

    public boolean release(UUID orderId) {
        var lines = jdbc.query("""
            update inventory.reservation
               set status = 'RELEASED'
             where order_id = ? and status = 'RESERVED'
            returning sku, quantity
            """, (rs, row) -> new ReservationLine(rs.getString("sku"), rs.getInt("quantity")), orderId);
        for (var line : lines.stream().sorted(Comparator.comparing(ReservationLine::sku)).toList()) {
            int updated = jdbc.update("""
                update inventory.stock_item
                   set reserved = reserved - ?, version = version + 1, updated_at = now()
                 where sku = ? and reserved >= ?
                """, line.quantity(), line.sku(), line.quantity());
            if (updated != 1) {
                throw new IllegalStateException("Cannot release stock for SKU " + line.sku());
            }
        }
        return !lines.isEmpty();
    }

    public List<ReservationView> findByOrderId(UUID orderId) {
        return jdbc.query("""
            select sku, quantity, status, created_at from inventory.reservation
             where order_id = ? order by sku
            """, (rs, rowNum) -> new ReservationView(rs.getString("sku"), rs.getInt("quantity"),
            rs.getString("status"), rs.getTimestamp("created_at").toInstant()), orderId);
    }

    public record ReservationView(String sku, int quantity, String status, Instant createdAt) {
    }
}
