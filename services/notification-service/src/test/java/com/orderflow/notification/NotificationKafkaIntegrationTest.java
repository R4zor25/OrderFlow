package com.orderflow.notification;

import com.orderflow.notification.messaging.NotificationEventDecoder;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@MicronautTest(transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationKafkaIntegrationTest implements TestPropertyProvider {

    private static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.3.1");
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-bookworm");

    static {
        kafka.start();
        postgres.start();
        try (var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic(NotificationEventDecoder.ORDER_TOPIC, 3, (short) 1),
                    new NewTopic(NotificationEventDecoder.STOCK_TOPIC, 3, (short) 1),
                    new NewTopic(NotificationEventDecoder.ORDER_TOPIC + ".DLT", 3, (short) 1),
                    new NewTopic(NotificationEventDecoder.STOCK_TOPIC + ".DLT", 3, (short) 1)
            )).all().get();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot create Kafka test topics", exception);
        }
    }

    @Override
    public Map<String, String> getProperties() {
        return Map.of(
                "kafka.bootstrap.servers", kafka.getBootstrapServers(),
                "kafka.health.enabled", "true",
                "notification.messaging.enabled", "true",
                "datasources.default.url", postgres.getJdbcUrl(),
                "datasources.default.username", postgres.getUsername(),
                "datasources.default.password", postgres.getPassword(),
                "datasources.default.driver-class-name", "org.postgresql.Driver"
        );
    }

    @Test
    void consumesBothTopicsSkipsUnknownTypeAndDeadLettersMalformedJson() throws Exception {
        var orderKey = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
        var confirmed = Files.readString(Path.of("../../contracts/events/order-confirmed.json"));
        var unknown = confirmed.replace("OrderConfirmed", "OrderShipped");
        var lowStock = Files.readString(Path.of("../../contracts/events/low-stock.json"));

        try (var producer = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(new ProducerRecord<>(NotificationEventDecoder.ORDER_TOPIC, orderKey, confirmed)).get();
            producer.send(new ProducerRecord<>(NotificationEventDecoder.ORDER_TOPIC, orderKey, unknown)).get();
            producer.send(new ProducerRecord<>(NotificationEventDecoder.ORDER_TOPIC, orderKey, "{broken-json")).get();
            producer.send(new ProducerRecord<>(NotificationEventDecoder.STOCK_TOPIC, "CHAIR-001", lowStock)).get();
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(committedRecords(NotificationEventDecoder.ORDER_TOPIC)).isEqualTo(3);
            assertThat(committedRecords(NotificationEventDecoder.STOCK_TOPIC)).isEqualTo(1);
        });

        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "notification-dlt-test-" + UUID.randomUUID(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"))) {
            var topic = NotificationEventDecoder.ORDER_TOPIC + ".DLT";
            consumer.subscribe(List.of(topic));
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                var records = consumer.poll(Duration.ofMillis(250));
                assertThat(records.count()).isEqualTo(1);
                var deadLetter = records.iterator().next();
                assertThat(deadLetter.key()).isEqualTo(orderKey);
                assertThat(deadLetter.value()).isEqualTo("{broken-json");
            });
        }
    }

    private long committedRecords(String topic) throws Exception {
        try (var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers()))) {
            return admin.listConsumerGroupOffsets("notification-service").partitionsToOffsetAndMetadata()
                    .get().entrySet().stream()
                    .filter(entry -> entry.getKey().topic().equals(topic))
                    .mapToLong(entry -> entry.getValue().offset())
                    .sum();
        }
    }
}
