package com.orderflow.catalog_pricing_service.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.orderflow.catalog_pricing_service.PostgresTestConfiguration;
import com.orderflow.catalog_pricing_service.RedisTestConfiguration;
import com.orderflow.catalog_pricing_service.TestJwtConfiguration;
import com.orderflow.catalog_pricing_service.TestJwts;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, RedisTestConfiguration.class,
    TestJwtConfiguration.class, CatalogVirtualThreadsIntegrationTest.ThreadProbeConfiguration.class})
class CatalogVirtualThreadsIntegrationTest {

    private final Environment environment;
    private final HikariDataSource dataSource;
    private final ObjectMapper json;
    private final AsyncTaskExecutor taskExecutor;

    @Autowired
    CatalogVirtualThreadsIntegrationTest(Environment environment, HikariDataSource dataSource,
            ObjectMapper json, @Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor) {
        this.environment = environment;
        this.dataSource = dataSource;
        this.json = json;
        this.taskExecutor = taskExecutor;
    }

    @Test
    void servesHttpRequestsOnVirtualThreads() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = get(client, "/test/thread", TestJwts.withRoles("ADMIN"));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("true");
        }
    }

    @Test
    void runsApplicationTasksOnVirtualThreads() throws Exception {
        assertThat(taskExecutor.submit(() -> Thread.currentThread().isVirtual())
            .get(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void boundsDatabaseConcurrencyAndExposesPoolPressureOverHttp() throws Exception {
        var heldConnections = new ArrayList<Connection>();
        try (var client = HttpClient.newHttpClient();
                var borrowers = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                for (int i = 0; i < 10; i++) {
                    heldConnections.add(dataSource.getConnection());
                }
                assertThat(dataSource.getMaximumPoolSize()).isEqualTo(10);
                assertThat(dataSource.getMinimumIdle()).isEqualTo(10);
                assertThat(dataSource.getConnectionTimeout()).isEqualTo(2000);
                assertThat(dataSource.getValidationTimeout()).isEqualTo(1000);
                assertThat(metric(client, "hikaricp.connections.active")).isEqualTo(10);
                assertThat(metric(client, "hikaricp.connections.max")).isEqualTo(10);
                var eleventh = borrowers.submit(() -> {
                    try (var connection = dataSource.getConnection()) {
                        return connection.isValid(1);
                    }
                });
                await().atMost(Duration.ofMillis(1800)).untilAsserted(() -> {
                    assertThat(metric(client, "hikaricp.connections.pending")).isEqualTo(1);
                    assertThat(metric(client, "hikaricp.connections.active")).isEqualTo(10);
                });
                assertThatThrownBy(() -> eleventh.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(SQLTransientConnectionException.class);
                assertThat(metric(client, "hikaricp.connections.active")).isEqualTo(10);
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(metric(client, "hikaricp.connections.pending")).isZero());
            } finally {
                for (var connection : heldConnections) {
                    connection.close();
                }
            }
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(metric(client, "hikaricp.connections.active")).isZero());
        }
    }

    private double metric(HttpClient client, String name) throws Exception {
        var response = get(client, "/actuator/metrics/" + name);
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).path("measurements").get(0).path("value").asDouble();
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return get(client, path, null);
    }

    private HttpResponse<String> get(HttpClient client, String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:"
                + environment.getProperty("local.server.port", Integer.class) + path))
            .timeout(Duration.ofSeconds(5));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ThreadProbeConfiguration {
        @Bean
        ThreadProbe threadProbe() {
            return new ThreadProbe();
        }
    }

    @TestComponent
    @RestController
    static class ThreadProbe {
        @GetMapping("/test/thread")
        String currentThreadIsVirtual() {
            return Boolean.toString(Thread.currentThread().isVirtual());
        }
    }
}
