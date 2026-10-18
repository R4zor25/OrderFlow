package com.orderflow.inventory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.orderflow.inventory.lowstock.LowStockScanService;
import com.orderflow.inventory.outbox.InventoryOutboxRelay;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class})
class LowStockKafkaIntegrationTest {

    @Autowired
    private LowStockScanService scan;

    @Autowired
    private InventoryOutboxRelay relay;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private KafkaContainer kafka;

    private String testSku;
    private String secondSku;

    @AfterEach
    void removeTestRows() {
        if (testSku != null) {
            jdbc.update("delete from inventory.outbox_event where aggregate_id = ?", testSku);
            jdbc.update("delete from inventory.stock_item where sku = ?", testSku);
        }
        if (secondSku != null) {
            jdbc.update("delete from inventory.outbox_event where aggregate_id = ?", secondSku);
            jdbc.update("delete from inventory.stock_item where sku = ?", secondSku);
        }
    }

    @Test
    void publishesLowStockWithSkuKeyAndCanonicalEnvelope() throws Exception {
        testSku = "LOW-K-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
            insert into inventory.stock_item
                (sku, on_hand, reserved, low_stock_threshold, low_stock_notified, version, updated_at)
            values (?, 3, 0, 5, false, 0, now())
            """, testSku);

        scan.scan();
        relay.publishBatch();

        ConsumerRecord<String, String> record = receiveEvent(testSku);
        var envelope = json.readTree(record.value());
        var canonical = json.readTree(canonicalLowStock());

        assertThat(record.key()).isEqualTo(testSku);
        assertThat(new String(record.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
            .isEqualTo("LowStock");
        assertThat(envelope.get("eventType").asText()).isEqualTo(canonical.get("eventType").asText());
        assertThat(envelope.get("eventVersion").asInt()).isEqualTo(canonical.get("eventVersion").asInt());
        assertThat(UUID.fromString(envelope.get("eventId").asText())).isNotNull();
        assertThat(Instant.parse(envelope.get("occurredAt").asText())).isNotNull();
        assertThat(envelope.get("aggregateId").asText()).isEqualTo(testSku);
        assertThat(envelope.get("payload").get("sku").asText()).isEqualTo(testSku);
        assertThat(envelope.get("payload").get("available").asInt()).isEqualTo(3);
        assertThat(envelope.get("payload").get("threshold").asInt()).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
            select published_at is not null from inventory.outbox_event
            where aggregate_type = 'StockItem' and aggregate_id = ? and event_type = 'LowStock'
            """, Boolean.class, testSku)).isTrue();
    }

    @Test
    void publishesEachLowSkuWithItsOwnKafkaKey() {
        testSku = "LOW-K-" + UUID.randomUUID().toString().substring(0, 8);
        secondSku = "LOW-K-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
            insert into inventory.stock_item
                (sku, on_hand, reserved, low_stock_threshold, low_stock_notified, version, updated_at)
            values (?, 3, 0, 5, false, 0, now()), (?, 4, 0, 5, false, 0, now())
            """, testSku, secondSku);

        scan.scan();
        relay.publishBatch();

        var first = receiveEvent(testSku);
        var second = receiveEvent(secondSku);
        assertThat(first.key()).isEqualTo(testSku);
        assertThat(second.key()).isEqualTo(secondSku);
        assertThat(json.readTree(first.value()).get("payload").get("sku").asText()).isEqualTo(testSku);
        assertThat(json.readTree(second.value()).get("payload").get("sku").asText()).isEqualTo(secondSku);
    }

    private ConsumerRecord<String, String> receiveEvent(String key) {
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "low-stock-test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of("inventory.stock-events.v1"));
            Instant deadline = Instant.now().plusSeconds(10);
            while (Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
            throw new AssertionError("No LowStock Kafka record for " + key);
        }
    }

    private String canonicalLowStock() throws Exception {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isDirectory(directory.resolve("contracts/events"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("Cannot find contracts/events from test working directory");
        }
        return Files.readString(directory.resolve("contracts/events/low-stock.json"));
    }
}
