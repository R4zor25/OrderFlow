package com.orderflow.order;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.orderflow.order.application.CancelOrderService;
import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderStatus;
import com.orderflow.order.infrastructure.CustomerRepository;
import com.orderflow.order.infrastructure.OrderRepository;
import com.orderflow.order.infrastructure.events.EventEnvelope;
import com.orderflow.order.infrastructure.events.StockReservationFailedPayload;
import com.orderflow.order.infrastructure.events.StockReservedPayload;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = "order.messaging.enabled=true")
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class})
class StockResultKafkaIntegrationTest {

    private static final String TOPIC = "inventory.stock-events.v1";
    private static final UUID CUSTOMER_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private KafkaContainer broker;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private CancelOrderService cancellation;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void consumesStockReservedAndWritesConfirmation() throws Exception {
        UUID orderId = createOrder();
        UUID eventId = UUID.randomUUID();
        var event = new EventEnvelope<>(eventId, "StockReserved", 1, Instant.now(),
            orderId.toString(), new StockReservedPayload(orderId,
                List.of(new StockReservedPayload.Line("CHAIR-001", 1))));

        send(orderId.toString(), json.writeValueAsString(event));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(processedCount(eventId)).isEqualTo(1);
            assertThat(outboxCount(orderId, "OrderConfirmed")).isEqualTo(1);
        });
    }

    @Test
    void consumesStockReservationFailedAndWritesRejection() throws Exception {
        UUID orderId = createOrder();
        UUID eventId = UUID.randomUUID();
        var event = new EventEnvelope<>(eventId, "StockReservationFailed", 1, Instant.now(),
            orderId.toString(), new StockReservationFailedPayload(orderId,
                "Insufficient stock", List.of("CHAIR-001")));

        send(orderId.toString(), json.writeValueAsString(event));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var order = orders.findById(orderId).orElseThrow();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
            assertThat(order.getRejectionReason()).isEqualTo("Insufficient stock");
            assertThat(processedCount(eventId)).isEqualTo(1);
            assertThat(outboxCount(orderId, "OrderRejected")).isEqualTo(1);
        });
    }

    @Test
    void acknowledgesLateResultForCancelledOrderWithoutConfirmingIt() throws Exception {
        UUID orderId = createOrder();
        cancellation.cancel(orderId);
        UUID eventId = UUID.randomUUID();
        var event = new EventEnvelope<>(eventId, "StockReserved", 1, Instant.now(),
            orderId.toString(), new StockReservedPayload(orderId,
                List.of(new StockReservedPayload.Line("CHAIR-001", 1))));

        send(orderId.toString(), json.writeValueAsString(event));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(processedCount(eventId)).isEqualTo(1));
        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(outboxCount(orderId, "OrderConfirmed")).isZero();
    }

    @Test
    void sendsMalformedRecordToDltAndContinuesWithLaterRecord() throws Exception {
        UUID orderId = createOrder();
        String poison = "{malformed-" + UUID.randomUUID();
        UUID validEventId = UUID.randomUUID();
        var valid = new EventEnvelope<>(validEventId, "StockReserved", 1, Instant.now(),
            orderId.toString(), new StockReservedPayload(orderId,
                List.of(new StockReservedPayload.Line("CHAIR-001", 1))));

        send(orderId.toString(), poison);
        send(orderId.toString(), json.writeValueAsString(valid));

        assertThat(readDlt(poison, Duration.ofSeconds(15))).isTrue();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(processedCount(validEventId)).isEqualTo(1);
        });
    }

    private void send(String key, String value) throws Exception {
        kafka.send(TOPIC, key, value).get();
    }

    private boolean readDlt(String payload, Duration timeout) {
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "order-dlt-test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(TOPIC + ".DLT"));
            Instant deadline = Instant.now().plus(timeout);
            while (Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (payload.equals(record.value())) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private UUID createOrder() {
        return transactions.execute(status -> {
            var customer = customers.findById(CUSTOMER_ID).orElseThrow();
            var order = Order.place(customer,
                List.of(new Order.PricedLine("CHAIR-001", 1, new BigDecimal("84.90"))),
                new BigDecimal("22.92"), Instant.now());
            return orders.saveAndFlush(order).getId();
        });
    }

    private int processedCount(UUID eventId) {
        return jdbc.queryForObject("select count(*) from orders.processed_event where event_id = ?",
            Integer.class, eventId);
    }

    private int outboxCount(UUID orderId, String type) {
        return jdbc.queryForObject("""
            select count(*) from orders.outbox_event where aggregate_id = ? and event_type = ?
            """, Integer.class, orderId, type);
    }
}
