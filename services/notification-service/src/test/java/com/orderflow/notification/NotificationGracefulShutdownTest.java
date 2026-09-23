package com.orderflow.notification;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.runtime.graceful.GracefulShutdownCapable;
import io.micronaut.runtime.graceful.GracefulShutdownConfiguration;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationGracefulShutdownTest {
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-bookworm");

    static {
        postgres.start();
    }

    @Test
    void applicationCloseWaitsForAnActiveHttpRequest() throws Exception {
        var properties = Map.<String, Object>of(
                "micronaut.server.port", -1,
                "datasources.default.url", postgres.getJdbcUrl(),
                "datasources.default.username", postgres.getUsername(),
                "datasources.default.password", postgres.getPassword(),
                "notification.test.slow-request", true
        );
        var server = ApplicationContext.run(EmbeddedServer.class, properties, "test");
        var context = server.getApplicationContext();
        var flight = context.getBean(InFlightRequest.class);
        try (var client = HttpClient.newHttpClient()) {
            var config = context.getBean(GracefulShutdownConfiguration.class);
            assertThat(config.isEnabled()).isTrue();
            assertThat(config.getGracePeriod()).isEqualTo(Duration.ofSeconds(20));
            var response = client.sendAsync(HttpRequest.newBuilder(server.getURI().resolve("/test/slow"))
                    .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(flight.entered.await(10, TimeUnit.SECONDS)).isTrue();
            var closing = CompletableFuture.runAsync(context::close);
            assertThat(flight.shutdownStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(closing).isNotDone();
            flight.completed.complete("finished");
            assertThat(response.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(response.get().body()).isEqualTo("finished");
            closing.get(10, TimeUnit.SECONDS);
        } finally {
            flight.completed.complete("finished");
            context.close();
        }
    }

    @Singleton
    @Requires(property = "notification.test.slow-request", value = "true")
    static class InFlightRequest implements GracefulShutdownCapable {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch shutdownStarted = new CountDownLatch(1);
        final CompletableFuture<String> completed = new CompletableFuture<>();

        @Override
        public CompletionStage<?> shutdownGracefully() {
            shutdownStarted.countDown();
            return CompletableFuture.completedFuture(null);
        }
    }

    @Controller("/test/slow")
    @Secured(SecurityRule.IS_ANONYMOUS)
    @Requires(property = "notification.test.slow-request", value = "true")
    static class SlowController {
        private final InFlightRequest flight;

        SlowController(InFlightRequest flight) {
            this.flight = flight;
        }

        @Get
        Mono<String> get() {
            return Mono.defer(() -> {
                flight.entered.countDown();
                return Mono.fromFuture(flight.completed);
            });
        }
    }
}
