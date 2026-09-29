package com.orderflow.notification;

import com.orderflow.notification.messaging.InvalidNotificationEventException;
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
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.containers.GenericContainer;
import io.micronaut.json.JsonMapper;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@MicronautTest(transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationKafkaIntegrationTest implements TestPropertyProvider {

    private static final String ORDER_DEAD_LETTER_COUNT =
            "messaging_dead_letters_total\\{topic=\"orders\\.order-events\\.v1\\.notification-service\\.DLT\",?\\} 1\\.0";

    // Auto-create off, as in Compose and ECS, so the dead-letter topics must come from the service itself.
    private static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.3.1")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-bookworm");

    private static final GenericContainer<?> mailpit = new GenericContainer<>("axllent/mailpit:v1.31.2")
            .withExposedPorts(1025, 8025);

    @Inject
    JsonMapper jsonMapper;

    @Inject
    EmbeddedServer server;

    static {
        kafka.start();
        postgres.start();
        mailpit.start();
        try (var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic(NotificationEventDecoder.ORDER_TOPIC, 3, (short) 1),
                    new NewTopic(NotificationEventDecoder.STOCK_TOPIC, 3, (short) 1)
            )).all().get();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot create Kafka test topics", exception);
        }
    }

    @Override
    public Map<String, String> getProperties() {
        return Map.ofEntries(
                Map.entry("kafka.bootstrap.servers", kafka.getBootstrapServers()),
                Map.entry("kafka.health.enabled", "true"),
                Map.entry("notification.messaging.enabled", "true"),
                Map.entry("notification.channel", "SMTP"),
                Map.entry("notification.ops-email", "ops@orderflow.test"),
                Map.entry("javamail.enabled", "true"),
                Map.entry("javamail.properties.mail.smtp.host", mailpit.getHost()),
                Map.entry("javamail.properties.mail.smtp.port", mailpit.getMappedPort(1025).toString()),
                Map.entry("javamail.properties.mail.smtp.connectiontimeout", "5000"),
                Map.entry("javamail.properties.mail.smtp.timeout", "5000"),
                Map.entry("javamail.properties.mail.smtp.writetimeout", "5000"),
                Map.entry("datasources.default.url", postgres.getJdbcUrl()),
                Map.entry("datasources.default.username", postgres.getUsername()),
                Map.entry("datasources.default.password", postgres.getPassword()),
                Map.entry("datasources.default.driver-class-name", "org.postgresql.Driver")
        );
    }

    @Test
    void sendsOnceForDuplicateEventAndDeadLettersMalformedJson() throws Exception {
        var orderKey = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
        var confirmed = Files.readString(Path.of("../../contracts/events/order-confirmed.json"));
        var unknown = confirmed.replace("OrderConfirmed", "OrderShipped");
        var lowStock = Files.readString(Path.of("../../contracts/events/low-stock.json"));

        RecordMetadata broken;
        try (var producer = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(new ProducerRecord<>(NotificationEventDecoder.ORDER_TOPIC, orderKey, confirmed)).get();
            producer.send(new ProducerRecord<>(NotificationEventDecoder.ORDER_TOPIC, orderKey, confirmed)).get();
            producer.send(new ProducerRecord<>(NotificationEventDecoder.ORDER_TOPIC, orderKey, confirmed)).get();
            producer.send(new ProducerRecord<>(NotificationEventDecoder.ORDER_TOPIC, orderKey, unknown)).get();
            broken = producer.send(new ProducerRecord<>(NotificationEventDecoder.ORDER_TOPIC, orderKey, "{broken-json"))
                    .get();
            producer.send(new ProducerRecord<>(NotificationEventDecoder.STOCK_TOPIC, "CHAIR-001", lowStock)).get();
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(committedRecords(NotificationEventDecoder.ORDER_TOPIC)).isEqualTo(5);
            assertThat(committedRecords(NotificationEventDecoder.STOCK_TOPIC)).isEqualTo(1);
            assertThat(notificationCount("cccccccc-cccc-4ccc-8ccc-cccccccccccc")).isEqualTo(1);
            assertThat(notificationCount("15151515-1515-4515-8515-151515151515")).isEqualTo(1);
            var messages = mailpitMessages();
            assertThat(messages).hasSize(2);
            assertThat(messages.toString()).contains("ops@orderflow.test", "Low stock CHAIR-001", "Order confirmed");
        });

        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "notification-dlt-test-" + UUID.randomUUID(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"))) {
            var topic = NotificationEventDecoder.ORDER_TOPIC + ".notification-service.DLT";
            consumer.subscribe(List.of(topic));
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                var records = consumer.poll(Duration.ofMillis(250));
                assertThat(records.count()).isEqualTo(1);
                var deadLetter = records.iterator().next();
                assertThat(deadLetter.key()).isEqualTo(orderKey);
                assertThat(deadLetter.value()).isEqualTo("{broken-json");
                var headers = deadLetter.headers();
                assertThat(text(headers, "kafka_dlt-original-topic")).isEqualTo(NotificationEventDecoder.ORDER_TOPIC);
                assertThat(ByteBuffer.wrap(headers.lastHeader("kafka_dlt-original-partition").value()).getInt())
                        .isEqualTo(broken.partition());
                assertThat(ByteBuffer.wrap(headers.lastHeader("kafka_dlt-original-offset").value()).getLong())
                        .isEqualTo(broken.offset());
                assertThat(text(headers, "kafka_dlt-original-consumer-group")).isEqualTo("notification-service");
                assertThat(text(headers, "kafka_dlt-exception-fqcn"))
                        .isEqualTo(InvalidNotificationEventException.class.getName());
                assertThat(text(headers, "kafka_dlt-exception-message")).isEqualTo("Cannot decode notification event");
            });
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(prometheus()).containsPattern(ORDER_DEAD_LETTER_COUNT));
    }

    @Test
    void createsBothDeadLetterTopicsOnABrokerWithoutAutoCreate() throws Exception {
        var topics = List.of(NotificationEventDecoder.ORDER_TOPIC + ".notification-service.DLT",
                NotificationEventDecoder.STOCK_TOPIC + ".notification-service.DLT");
        try (var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers()))) {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                var described = admin.describeTopics(topics).allTopicNames().get();
                assertThat(described.values()).allSatisfy(topic -> assertThat(topic.partitions()).hasSize(3));
                for (String name : topics) {
                    var resource = new ConfigResource(ConfigResource.Type.TOPIC, name);
                    var config = admin.describeConfigs(List.of(resource)).all().get().get(resource);
                    assertThat(config.get(TopicConfig.RETENTION_MS_CONFIG).value()).isEqualTo("604800000");
                    assertThat(config.get(TopicConfig.SEGMENT_MS_CONFIG).value()).isEqualTo("86400000");
                }
            });
        }
    }

    @Test
    void publishesPrometheusMetricsWithoutAToken() throws Exception {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(prometheus()).contains(
                "notifications_pending", "notifications_failed", "notifications_delivery_total",
                "messaging_dead_letters_total").containsPattern("kafka_consumer_fetch_manager_records_lag_max_records\\{"));
    }

    private String prometheus() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create(
                    "http://localhost:" + server.getPort() + "/prometheus")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return response.body();
        }
    }

    private static String text(Headers headers, String name) {
        return new String(headers.lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private List<?> mailpitMessages() throws Exception {
        var url = URI.create("http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025) + "/api/v1/messages");
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return (List<?>) jsonMapper.readValue(response.body(), Map.class).get("messages");
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

    private long notificationCount(String eventId) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                     select count(*) from notifications.notification where event_id = ?
                     """)) {
            statement.setObject(1, UUID.fromString(eventId));
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }
}
