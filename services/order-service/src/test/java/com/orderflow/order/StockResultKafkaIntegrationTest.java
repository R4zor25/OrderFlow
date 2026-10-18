package com.orderflow.order;

import com.orderflow.order.customer.CustomerRepository;
import com.orderflow.order.events.EventEnvelope;
import com.orderflow.order.events.StockReservationFailedPayload;
import com.orderflow.order.events.StockReservedPayload;
import com.orderflow.order.orders.application.CancelOrderService;
import com.orderflow.order.orders.application.OrderAccess;
import com.orderflow.order.orders.domain.Order;
import com.orderflow.order.orders.domain.OrderStatus;
import com.orderflow.order.orders.infrastructure.OrderRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
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
    private Tracer tracer;

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

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Test
    void consumesStockReservedAndWritesConfirmation() throws Exception {
        UUID orderId = createOrder();
        UUID eventId = UUID.randomUUID();
        var event = new EventEnvelope<>(eventId, "StockReserved", 1, Instant.now(),
            orderId.toString(), new StockReservedPayload(orderId,
                List.of(new StockReservedPayload.Line("CHAIR-001", 1))));

        var span = tracer.nextSpan().name("test.stock-result").start();
        String traceId = span.context().traceId();
        try (var scope = tracer.withSpan(span)) {
            send(orderId.toString(), json.writeValueAsString(event));
        } finally {
            span.end();
        }

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(processedCount(eventId)).isEqualTo(1);
            assertThat(outboxCount(orderId, "OrderConfirmed")).isEqualTo(1);
            assertResultTrace(orderId, "OrderConfirmed", traceId);
        });
    }

    @Test
    void consumesStockReservationFailedAndWritesRejection() throws Exception {
        UUID orderId = createOrder();
        UUID eventId = UUID.randomUUID();
        var event = new EventEnvelope<>(eventId, "StockReservationFailed", 1, Instant.now(),
            orderId.toString(), new StockReservationFailedPayload(orderId,
                "Insufficient stock", List.of("CHAIR-001")));

        var span = tracer.nextSpan().name("test.stock-result").start();
        String traceId = span.context().traceId();
        try (var scope = tracer.withSpan(span)) {
            send(orderId.toString(), json.writeValueAsString(event));
        } finally {
            span.end();
        }

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var order = orders.findById(orderId).orElseThrow();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
            assertThat(order.getRejectionReason()).isEqualTo("Insufficient stock");
            assertThat(processedCount(eventId)).isEqualTo(1);
            assertThat(outboxCount(orderId, "OrderRejected")).isEqualTo(1);
            assertResultTrace(orderId, "OrderRejected", traceId);
        });
    }

    @Test
    void acknowledgesLateResultForCancelledOrderWithoutConfirmingIt() throws Exception {
        UUID orderId = createOrder();
        cancellation.cancel(orderId, OrderAccess.admin());
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
        double deadLettersBefore = deadLetters();
        UUID orderId = createOrder();
        String poison = "{malformed-" + UUID.randomUUID();
        UUID validEventId = UUID.randomUUID();
        var valid = new EventEnvelope<>(validEventId, "StockReserved", 1, Instant.now(),
            orderId.toString(), new StockReservedPayload(orderId,
                List.of(new StockReservedPayload.Line("CHAIR-001", 1))));

        send(orderId.toString(), poison);
        send(orderId.toString(), json.writeValueAsString(valid));

        var deadLetter = readDlt(poison, Duration.ofSeconds(15));
        assertThat(deadLetter).isPresent();
        assertThat(new String(deadLetter.orElseThrow().headers()
            .lastHeader("kafka_dlt-original-consumer-group").value(), StandardCharsets.UTF_8))
            .isEqualTo("order-service");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(deadLetters()).isEqualTo(deadLettersBefore + 1));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(processedCount(validEventId)).isEqualTo(1);
        });
    }

    private void assertResultTrace(UUID orderId, String type, String traceId) {
        String headers = jdbc.queryForObject(
            "select headers::text from orders.outbox_event where aggregate_id = ? and event_type = ?",
            String.class, orderId, type);
        assertThat(json.readTree(headers).path("traceparent").asString()).startsWith("00-" + traceId + "-");
    }

    @Test
    void truncatesOverlongRejectionReasonInsteadOfDeadLettering() throws Exception {
        UUID orderId = createOrder();
        var event = new EventEnvelope<>(UUID.randomUUID(), "StockReservationFailed", 1, Instant.now(),
            orderId.toString(), new StockReservationFailedPayload(orderId, "x".repeat(600), List.of("CHAIR-001")));

        send(orderId.toString(), json.writeValueAsString(event));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var order = orders.findById(orderId).orElseThrow();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
            assertThat(order.getRejectionReason()).isEqualTo("x".repeat(500));
        });
    }

    @Test
    void rejectsInvalidOrderResultWithoutUnavailableSkus() throws Exception {
        UUID orderId = createOrder();
        var event = new EventEnvelope<>(UUID.randomUUID(), "StockReservationFailed", 1, Instant.now(),
            orderId.toString(), new StockReservationFailedPayload(orderId, "Invalid order", List.of()));

        send(orderId.toString(), json.writeValueAsString(event));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var order = orders.findById(orderId).orElseThrow();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
            assertThat(order.getRejectionReason()).isEqualTo("Invalid order");
        });
    }

    @Test
    void consumesEachStockEventPartitionOnItsOwnThread() {
        var containers = listeners.getListenerContainers().stream()
            .filter(container -> Arrays.asList(container.getContainerProperties().getTopics()).contains(TOPIC))
            .toList();

        assertThat(containers).singleElement()
            .isInstanceOfSatisfying(ConcurrentMessageListenerContainer.class,
                container -> assertThat(container.getConcurrency()).isEqualTo(3));
    }

    private double deadLetters() {
        var counter = registry.find("messaging.dead.letters")
            .tag("topic", TOPIC + ".order-service.DLT").counter();
        return counter == null ? 0.0 : counter.count();
    }

    private void send(String key, String value) throws Exception {
        kafka.send(TOPIC, key, value).get();
    }

    private Optional<ConsumerRecord<String, String>> readDlt(String payload, Duration timeout) {
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "order-dlt-test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(TOPIC + ".order-service.DLT"));
            Instant deadline = Instant.now().plus(timeout);
            while (Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (payload.equals(record.value())) {
                        return Optional.of(record);
                    }
                }
            }
            return Optional.empty();
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
