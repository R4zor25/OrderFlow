package com.orderflow.notification;

import com.orderflow.notification.messaging.NotificationEventDecoder;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class NotificationHealthIntegrationTest {
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-bookworm");
    private static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.3.1");

    static {
        postgres.start();
        kafka.start();
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(NotificationEventDecoder.ORDER_TOPIC, 3, (short) 1),
                    new NewTopic(NotificationEventDecoder.STOCK_TOPIC, 3, (short) 1))).all().get();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    void dependencyOutagesAffectReadinessWithoutFailingLivenessAndRecover() throws Exception {
        var properties = Map.<String, Object>ofEntries(
                Map.entry("micronaut.server.port", -1),
                Map.entry("datasources.default.url", postgres.getJdbcUrl() + "&socketTimeout=2&connectTimeout=2"),
                Map.entry("datasources.default.username", postgres.getUsername()),
                Map.entry("datasources.default.password", postgres.getPassword()),
                Map.entry("datasources.default.connection-timeout", 1000),
                Map.entry("kafka.bootstrap.servers", kafka.getBootstrapServers()),
                Map.entry("kafka.health.enabled", true),
                Map.entry("notification.messaging.enabled", true),
                Map.entry("endpoints.health.details-visible", "NEVER")
        );
        var server = ApplicationContext.run(EmbeddedServer.class, properties, "test");
        try (var client = HttpClient.newHttpClient()) {
            assertHealthy(server, client);
            var docker = DockerClientFactory.instance().client();
            docker.pauseContainerCmd(postgres.getContainerId()).exec();
            try {
                await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    assertThat(get(server, client, "/health/readiness").statusCode()).isEqualTo(503);
                    assertThat(get(server, client, "/health/liveness").statusCode()).isEqualTo(200);
                });
            } finally {
                docker.unpauseContainerCmd(postgres.getContainerId()).exec();
            }
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertHealthy(server, client));
            docker.pauseContainerCmd(kafka.getContainerId()).exec();
            try {
                await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    assertThat(get(server, client, "/health/readiness").statusCode()).isEqualTo(503);
                    assertThat(get(server, client, "/health/liveness").statusCode()).isEqualTo(200);
                });
            } finally {
                docker.unpauseContainerCmd(kafka.getContainerId()).exec();
            }
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertHealthy(server, client));
        } finally {
            server.getApplicationContext().close();
        }
    }

    private void assertHealthy(EmbeddedServer server, HttpClient client) throws Exception {
        assertThat(get(server, client, "/health/liveness").statusCode()).isEqualTo(200);
        var readiness = get(server, client, "/health/readiness");
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).contains("\"UP\"").doesNotContain("jdbc", "bootstrap", "password");
    }

    private HttpResponse<String> get(EmbeddedServer server, HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(server.getURI().resolve(path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
