package com.orderflow.inventory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.orderflow.inventory.infrastructure.events.EventEnvelope;
import com.orderflow.inventory.infrastructure.events.OrderPlacedPayload;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = "inventory.messaging.enabled=true")
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class})
class OrderCancelledKafkaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private KafkaContainer broker;

    @Autowired
    private ObjectMapper json;

    @Test
    void releasesStockAfterOrderPlacedThenOrderCancelledForSameOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID placedId = UUID.randomUUID();
        UUID cancelledId = UUID.randomUUID();
        String sku = "CANCEL-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
            insert into inventory.stock_item
                (sku, on_hand, reserved, low_stock_threshold, low_stock_notified, version, updated_at)
            values (?, 5, 0, 1, false, 0, now())
            """, sku);
        var placed = new EventEnvelope<>(placedId, "OrderPlaced", 1, Instant.now(),
            orderId.toString(), new OrderPlacedPayload(orderId, customerId, "B2B",
                List.of(new OrderPlacedPayload.Line(sku, 2))));
        var cancelled = new EventEnvelope<>(cancelledId, "OrderCancelled", 1, Instant.now(),
            orderId.toString(), Map.of("orderId", orderId, "customerId", customerId,
                "customerEmail", "standard@example.test", "previousStatus", "PENDING"));

        kafka.send("orders.order-events.v1", orderId.toString(), json.writeValueAsString(placed))
            .get(10, TimeUnit.SECONDS);
        kafka.send("orders.order-events.v1", orderId.toString(), json.writeValueAsString(cancelled))
            .get(10, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("""
                select reserved from inventory.stock_item where sku = ?
                """, Integer.class, sku)).isZero();
            assertThat(jdbc.queryForObject("""
                select status from inventory.reservation where order_id = ? and sku = ?
                """, String.class, orderId, sku)).isEqualTo("RELEASED");
            assertThat(jdbc.queryForObject("""
                select count(*) from inventory.processed_event where event_id in (?, ?)
                """, Integer.class, placedId, cancelledId)).isEqualTo(2);
            assertThat(jdbc.queryForObject("""
                select count(*) from inventory.outbox_event
                 where aggregate_id = ? and event_type = 'StockReleased'
                """, Integer.class, orderId)).isEqualTo(1);
        });

        ConsumerRecord<String, String> released = receiveReleased(orderId);
        assertThat(released.key()).isEqualTo(orderId.toString());
        var message = json.readTree(released.value());
        assertThat(message.get("eventType").asText()).isEqualTo("StockReleased");
        assertThat(message.get("payload").get("orderId").asText()).isEqualTo(orderId.toString());
    }

    private ConsumerRecord<String, String> receiveReleased(UUID orderId) {
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "stock-release-test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of("inventory.stock-events.v1"));
            Instant deadline = Instant.now().plusSeconds(10);
            while (Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (orderId.toString().equals(record.key())
                        && "StockReleased".equals(json.readTree(record.value()).path("eventType").asString())) {
                        return record;
                    }
                }
            }
            throw new AssertionError("StockReleased was not published for order " + orderId);
        }
    }
}
