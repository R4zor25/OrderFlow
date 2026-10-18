package com.orderflow.inventory.lowstock;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LowStockRepository {

    private final JdbcTemplate jdbc;

    public LowStockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void resetRecovered() {
        jdbc.update("""
            update inventory.stock_item
            set low_stock_notified = false
            where low_stock_notified = true
              and on_hand - reserved > low_stock_threshold
            """);
    }

    public List<LowStockCandidate> claimLowStock() {
        return jdbc.query("""
            update inventory.stock_item
            set low_stock_notified = true
            where low_stock_notified = false
              and on_hand - reserved <= low_stock_threshold
            returning sku, on_hand - reserved as available, low_stock_threshold
            """, (rs, rowNum) -> new LowStockCandidate(
            rs.getString("sku"), rs.getInt("available"), rs.getInt("low_stock_threshold")));
    }

    public record LowStockCandidate(String sku, int available, int threshold) {
    }
}
