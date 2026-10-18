package com.orderflow.order.messaging;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class ProcessedEventRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ProcessedEventRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public boolean markIfNew(UUID eventId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Processed event write requires an active transaction");
        }
        return jdbc.update("""
            insert into orders.processed_event (event_id, processed_at)
            values (?, ?)
            on conflict (event_id) do nothing
            """, eventId, Timestamp.from(clock.instant())) == 1;
    }
}
