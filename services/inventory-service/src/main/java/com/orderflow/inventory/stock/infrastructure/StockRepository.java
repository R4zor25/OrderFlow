package com.orderflow.inventory.stock.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import com.orderflow.inventory.stock.domain.StockLevel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class StockRepository {

    private static final RowMapper<StockLevel> LEVEL = (rs, rowNum) ->
        new StockLevel(rs.getString("sku"), rs.getInt("on_hand"), rs.getInt("reserved"));

    private final JdbcTemplate jdbc;

    public StockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StockLevel> find(String sku) {
        return jdbc.query("select sku, on_hand, reserved from inventory.stock_item where sku = ?", LEVEL, sku)
            .stream().findFirst();
    }

    public Optional<StockLevel> setOnHand(String sku, int onHand, Instant now) {
        // Evaluating reserved in the same statement keeps a concurrent reservation from slipping above the new on_hand.
        return jdbc.query("""
            update inventory.stock_item
               set on_hand = ?,
                   low_stock_notified = case when ? - reserved > low_stock_threshold then false
                                             else low_stock_notified end,
                   version = version + 1,
                   updated_at = ?
             where sku = ? and reserved <= ?
            returning sku, on_hand, reserved
            """, LEVEL, onHand, onHand, Timestamp.from(now), sku, onHand).stream().findFirst();
    }
}
