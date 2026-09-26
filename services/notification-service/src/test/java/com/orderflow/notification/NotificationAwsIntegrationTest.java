package com.orderflow.notification;

import com.orderflow.notification.messaging.NotificationEventDecoder;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class NotificationAwsIntegrationTest {
    @Test
    void awsInputsEnableHealthyConsumerAndPersistOneLogNotificationForDuplicateEvents() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17.11-bookworm");
             var kafka = new KafkaContainer("apache/kafka:4.3.1")) {
            postgres.start();
            kafka.start();
            try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
                admin.createTopics(List.of(
                        new NewTopic(NotificationEventDecoder.ORDER_TOPIC, 3, (short) 1),
                        new NewTopic(NotificationEventDecoder.STOCK_TOPIC, 3, (short) 1))).all().get();
            }
            var properties = Map.<String, Object>of(
                    "micronaut.server.port", -1,
                    "NOTIFICATION_DB_URL", postgres.getJdbcUrl() + "&currentSchema=notifications",
                    "NOTIFICATION_DB_USERNAME", postgres.getUsername(),
                    "NOTIFICATION_DB_PASSWORD", postgres.getPassword(),
                    "KAFKA_BOOTSTRAP", kafka.getBootstrapServers(),
                    "NOTIFICATION_JWT_ISSUER_URI", "http://alb.example/realms/orderflow",
                    "NOTIFICATION_JWT_JWK_SET_URI", "http://keycloak:8080/realms/orderflow/protocol/openid-connect/certs");
            var server = ApplicationContext.run(EmbeddedServer.class, properties, "aws");
            try (var context = server.getApplicationContext(); var client = HttpClient.newHttpClient()) {
                var environment = context.getEnvironment();
                assertThat(environment.getActiveNames()).contains("aws");
                assertThat(environment.getProperty("notification.channel", String.class)).contains("LOG");
                assertThat(environment.getProperty("notification.messaging.enabled", Boolean.class)).contains(true);
                assertThat(environment.getProperty("kafka.health.enabled", Boolean.class)).contains(true);
                assertThat(environment.getProperty("javamail.enabled", Boolean.class)).contains(false);
                for (var path : List.of("/health/liveness", "/health/readiness")) {
                    var response = client.send(HttpRequest.newBuilder(server.getURI().resolve(path))
                            .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(response.body()).contains("UP").doesNotContain("jdbc", "password", postgres.getPassword());
                }
                var confirmed = Files.readString(Path.of("../../contracts/events/order-confirmed.json"));
                var mapper = context.getBean(io.micronaut.json.JsonMapper.class);
                var tree = mapper.readValue(confirmed, io.micronaut.json.tree.JsonNode.class);
                var eventId = UUID.fromString(tree.get("eventId").getStringValue());
                var key = tree.get("aggregateId").getStringValue();
                try (var producer = new KafkaProducer<String, String>(Map.of(
                        "bootstrap.servers", kafka.getBootstrapServers(),
                        "key.serializer", StringSerializer.class.getName(),
                        "value.serializer", StringSerializer.class.getName(),
                        "acks", "all"))) {
                    producer.send(new ProducerRecord<>(NotificationEventDecoder.ORDER_TOPIC, key, confirmed)).get();
                    producer.send(new ProducerRecord<>(NotificationEventDecoder.ORDER_TOPIC, key, confirmed)).get();
                }
                await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                    try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                         var statement = connection.prepareStatement(
                                 "SELECT count(*) FROM notifications.notification WHERE event_id = ? AND channel = 'LOG'")) {
                        statement.setObject(1, eventId);
                        try (var rows = statement.executeQuery()) {
                            assertThat(rows.next()).isTrue();
                            assertThat(rows.getLong(1)).isEqualTo(1);
                        }
                    }
                });
            }
        }
    }
}
