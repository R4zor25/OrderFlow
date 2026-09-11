package com.orderflow.catalog_pricing_service.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.orderflow.catalog_pricing_service.PostgresTestConfiguration;
import com.orderflow.catalog_pricing_service.RedisTestConfiguration;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "POSTGRES_PASSWORD=test", "spring.datasource.hikari.connection-timeout=1000",
    "spring.datasource.hikari.data-source-properties.socketTimeout=1",
    "spring.datasource.hikari.validation-timeout=1000", "spring.data.redis.timeout=1s", "spring.data.redis.connect-timeout=1s"
})
@ActiveProfiles("local")
@Import({PostgresTestConfiguration.class, RedisTestConfiguration.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogHealthProbeTest {
    private final ConfigurableApplicationContext context;
    private final PostgreSQLContainer postgres;
    private final GenericContainer<?> redis;
    private final HealthEndpointGroups groups;
    private final int port;
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    CatalogHealthProbeTest(ConfigurableApplicationContext context, PostgreSQLContainer postgres,
                           @Qualifier("redisContainer") GenericContainer<?> redis, HealthEndpointGroups groups,
                           @LocalServerPort int port) {
        this.context = context;
        this.postgres = postgres;
        this.redis = redis;
        this.groups = groups;
        this.port = port;
    }

    @Test
    @Order(1)
    void exposesOnlyProcessStateInLivenessAndRequiredDependenciesInReadiness() throws Exception {
        assertThat(groups.get("liveness")).isNotNull();
        assertThat(groups.get("liveness").isMember("livenessState")).isTrue();
        assertThat(groups.get("liveness").isMember("db")).isFalse();
        assertThat(groups.get("liveness").isMember("redis")).isFalse();
        assertThat(groups.get("readiness").isMember("readinessState")).isTrue();
        assertThat(groups.get("readiness").isMember("db")).isTrue();
        assertThat(groups.get("readiness").isMember("redis")).isTrue();
        assertProbe("readiness", 200);
        assertProbe("liveness", 200);
    }

    @Test
    @Order(2)
    void databaseOutageStopsReadinessWhileLivenessStaysHealthyAndRecovers() throws Exception {
        postgres.getDockerClient().pauseContainerCmd(postgres.getContainerId()).exec();
        try {
            assertProbe("readiness", 503);
            assertProbe("liveness", 200);
        } finally {
            postgres.getDockerClient().unpauseContainerCmd(postgres.getContainerId()).exec();
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertProbe("readiness", 200));
        assertProbe("liveness", 200);
    }

    @Test
    @Order(3)
    void redisOutageStopsReadinessWhileLivenessStaysHealthyAndRecovers() throws Exception {
        redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
        try {
            assertProbe("readiness", 503);
            assertProbe("liveness", 200);
        } finally {
            redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertProbe("readiness", 200));
    }

    private void assertProbe(String group, int expectedStatus) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/actuator/health/" + group))
            .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s: %s", group, response.body()).isEqualTo(expectedStatus);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

}
