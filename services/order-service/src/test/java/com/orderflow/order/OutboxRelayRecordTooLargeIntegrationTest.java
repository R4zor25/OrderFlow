package com.orderflow.order;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.orderflow.order.outbox.OutboxRelay;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.kafka.producer.properties.max.request.size=4096")
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class})
class OutboxRelayRecordTooLargeIntegrationTest {

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void realBrokerRejectionOfOversizedRecordParksTheEvent() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        jdbc.update("""
            insert into orders.outbox_event
                (id, aggregate_type, aggregate_id, event_type, payload, created_at)
            values (?, 'Order', ?, 'OrderPlaced', ?::jsonb, ?)
            """, eventId, orderId, """
                {"eventId":"%s","eventType":"OrderPlaced","eventVersion":1,
                 "occurredAt":"2026-09-30T10:00:00Z","aggregateId":"%s","payload":{"padding":"%s"}}
                """.formatted(eventId, orderId, "x".repeat(10_000)), Timestamp.from(Instant.now()));

        relay.publishBatch();

        var row = jdbc.queryForMap("select parked_at, published_at, last_error from orders.outbox_event where id = ?", eventId);
        assertThat(row.get("parked_at")).isNotNull();
        assertThat(row.get("published_at")).isNull();
        assertThat((String) row.get("last_error")).startsWith("RecordTooLargeException");
    }
}
