package com.orderflow.inventory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.orderflow.inventory.application.OrderPlacedProcessor;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import com.orderflow.inventory.infrastructure.events.EventEnvelope;
import com.orderflow.inventory.infrastructure.events.OrderPlacedPayload;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
    "inventory.messaging.enabled=true",
    "spring.kafka.consumer.auto-offset-reset=earliest",
    "inventory.messaging.error.initial-interval=100ms",
    "inventory.messaging.error.max-interval=200ms"
})
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class})
class OrderPlacedKafkaIntegrationTest {

    private static final UUID ORDER_ID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID EVENT_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final String SKU = "CHAIR-001";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaTemplate<String, String> producer;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private ObjectMapper json;

    @MockitoSpyBean
    private OrderPlacedProcessor processor;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Test
    void consumesCanonicalOrderPlacedAndPublishesStockReserved() throws Exception {
        String placed = canonicalOrderPlaced();
        producer.send("orders.order-events.v1", ORDER_ID.toString(), placed)
            .get(10, TimeUnit.SECONDS);

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("""
                select reserved from inventory.stock_item where sku = ?
                """, Integer.class, SKU)).isEqualTo(2);
            assertThat(jdbc.queryForObject("""
                select count(*) from inventory.processed_event where event_id = ?
                """, Integer.class, EVENT_ID)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                select published_at is not null from inventory.outbox_event
                where aggregate_id = ? and event_type = 'StockReserved'
                """, Boolean.class, ORDER_ID.toString())).isTrue();
        });

        var found = receiveStockEvent(ORDER_ID);
        assertThat(found.key()).isEqualTo(ORDER_ID.toString());
        assertThat(new String(found.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
            .isEqualTo("StockReserved");
        var result = json.readTree(found.value());
        assertThat(result.get("eventType").asText()).isEqualTo("StockReserved");
        assertThat(result.get("aggregateId").asText()).isEqualTo(ORDER_ID.toString());
        assertThat(result.get("payload").get("lines").get(0).get("sku").asText()).isEqualTo(SKU);
        assertThat(result.get("payload").get("lines").get(0).get("quantity").asInt()).isEqualTo(2);
    }

    @Test
    void publishesFailureAfterRollingBackUnavailableStock() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var placed = new EventEnvelope<>(eventId, "OrderPlaced", 1,
            Instant.parse("2026-10-01T10:15:31Z"), orderId.toString(),
            new OrderPlacedPayload(orderId, UUID.randomUUID(), "B2B",
                List.of(new OrderPlacedPayload.Line("CHAIR-003", 6))));
        producer.send("orders.order-events.v1", orderId.toString(), json.writeValueAsString(placed))
            .get(10, TimeUnit.SECONDS);

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("""
                select reserved from inventory.stock_item where sku = 'CHAIR-003'
                """, Integer.class)).isZero();
            assertThat(jdbc.queryForObject("""
                select count(*) from inventory.reservation where order_id = ?
                """, Integer.class, orderId)).isZero();
            assertThat(jdbc.queryForObject("""
                select count(*) from inventory.processed_event where event_id = ?
                """, Integer.class, eventId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                select published_at is not null from inventory.outbox_event
                where aggregate_id = ? and event_type = 'StockReservationFailed'
                """, Boolean.class, orderId.toString())).isTrue();
        });

        var found = receiveStockEvent(orderId);
        assertThat(found.key()).isEqualTo(orderId.toString());
        assertThat(new String(found.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
            .isEqualTo("StockReservationFailed");
        var result = json.readTree(found.value());
        assertThat(result.get("eventType").asText()).isEqualTo("StockReservationFailed");
        assertThat(result.get("payload").get("reason").asText()).isEqualTo("Insufficient stock");
        assertThat(result.get("payload").get("unavailableSkus").get(0).asText()).isEqualTo("CHAIR-003");
    }

    @Test
    void routesMalformedJsonToDltAndContinuesWithNextOrder() throws Exception {
        double deadLettersBefore = deadLetters();
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String key = orderId.toString();
        String malformed = "{\"eventType\":";
        var placed = new EventEnvelope<>(eventId, "OrderPlaced", 1,
            Instant.parse("2026-10-01T10:15:31Z"), key,
            new OrderPlacedPayload(orderId, UUID.randomUUID(), "B2B",
                List.of(new OrderPlacedPayload.Line("CHAIR-002", 1))));

        producer.send("orders.order-events.v1", key, malformed).get(10, TimeUnit.SECONDS);
        producer.send("orders.order-events.v1", key, json.writeValueAsString(placed))
            .get(10, TimeUnit.SECONDS);

        var deadLetter = receiveEvent("orders.order-events.v1.inventory-service.DLT", key);
        assertThat(deadLetter.value()).isEqualTo(malformed);
        assertThat(new String(deadLetter.headers().lastHeader("kafka_dlt-original-topic").value(),
            StandardCharsets.UTF_8)).isEqualTo("orders.order-events.v1");
        assertThat(new String(deadLetter.headers().lastHeader("kafka_dlt-original-consumer-group").value(),
            StandardCharsets.UTF_8)).isEqualTo("inventory-service");
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(deadLetters()).isEqualTo(deadLettersBefore + 1));

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("""
                select count(*) from inventory.reservation where order_id = ?
                """, Integer.class, orderId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                select count(*) from inventory.processed_event where event_id = ?
                """, Integer.class, eventId)).isEqualTo(1);
        });
    }

    @Test
    void keepsRetryingTransientFailuresInsteadOfDeadLettering() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var placed = new EventEnvelope<>(eventId, "OrderPlaced", 1,
            Instant.parse("2026-10-01T10:15:31Z"), orderId.toString(),
            new OrderPlacedPayload(orderId, UUID.randomUUID(), "B2B",
                List.of(new OrderPlacedPayload.Line("CHAIR-004", 1))));
        doThrow(new TransientDataAccessResourceException("temporary database failure"))
            .doThrow(new TransientDataAccessResourceException("temporary database failure"))
            .doThrow(new TransientDataAccessResourceException("temporary database failure"))
            .doThrow(new TransientDataAccessResourceException("temporary database failure"))
            .doCallRealMethod().when(processor).process(any());

        producer.send("orders.order-events.v1", orderId.toString(), json.writeValueAsString(placed))
            .get(10, TimeUnit.SECONDS);

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("""
                select count(*) from inventory.processed_event where event_id = ?
                """, Integer.class, eventId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                select count(*) from inventory.reservation where order_id = ?
                """, Integer.class, orderId)).isEqualTo(1);
        });
        verify(processor, times(5)).process(argThat(event -> event.eventId().equals(eventId)));
    }

    @Test
    void answersInvalidButIdentifiableOrderWithReservationFailure() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var placed = new EventEnvelope<>(eventId, "OrderPlaced", 1,
            Instant.parse("2026-10-01T10:15:31Z"), orderId.toString(),
            new OrderPlacedPayload(orderId, UUID.randomUUID(), "B2B",
                List.of(new OrderPlacedPayload.Line("CHAIR-005", 0))));

        producer.send("orders.order-events.v1", orderId.toString(), json.writeValueAsString(placed))
            .get(10, TimeUnit.SECONDS);

        var result = receiveStockEvent(orderId);
        var envelope = json.readTree(result.value());
        assertThat(envelope.get("eventType").asString()).isEqualTo("StockReservationFailed");
        assertThat(envelope.get("payload").get("reason").asString()).isEqualTo("Invalid order");
        assertThat(envelope.get("payload").get("unavailableSkus").get(0).asString()).isEqualTo("CHAIR-005");
        verify(processor, times(1)).process(argThat(event -> event.eventId().equals(eventId)));
        assertThat(jdbc.queryForObject("""
            select count(*) from inventory.reservation where order_id = ?
            """, Integer.class, orderId)).isZero();
    }

    @Test
    void sendsUnidentifiableOrderToDltWithoutRetrying() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var placed = new EventEnvelope<>(eventId, "OrderPlaced", 1,
            Instant.parse("2026-10-01T10:15:31Z"), UUID.randomUUID().toString(),
            new OrderPlacedPayload(orderId, UUID.randomUUID(), "B2B",
                List.of(new OrderPlacedPayload.Line("CHAIR-005", 1))));
        String value = json.writeValueAsString(placed);

        producer.send("orders.order-events.v1", orderId.toString(), value)
            .get(10, TimeUnit.SECONDS);

        var deadLetter = receiveEvent("orders.order-events.v1.inventory-service.DLT", orderId.toString());
        assertThat(deadLetter.value()).isEqualTo(value);
        verify(processor, never()).process(any());
        assertThat(jdbc.queryForObject("""
            select count(*) from inventory.processed_event where event_id = ?
            """, Integer.class, eventId)).isZero();
    }

    @Test
    void consumesEachOrderEventPartitionOnItsOwnThread() {
        var containers = listeners.getListenerContainers().stream()
            .filter(container -> java.util.Arrays.asList(container.getContainerProperties().getTopics())
                .contains("orders.order-events.v1"))
            .toList();

        assertThat(containers).singleElement()
            .isInstanceOfSatisfying(ConcurrentMessageListenerContainer.class,
                container -> assertThat(container.getConcurrency()).isEqualTo(3));
    }

    private double deadLetters() {
        var counter = registry.find("messaging.dead.letters")
            .tag("topic", "orders.order-events.v1.inventory-service.DLT").counter();
        return counter == null ? 0.0 : counter.count();
    }

    private ConsumerRecord<String, String> receiveStockEvent(UUID orderId) {
        return receiveEvent("inventory.stock-events.v1", orderId.toString());
    }

    private ConsumerRecord<String, String> receiveEvent(String topic, String key) {
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "inventory-result-test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            ConsumerRecord<String, String> found = null;
            Instant deadline = Instant.now().plusSeconds(10);
            while (found == null && Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        found = record;
                        break;
                    }
                }
            }

            assertThat(found).isNotNull();
            return found;
        }
    }

    private String canonicalOrderPlaced() throws Exception {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isDirectory(directory.resolve("contracts/events"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("Cannot find contracts/events from test working directory");
        }
        return Files.readString(directory.resolve("contracts/events/order-placed.json"));
    }
}
