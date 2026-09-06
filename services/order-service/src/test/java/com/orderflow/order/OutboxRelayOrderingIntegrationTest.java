package com.orderflow.order;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.orderflow.order.application.OutboxRelay;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class})
class OutboxRelayOrderingIntegrationTest {

    private static final String TOPIC = "orders.order-events.v1";

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private KafkaContainer kafka;

    @Test
    void lockedEarlierEventPreventsLaterEventFromOvertakingIt() throws Exception {
        ensureTopic();
        UUID orderId = UUID.randomUUID();
        UUID placed = insert(orderId, "OrderPlaced", Instant.now().plusSeconds(1));
        UUID cancelled = insert(orderId, "OrderCancelled", Instant.now());
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var holdingTransaction = executor.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.queryForObject("select id from orders.outbox_event where id = ? for update", UUID.class, placed);
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release row lock");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(relay.publishBatch()).isZero();
                assertThat(jdbc.queryForObject("""
                    select count(*) from orders.outbox_event
                    where id in (?, ?) and published_at is not null
                    """, Integer.class, placed, cancelled)).isZero();
            } finally {
                release.countDown();
            }
            holdingTransaction.get(5, TimeUnit.SECONDS);
        }

        assertThat(relay.publishBatch()).isEqualTo(1);
        assertThat(relay.publishBatch()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            select count(*) from orders.outbox_event
            where id in (?, ?) and published_at is not null
            """, Integer.class, placed, cancelled)).isEqualTo(2);

        try (var consumer = new KafkaConsumer<String, String>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "ordering-test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(TOPIC));
            var received = new ArrayList<UUID>();
            Instant deadline = Instant.now().plusSeconds(10);
            while (received.size() < 2 && Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.key().equals(orderId.toString())) {
                        var id = record.value().contains(placed.toString()) ? placed : cancelled;
                        received.add(id);
                    }
                }
            }
            assertThat(received).containsExactly(placed, cancelled);
        }
    }

    private UUID insert(UUID orderId, String type, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into orders.outbox_event
                (id, aggregate_type, aggregate_id, event_type, payload, created_at)
            values (?, 'Order', ?, ?, ?::jsonb, ?)
            """, id, orderId, type,
            """
                {"eventId":"%s","eventType":"%s","eventVersion":1,
                 "occurredAt":"2026-09-30T10:00:00Z","aggregateId":"%s","payload":{}}
                """.formatted(id, type, orderId), Timestamp.from(createdAt));
        return id;
    }

    private void ensureTopic() throws Exception {
        try (var admin = AdminClient.create(Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            if (!admin.listTopics().names().get().contains(TOPIC)) {
                admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get();
            }
        }
    }
}
