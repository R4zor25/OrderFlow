package com.orderflow.order;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.orderflow.order.pricing.PricingGateway;
import com.orderflow.order.pricing.quote.QuoteLineRequest;
import com.orderflow.order.pricing.quote.QuoteRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({PostgresTestConfiguration.class, RedisTestConfiguration.class, KeycloakTestConfiguration.class})
class PricingClientCredentialsIntegrationTest {

    private static final WireMockServer catalog = new WireMockServer(options().dynamicPort());
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private PricingGateway pricingGateway;

    @BeforeAll
    static void startCatalog() {
        catalog.start();
        catalog.stubFor(post(urlEqualTo("/api/v1/quotes")).willReturn(aResponse()
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"customerGroup":"B2B_STANDARD","currency":"EUR","lines":[],
                 "totalNet":0,"totalVat":0,"totalGross":0,
                 "quotedAt":"2026-10-01T10:15:30Z"}
                """)));
    }

    @AfterAll
    static void stopCatalog() {
        catalog.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("order.pricing.base-url", catalog::baseUrl);
        registry.add("spring.security.oauth2.client.provider.keycloak.token-uri",
            () -> KeycloakTestContainer.realmUrl() + "/protocol/openid-connect/token");
        registry.add("spring.security.oauth2.client.registration.catalog-pricing.client-secret",
            () -> KeycloakTestContainer.CLIENT_SECRET);
    }

    @Test
    void sendsKeycloakServiceTokenWithPricingScope() throws Exception {
        var request = new QuoteRequest("B2B_STANDARD", List.of(new QuoteLineRequest("CHAIR-001", 1)));

        pricingGateway.quote(request);
        pricingGateway.quote(request);

        List<LoggedRequest> calls = catalog.findAll(postRequestedFor(urlEqualTo("/api/v1/quotes")));
        assertThat(calls).hasSize(2);
        String first = calls.get(0).getHeader("Authorization");
        assertThat(first).startsWith("Bearer ");
        assertThat(calls.get(1).getHeader("Authorization")).isEqualTo(first);
        String payload = first.substring("Bearer ".length()).split("\\.")[1];
        var claims = JSON.readTree(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
        assertThat(claims.path("scope").asString().split(" ")).contains("pricing.quote");
    }
}
