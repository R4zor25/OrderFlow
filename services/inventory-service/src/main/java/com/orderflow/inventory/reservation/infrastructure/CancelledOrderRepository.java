package com.orderflow.inventory.reservation.infrastructure;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CancelledOrderRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CancelledOrderRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void remember(UUID orderId) {
        jdbc.update("""
            insert into inventory.cancelled_order (order_id, cancelled_at)
            values (?, ?)
            on conflict (order_id) do nothing
            """, orderId, Timestamp.from(clock.instant()));
    }

    public boolean isCancelled(UUID orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists (select 1 from inventory.cancelled_order where order_id = ?)
            """, Boolean.class, orderId));
    }
}
