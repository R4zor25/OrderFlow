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
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.kafka.KafkaContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "inventory.messaging.enabled=true",
    "spring.kafka.listener.auto-startup=false"
})
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class})
@ActiveProfiles("local")
@DirtiesContext
class InventoryHealthIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private KafkaContainer kafka;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2)).build();

    @Test
    void kafkaOutageShowsInFullHealthWhileProbesStayUp() throws Exception {
        assertProbe("/actuator/health/liveness", 200, "UP");
        assertProbe("/actuator/health/readiness", 200, "UP");

        kafka.stop();

        assertProbe("/actuator/health", 503, "DOWN");
        assertProbe("/actuator/health/readiness", 200, "UP");
        assertProbe("/actuator/health/liveness", 200, "UP");
    }

    private void assertProbe(String path, int statusCode, String status) throws Exception {
        var request = HttpRequest.newBuilder(
            URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(6)).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(statusCode);
        assertThat(response.body()).contains("\"status\":\"" + status + "\"");
    }
}
