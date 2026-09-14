package com.orderflow.order;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@AutoConfigureMetrics
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, RedisTestConfiguration.class, TestJwtConfiguration.class,
    OrderVirtualThreadsIntegrationTest.RequestConfiguration.class})
class OrderVirtualThreadsIntegrationTest {

    private final HikariDataSource dataSource;
    private final Environment environment;
    private final ObjectMapper json;

    @Autowired
    OrderVirtualThreadsIntegrationTest(DataSource dataSource, Environment environment, ObjectMapper json) {
        this.dataSource = (HikariDataSource) dataSource;
        this.environment = environment;
        this.json = json;
    }

    @Test
    void handlesRealHttpRequestOnVirtualThread() {
        Boolean virtual = client().get().uri("/test/virtual-thread")
            .header("Authorization", "Bearer " + TestJwts.withRoles("CUSTOMER")).retrieve().body(Boolean.class);
        assertThat(virtual).isTrue();
    }

    @Test
    void keepsApplicationAliveWithDaemonVirtualThreads() {
        assertThat(environment.getProperty("spring.main.keep-alive", Boolean.class, false)).isTrue();
    }

    @Test
    void exposesTenConnectionFixedPoolThroughActuator() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.isValid(1)).isTrue();
        }
        assertThat(dataSource.getMaximumPoolSize()).isEqualTo(10);
        assertThat(dataSource.getMinimumIdle()).isEqualTo(10);
        await().untilAsserted(() -> assertThat(dataSource.getHikariPoolMXBean().getTotalConnections()).isEqualTo(10));
        String response = client().get().uri("/actuator/metrics/hikaricp.connections.max")
            .retrieve().body(String.class);
        var metric = json.readTree(response);
        assertThat(metric.path("name").asString()).isEqualTo("hikaricp.connections.max");
        assertThat(metric.path("measurements").get(0).path("value").asDouble()).isEqualTo(10.0);
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RequestConfiguration {
        @Bean
        VirtualThreadProbe virtualThreadProbe() {
            return new VirtualThreadProbe();
        }
    }

    @TestComponent
    @RestController
    static class VirtualThreadProbe {
        @GetMapping("/test/virtual-thread")
        boolean isVirtualThread() {
            return Thread.currentThread().isVirtual();
        }
    }
}
