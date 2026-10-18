package com.orderflow.order.retention;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class RetentionCleaner {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleaner.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final RetentionProperties properties;

    public RetentionCleaner(JdbcTemplate jdbc, Clock clock, RetentionProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
    }

    public void clean() {
        Instant now = clock.instant();
        // Parked rows have no published_at, so an operator still finds them.
        deleteInBatches("orders.outbox_event", "id", "published_at", now.minus(properties.outbox()));
        deleteInBatches("orders.processed_event", "event_id", "processed_at", now.minus(properties.processedEvents()));
    }

    private void deleteInBatches(String table, String key, String timestamp, Instant cutoff) {
        // Small autocommitted batches keep row locks and WAL bursts short on a large backlog.
        String sql = "delete from " + table + " where " + key + " in (select " + key + " from " + table
            + " where " + timestamp + " < ? order by " + timestamp + " limit ?)";
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.update(sql, Timestamp.from(cutoff), properties.batchSize());
            total += deleted;
        } while (deleted == properties.batchSize());
        log.info("Retention removed table={} rows={}", table, total);
    }
}
