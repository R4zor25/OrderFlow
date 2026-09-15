package com.orderflow.catalog_pricing_service.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.orderflow.catalog_pricing_service.CatalogPricingServiceApplication;
import com.orderflow.catalog_pricing_service.PostgresTestConfiguration;
import com.orderflow.catalog_pricing_service.RedisTestConfiguration;
import com.orderflow.catalog_pricing_service.TestJwtConfiguration;
import com.orderflow.catalog_pricing_service.TestJwts;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class CatalogGracefulShutdownTest {
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void gracefulShutdownWaitsForAnActiveHttpRequestToComplete() throws Exception {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                CatalogPricingServiceApplication.class, PostgresTestConfiguration.class,
                RedisTestConfiguration.class, TestJwtConfiguration.class, RequestConfiguration.class).run("--server.port=0")) {
            int port = context.getEnvironment().getProperty("local.server.port", Integer.class);
            InFlightRequest request = context.getBean(InFlightRequest.class);
            assertThat(context.getEnvironment().getProperty("server.shutdown")).isEqualTo("graceful");
            assertThat(context.getEnvironment().getProperty("spring.lifecycle.timeout-per-shutdown-phase"))
                .isEqualTo("20s");
            ApplicationAvailability availability = context.getBean(ApplicationAvailability.class);
            CompletableFuture<HttpResponse<String>> response = http.sendAsync(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/test/in-flight")).timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + TestJwts.withRoles("ADMIN")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertThat(request.started.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> shutdown = CompletableFuture.runAsync(context::close);
            try {
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(availability.getReadinessState()).isEqualTo(ReadinessState.REFUSING_TRAFFIC));
                assertThat(shutdown).isNotDone();
                request.release.countDown();
                HttpResponse<String> completed = response.get(5, TimeUnit.SECONDS);
                assertThat(completed.statusCode()).isEqualTo(200);
                assertThat(completed.body()).isEqualTo("completed");
                shutdown.get(10, TimeUnit.SECONDS);
            } finally {
                request.release.countDown();
                shutdown.get(25, TimeUnit.SECONDS);
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RequestConfiguration {
        @Bean
        InFlightRequest inFlightRequest() {
            return new InFlightRequest();
        }
    }

    @RestController
    static class InFlightRequest {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @GetMapping("/test/in-flight")
        String handle() throws InterruptedException {
            started.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test did not release the active request");
            }
            return "completed";
        }
    }
}
