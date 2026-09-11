package com.orderflow.inventory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.kafka.KafkaContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "inventory.messaging.enabled=true",
    "spring.kafka.listener.auto-startup=false",
    "management.endpoint.health.group.readiness.include=readinessState,db,kafka"
})
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class})
@DirtiesContext
class InventoryHealthIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private KafkaContainer kafka;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2)).build();

    @Test
    void kafkaOutageRejectsReadinessWhileLivenessStaysUp() throws Exception {
        assertProbe("liveness", 200, "UP");
        assertProbe("readiness", 200, "UP");

        kafka.stop();

        assertProbe("readiness", 503, "DOWN");
        assertProbe("liveness", 200, "UP");
    }

    private void assertProbe(String group, int statusCode, String status) throws Exception {
        var request = HttpRequest.newBuilder(
            URI.create("http://localhost:" + port + "/actuator/health/" + group))
            .timeout(Duration.ofSeconds(6)).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(statusCode);
        assertThat(response.body()).contains("\"status\":\"" + status + "\"");
    }
}
