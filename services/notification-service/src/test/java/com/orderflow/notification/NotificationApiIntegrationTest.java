package com.orderflow.notification;

import com.orderflow.notification.infrastructure.persistence.NotificationRepository;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationApiIntegrationTest {

    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-bookworm");
    private static TestJwks jwks;
    private static EmbeddedServer server;
    private static final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        postgres.start();
        jwks = new TestJwks();
        server = ApplicationContext.run(EmbeddedServer.class, Map.of(
                "micronaut.server.port", -1,
                "datasources.default.url", postgres.getJdbcUrl(),
                "datasources.default.username", postgres.getUsername(),
                "datasources.default.password", postgres.getPassword(),
                "micronaut.security.token.jwt.signatures.jwks.keycloak.url", jwks.url()), "test");
    }

    @AfterAll
    static void stop() {
        server.getApplicationContext().close();
        jwks.close();
        postgres.stop();
    }

    @Test
    void listsNotificationsOfAnOrderForAdmin() throws Exception {
        var repository = server.getApplicationContext().getBean(NotificationRepository.class);
        UUID orderId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        repository.insertPending(UUID.randomUUID(), UUID.randomUUID(), orderId, "OrderConfirmed",
                "buyer@example.test", "Order confirmed", "secret body", "LOG", now, now);
        repository.insertPending(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "OrderConfirmed",
                "other@example.test", "Other", "other body", "LOG", now, now);

        var response = get("/api/v1/notifications?orderId=" + orderId, jwks.token("ADMIN"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"orderId\":\"" + orderId + "\"", "\"recipient\":\"buyer@example.test\"",
                        "\"type\":\"OrderConfirmed\"", "\"status\":\"PENDING\"", "\"attempts\":0")
                .doesNotContain("secret body", "\"body\"", "other@example.test");
    }

    @Test
    void rejectsWithoutTokenWith401() throws Exception {
        assertThat(get("/api/v1/notifications?orderId=" + UUID.randomUUID(), null).statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsNonAdminWith403() throws Exception {
        assertThat(get("/api/v1/notifications?orderId=" + UUID.randomUUID(), jwks.token("CUSTOMER")).statusCode())
                .isEqualTo(403);
    }

    @Test
    void rejectsMissingOrderIdWith400() throws Exception {
        assertThat(get("/api/v1/notifications", jwks.token("ADMIN")).statusCode()).isEqualTo(400);
    }

    @Test
    void rejectsTokenFromAnotherIssuerWith401() throws Exception {
        assertThat(get("/api/v1/notifications?orderId=" + UUID.randomUUID(),
                jwks.tokenFrom("http://evil.example/realms/orderflow", "ADMIN")).statusCode()).isEqualTo(401);
    }

    @Test
    void healthStaysAnonymous() throws Exception {
        assertThat(get("/health/liveness", null).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.getPort() + path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
