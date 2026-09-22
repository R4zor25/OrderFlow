package com.orderflow.order;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "order.messaging.enabled=true",
    "order.outbox.relay.enabled=false",
    "POSTGRES_PASSWORD=test",
    "spring.kafka.listener.auto-startup=false"
})
@Import({PostgresTestConfiguration.class, RedisTestConfiguration.class, KafkaTestConfiguration.class})
@ActiveProfiles("local")
@DirtiesContext
class HealthProbesIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private KafkaContainer broker;

    @Autowired
    @Qualifier("redisContainer")
    private GenericContainer<?> redis;

    @Test
    void dependencyFailuresShowInFullHealthWithoutStoppingProbesOrExposingDetails() {
        assertProbe("/actuator/health/liveness", 200, "UP");
        assertProbe("/actuator/health/readiness", 200, "UP");
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(redis.getContainerId()).exec();
        try {
            assertProbe("/actuator/health/readiness", 200, "UP");
            assertHealth(503, "DOWN");
            assertProbe("/actuator/health/liveness", 200, "UP");
        }
        finally {
            docker.unpauseContainerCmd(redis.getContainerId()).exec();
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertHealth(200, "UP"));

        broker.stop();
        long started = System.nanoTime();
        assertHealth(503, "DOWN");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertProbe("/actuator/health/readiness", 200, "UP");
        assertProbe("/actuator/health/liveness", 200, "UP");
    }

    private void assertHealth(int expectedStatus, String status) {
        assertBody("/actuator/health", expectedStatus, "{\"groups\":[\"liveness\",\"readiness\"],\"status\":\"" + status + "\"}");
    }

    private void assertProbe(String path, int expectedStatus, String status) {
        assertBody(path, expectedStatus, "{\"status\":\"" + status + "\"}");
    }

    private void assertBody(String path, int expectedStatus, String body) {
        RestClient.create("http://localhost:" + port).get()
            .uri(path)
            .exchange((request, response) -> {
                assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
                assertThat(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo(body);
                return null;
            });
    }
}
