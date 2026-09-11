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
    void essentialDependencyFailuresStopReadinessWithoutStoppingLivenessOrExposingDetails() {
        assertProbe("liveness", 200, "UP");
        assertProbe("readiness", 200, "UP");
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(redis.getContainerId()).exec();
        try {
            assertProbe("readiness", 503, "DOWN");
            assertProbe("liveness", 200, "UP");
        }
        finally {
            docker.unpauseContainerCmd(redis.getContainerId()).exec();
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertProbe("readiness", 200, "UP"));

        broker.stop();
        long started = System.nanoTime();
        assertProbe("readiness", 503, "DOWN");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertProbe("liveness", 200, "UP");
    }

    private void assertProbe(String group, int expectedStatus, String status) {
        RestClient.create("http://localhost:" + port).get()
            .uri("/actuator/health/" + group)
            .exchange((request, response) -> {
                assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
                assertThat(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("{\"status\":\"" + status + "\"}");
                return null;
            });
    }
}
