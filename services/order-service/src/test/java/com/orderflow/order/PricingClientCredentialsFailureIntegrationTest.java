package com.orderflow.order;

import java.util.List;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.orderflow.order.pricing.PricingGateway;
import com.orderflow.order.pricing.PricingUnavailableException;
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

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import({PostgresTestConfiguration.class, RedisTestConfiguration.class, KeycloakTestConfiguration.class})
class PricingClientCredentialsFailureIntegrationTest {

    private static final WireMockServer catalog = new WireMockServer(options().dynamicPort());

    @Autowired
    private PricingGateway pricingGateway;

    @BeforeAll
    static void startCatalog() {
        catalog.start();
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
            () -> "wrong-secret");
    }

    @Test
    void rejectsQuoteWithoutCallingCatalogWhenCredentialsAreInvalid() {
        var request = new QuoteRequest("B2B_STANDARD", List.of(new QuoteLineRequest("CHAIR-001", 1)));

        assertThatThrownBy(() -> pricingGateway.quote(request)).isInstanceOf(PricingUnavailableException.class);

        catalog.verify(0, postRequestedFor(urlEqualTo("/api/v1/quotes")));
    }
}
