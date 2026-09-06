package com.orderflow.order;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.orderflow.order.application.OutboxRelay;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class OutboxRelayFailureIntegrationTest {

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private KafkaTemplate<String, String> kafka;

    @Test
    void failedBrokerAcknowledgementLeavesEventUnpublishedForRetry() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        jdbc.update("""
            insert into orders.outbox_event
                (id, aggregate_type, aggregate_id, event_type, payload, created_at)
            values (?, 'Order', ?, 'OrderPlaced', ?::jsonb, ?)
            """, eventId, orderId,
            """
                {"eventId":"%s","eventType":"OrderPlaced","eventVersion":1,
                 "occurredAt":"2026-09-30T10:00:00Z","aggregateId":"%s","payload":{}}
                """.formatted(eventId, orderId), Timestamp.from(Instant.now()));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")))
            .thenReturn(CompletableFuture.completedFuture(null));

        assertThatThrownBy(relay::publishBatch)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(eventId.toString());
        assertThat(jdbc.queryForObject("""
            select published_at from orders.outbox_event where id = ?
            """, Timestamp.class, eventId)).isNull();

        assertThat(relay.publishBatch()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            select published_at from orders.outbox_event where id = ?
            """, Timestamp.class, eventId)).isNotNull();
    }
}
